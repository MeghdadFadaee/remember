"""Bounded SFTP/file operations. No remote shell, recursive traversal, or glob expansion."""

import base64
import getpass
import hashlib
import hmac
import os
import posixpath
import re
import stat
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urlsplit

import paramiko

from .model import Error, MAX_METADATA, safe


@dataclass(frozen=True)
class Source:
    host: str | None
    user: str | None
    port: int | None
    root: str

    @classmethod
    def parse(cls, value):
        if not value or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise Error("Source must be a directory or [user@]host:/backup/path")
        if value.startswith(("sftp://", "ssh://")):
            try:
                uri = urlsplit(value)
                if uri.password is not None or uri.query or uri.fragment or not uri.hostname or not uri.path:
                    raise Error("Use ssh://[user@]host[:port]/backup/path; passwords do not belong in URLs")
                path = unquote(uri.path)
                user = unquote(uri.username) if uri.username else None
                if any(ord(c) < 32 or ord(c) == 127 for c in path + (user or "")):
                    raise Error("SSH paths and usernames cannot contain control characters")
                return cls(uri.hostname, user, uri.port, path)
            except ValueError as exc:
                raise Error("Invalid SSH URL") from exc
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", value):
            raise Error("Only sftp:// and ssh:// URLs are supported")
        match = re.fullmatch(r"(?:(?P<user>[^/@:]+)@)?(?:\[(?P<ipv6>[^\]]+)\]|(?P<host>[^/:]+)):(?P<root>.+)", value)
        if match and not re.match(r"^[A-Za-z]:[\\/]", value):
            return cls(match["ipv6"] or match["host"], match["user"], None, match["root"])
        return cls(None, None, None, str(Path(value).expanduser()))


def fingerprint(key):
    return "SHA256:" + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")


class Trust(paramiko.MissingHostKeyPolicy):
    def __init__(self, pin, known_hosts):
        self.pin = pin
        self.known_hosts = known_hosts

    def missing_host_key(self, client, hostname, key):
        actual = fingerprint(key)
        if self.pin:
            if not hmac.compare_digest(self.pin, actual):
                raise Error(f"Server identity mismatch for {safe(hostname)} (received {actual}); authentication blocked")
            return
        if not sys.stdin.isatty():
            raise Error(f"Unknown SSH host {safe(hostname)} ({actual}). Verify this fingerprint, then pass --host-key {actual} or trust the host with SSH first")
        print(f"Unknown SSH host: {safe(hostname)}\n{key.get_name()} {actual}\nCompare this fingerprint through a trusted channel.", file=sys.stderr)
        print("Trust and remember this host? Type yes: ", file=sys.stderr, end="", flush=True)
        if input().strip() != "yes":
            raise Error("Host was not approved; authentication blocked")
        path = self.known_hosts
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Append preserves unrelated OpenSSH host entries/comments. No auto-trust policy.
        with path.open("a", encoding="utf-8") as output:
            output.write(f"\n{hostname} {key.get_name()} {key.get_base64()}\n")
        os.chmod(path, 0o600)
        client.get_host_keys().add(hostname, key.get_name(), key)


def checked_path(name):
    parts = name.split("/")
    if not name or any(part in ("", ".", "..") or "\\" in part or "\x00" in part for part in parts):
        raise Error("Unsafe backup filename")
    return parts


def regular(mode):
    return isinstance(mode, int) and stat.S_ISREG(mode)


def is_directory(mode):
    return isinstance(mode, int) and stat.S_ISDIR(mode)


class Store:
    def __init__(self, source, *, port=None, identity=None, host_key=None, known_hosts=None, password=False, ask_passphrase=False, timeout=30):
        self.source = source
        self.client = None
        self.sftp = None
        self.proxy = None
        if source.host is None:
            self.root = Path(source.root).resolve(strict=True)
            if not self.root.is_dir():
                raise Error("Local backup root is not a directory")
            return
        self.root = source.root
        try:
            config_path = Path.home() / ".ssh/config"
            config = {}
            if config_path.is_file():
                with config_path.open() as stream:
                    config = paramiko.SSHConfig.from_file(stream).lookup(source.host)
            if config.get("proxyjump") not in (None, "none") and not config.get("proxycommand"):
                raise Error("ProxyJump is not supported yet; use a reachable SSH alias with ProxyCommand instead")
            host = config.get("hostname", source.host)
            user = source.user or config.get("user", getpass.getuser())
            ssh_port = port if port is not None else source.port if source.port is not None else int(config.get("port", 22))
            if not 1 <= ssh_port <= 65535:
                raise Error("SSH port must be between 1 and 65535")
            keys = [str(Path(identity).expanduser())] if identity else config.get("identityfile")
            path = Path(known_hosts).expanduser() if known_hosts else Path.home() / ".ssh/known_hosts"
            client = self.client = paramiko.SSHClient()
            if not host_key:
                client.load_system_host_keys()
                if path.is_file():
                    client.load_host_keys(str(path))
            client.set_missing_host_key_policy(Trust(host_key, path))
            proxy = config.get("proxycommand")
            if proxy and proxy != "none":
                self.proxy = paramiko.ProxyCommand(proxy)
            # Prompting for a password is explicit, but transmitting credentials always follows host verification.
            secret = getpass.getpass("SSH password: ") if password else None
            passphrase = getpass.getpass("SSH key passphrase: ") if ask_passphrase else None
            client.connect(host, port=ssh_port, username=user, key_filename=keys, password=secret,
                           passphrase=passphrase,
                           allow_agent=not password, look_for_keys=not password, timeout=timeout,
                           banner_timeout=timeout, auth_timeout=timeout, channel_timeout=timeout,
                           sock=self.proxy, compress=False)
            self.sftp = client.open_sftp()
            self.sftp.get_channel().settimeout(timeout)
            if self.root == "~" or self.root.startswith("~/"):
                self.root = posixpath.join(self.sftp.normalize("."), self.root[2:] if self.root != "~" else "")
            self.root = self.sftp.normalize(self.root)
            if not is_directory(self.sftp.lstat(self.root).st_mode):
                raise Error("Remote backup root is not a directory")
        except BaseException:
            self.close()
            raise

    def _path(self, name):
        parts = checked_path(name)
        # Inspect every intermediate component, including trash directories, without following symlinks.
        current = self.root
        for part in parts[:-1]:
            current = posixpath.join(current, part) if self.sftp else current / part
            mode = self.sftp.lstat(current).st_mode if self.sftp else current.lstat().st_mode
            if not is_directory(mode):
                raise Error("Backup path contains a symlink or non-directory")
        return posixpath.join(self.root, name) if self.sftp else self.root.joinpath(*parts)

    def entries(self, directory=None):
        base = self.root if directory is None else self._path(directory)
        if directory and not is_directory(self.stat(directory).st_mode):
            raise Error("Backup directory is not a regular directory")
        if self.sftp:
            for item in self.sftp.listdir_iter(base, read_aheads=1):
                yield item.filename, item
        else:
            with os.scandir(base) as iterator:
                for item in iterator:
                    yield item.name, item.stat(follow_symlinks=False)

    def stat(self, name):
        path = self._path(name)
        return self.sftp.lstat(path) if self.sftp else path.lstat()

    def exists(self, name):
        try:
            self.stat(name)
            return True
        except FileNotFoundError:
            return False

    def open(self, name):
        path = self._path(name)
        if not regular(self.stat(name).st_mode):
            raise Error(f"Not a regular file: {safe(name)}")
        if self.sftp:
            return self.sftp.open(path, "rb", bufsize=65536)
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        return os.fdopen(descriptor, "rb")

    def read(self, name):
        size = self.stat(name).st_size
        if type(size) is not int or not 0 <= size <= MAX_METADATA:
            raise Error("Metadata exceeds 128 KiB")
        with self.open(name) as stream:
            data = stream.read(MAX_METADATA + 1)
        if len(data) > MAX_METADATA:
            raise Error("Metadata exceeds 128 KiB")
        return data

    def mkdir(self, name):
        path = self._path(name)
        if self.sftp:
            self.sftp.mkdir(path, mode=0o700)
        else:
            path.mkdir(mode=0o700)

    def rename(self, old, new):
        if self.exists(new):
            raise Error(f"Refusing to replace {safe(new)}")
        if not regular(self.stat(old).st_mode):
            raise Error("Refusing to move a non-regular file")
        if self.sftp:
            self.sftp.rename(self._path(old), self._path(new))
        else:
            # Link/unlink gives no-clobber semantics on a single local filesystem.
            os.link(self._path(old), self._path(new), follow_symlinks=False)
            self._path(old).unlink()

    def remove(self, name):
        if not regular(self.stat(name).st_mode):
            raise Error("Refusing to remove a non-regular file")
        path = self._path(name)
        self.sftp.remove(path) if self.sftp else path.unlink()

    def rmdir(self, name):
        if not is_directory(self.stat(name).st_mode):
            raise Error("Refusing to remove a non-directory")
        path = self._path(name)
        self.sftp.rmdir(path) if self.sftp else path.rmdir()

    def atomic_write(self, name, data, expected=None):
        path = self._path(name)
        temporary = posixpath.join(posixpath.dirname(name), ".remember-" + uuid.uuid4().hex + ".tmp")
        temporary_path = self._path(temporary)
        try:
            if self.sftp:
                with self.sftp.open(temporary_path, "wx") as stream:
                    self.sftp.chmod(temporary_path, 0o600)
                    stream.write(data); stream.flush()
            else:
                descriptor = os.open(temporary_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(data); stream.flush(); os.fsync(stream.fileno())
            if expected is not None and self.read(name) != expected:
                raise Error("Manifest changed during this operation; refresh and retry")
            if expected is None and self.exists(name):
                raise Error(f"Refusing to replace {safe(name)}")
            if self.sftp:
                if expected is None:
                    self.sftp.rename(temporary_path, path)
                else:
                    # OpenSSH extension: never remove the committed manifest as a fallback.
                    try:
                        self.sftp.posix_rename(temporary_path, path)
                    except OSError as exc:
                        raise Error("Server could not atomically replace the manifest; original retained. OpenSSH posix-rename support is required") from exc
            else:
                if expected is None:
                    os.link(temporary_path, path)
                    temporary_path.unlink()
                else:
                    os.replace(temporary_path, path)
        finally:
            if self.exists(temporary):
                self.remove(temporary)

    def close(self):
        if self.sftp:
            self.sftp.close()
        if self.client:
            self.client.close()
        if self.proxy:
            self.proxy.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
