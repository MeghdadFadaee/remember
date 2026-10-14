"""Disposable, loopback-only SFTP fixture for Android backup integration tests.

Requires Paramiko. Accepts any valid Ed25519 identity for backup-test, offers
an Ed25519 host key, and exposes only a freshly created temporary directory.
Never use this fixture as a production server.
"""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import socket
import tempfile
import threading

import paramiko
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=2222)
    parser.add_argument("--state-file", type=Path, required=True)
    args = parser.parse_args()
    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())
    private = Ed25519PrivateKey.generate().private_bytes(Encoding.PEM, PrivateFormat.OpenSSH, NoEncryption())
    host_key = paramiko.Ed25519Key(file_obj=io.StringIO(private.decode()))
    fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(host_key.asbytes()).digest()).decode().rstrip("=")
    state = {"fingerprint": fingerprint, "port": args.port, "auth_attempts": 0}
    state_lock = threading.Lock()

    def write_state():
        temporary = args.state_file.with_name(args.state_file.name + ".tmp")
        temporary.write_text(json.dumps(state))
        temporary.replace(args.state_file)

    def record_auth():
        with state_lock:
            state["auth_attempts"] += 1
            write_state()

    class Server(paramiko.ServerInterface):
        def check_auth_none(self, username):
            record_auth()
            return paramiko.AUTH_FAILED

        def check_auth_publickey(self, username, key):
            record_auth()
            return paramiko.AUTH_SUCCESSFUL if username == "backup-test" and key.get_name() == "ssh-ed25519" else paramiko.AUTH_FAILED

        def get_allowed_auths(self, username):
            return "publickey"

        def check_channel_request(self, kind, chanid):
            return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    with tempfile.TemporaryDirectory(prefix="remember-sftp-fixture-") as directory:
        root = Path(directory).resolve()
        (root / "remember").mkdir()

        class SFTP(paramiko.SFTPServerInterface):
            def local(self, path):
                result = (root / path.lstrip("/")).resolve()
                if not result.is_relative_to(root):
                    raise PermissionError("Outside fixture root")
                return result

            def canonicalize(self, path):
                return "/" + str(self.local(path).relative_to(root))

            def stat(self, path):
                try:
                    return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
                except OSError as error:
                    return paramiko.SFTPServer.convert_errno(error.errno)

            lstat = stat

            def list_folder(self, path):
                try:
                    result = []
                    for file in self.local(path).iterdir():
                        item = paramiko.SFTPAttributes.from_stat(file.stat())
                        item.filename = file.name
                        result.append(item)
                    return result
                except OSError as error:
                    return paramiko.SFTPServer.convert_errno(error.errno)

            def open(self, path, flags, attr):
                try:
                    fd = os.open(self.local(path), flags, 0o600)
                    mode = "r+b" if flags & os.O_RDWR else ("wb" if flags & os.O_WRONLY else "rb")
                    file = os.fdopen(fd, mode)
                    handle = paramiko.SFTPHandle(flags)
                    if not flags & os.O_WRONLY:
                        handle.readfile = file
                    if flags & (os.O_WRONLY | os.O_RDWR):
                        handle.writefile = file
                    return handle
                except OSError as error:
                    return paramiko.SFTPServer.convert_errno(error.errno)

            def remove(self, path):
                try:
                    self.local(path).unlink()
                    return paramiko.SFTP_OK
                except OSError as error:
                    return paramiko.SFTPServer.convert_errno(error.errno)

            def rename(self, old, new):
                try:
                    self.local(old).rename(self.local(new))
                    return paramiko.SFTP_OK
                except OSError as error:
                    return paramiko.SFTPServer.convert_errno(error.errno)

        transports = []
        threads = []

        def serve(connection):
            transport = paramiko.Transport(connection)
            transports.append(transport)
            transport.add_server_key(host_key)
            transport.set_subsystem_handler("sftp", paramiko.SFTPServer, SFTP)
            try:
                transport.start_server(server=Server())
                while transport.is_active() and not stop.wait(0.1):
                    pass
            except (EOFError, OSError, paramiko.SSHException):
                pass
            finally:
                transport.close()

        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", args.port))
            listener.listen(5)
            listener.settimeout(0.5)
            write_state()
            print("Loopback-only disposable SFTP fixture ready", flush=True)
            try:
                while not stop.is_set():
                    try:
                        connection, _ = listener.accept()
                    except socket.timeout:
                        continue
                    thread = threading.Thread(target=serve, args=(connection,), daemon=True)
                    threads.append(thread)
                    thread.start()
            finally:
                for transport in transports:
                    transport.close()
                for thread in threads:
                    thread.join(timeout=2)


if __name__ == "__main__":
    main()
