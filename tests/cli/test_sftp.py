"""Real SSH/SFTP protocol tests against a disposable loopback-only server, never personal storage."""

import contextlib
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import paramiko
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from remember_cli.cli import main
from remember_cli.model import Error, json_bytes
from remember_cli.repository import Repository, download
from remember_cli.store import Source, Store
from test_cli import FIRST, manifest


class SftpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="remember-cli-sftp-test-")
        cls.folder = Path(cls.temp.name)
        cls.identity = cls.folder / "id_ed25519"
        cls.identity.write_bytes(Ed25519PrivateKey.generate().private_bytes(Encoding.PEM, PrivateFormat.OpenSSH, NoEncryption()))
        cls.identity.chmod(0o600)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            cls.port = listener.getsockname()[1]
        cls.state = cls.folder / "state.json"
        cls.log = (cls.folder / "fixture.log").open("w+")
        fixture = Path(__file__).resolve().parents[2] / "tools/sftp_fixture.py"
        cls.server = subprocess.Popen([sys.executable, str(fixture), "--port", str(cls.port), "--state-file", str(cls.state)], stdout=cls.log, stderr=cls.log)
        deadline = time.monotonic() + 10
        while not cls.state.exists() and time.monotonic() < deadline and cls.server.poll() is None:
            time.sleep(.05)
        if not cls.state.exists():
            cls.log.seek(0)
            cls.server.terminate(); cls.server.wait(timeout=5)
            raise RuntimeError(cls.log.read())
        cls.pin = json.loads(cls.state.read_text())["fingerprint"]
        cls.source_text = f"backup-test@127.0.0.1:/remember"
        cls.source = Source.parse(cls.source_text)

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate(); cls.server.wait(timeout=10)
        cls.log.close(); cls.temp.cleanup()

    def connect(self, pin=None):
        return Store(self.source, port=self.port, identity=str(self.identity), host_key=pin or self.pin, timeout=5)

    def test_identity_rejection_precedes_authentication_and_known_hosts_reuse(self):
        before = json.loads(self.state.read_text())["auth_attempts"]
        with self.assertRaises(Error):
            with self.connect("SHA256:" + "a" * 43):
                pass
        self.assertEqual(json.loads(self.state.read_text())["auth_attempts"], before)
        with patch("sys.stdin", io.StringIO()):
            with self.assertRaises(Error):
                Store(self.source, port=self.port, identity=str(self.identity), known_hosts=str(self.folder / "empty-known-hosts"), timeout=5)
        self.assertEqual(json.loads(self.state.read_text())["auth_attempts"], before)
        class Terminal(io.StringIO):
            def isatty(self):
                return True
        approved = self.folder / "approved-known-hosts"
        with patch("sys.stdin", Terminal("yes\n")), contextlib.redirect_stderr(io.StringIO()):
            with Store(self.source, port=self.port, identity=str(self.identity), known_hosts=str(approved), timeout=5):
                pass
        self.assertIn(f"[127.0.0.1]:{self.port}", approved.read_text())
        with self.connect() as store:
            key = store.client.get_transport().get_remote_server_key()
            hosts = self.folder / "known-hosts"
            hosts.write_text(f"[127.0.0.1]:{self.port} {key.get_name()} {key.get_base64()}\n")
        with Store(self.source, port=self.port, identity=str(self.identity), known_hosts=str(hosts), timeout=5) as store:
            self.assertEqual(list(store.entries()), [])
        before = json.loads(self.state.read_text())["auth_attempts"]
        wrong = paramiko.Ed25519Key.from_private_key_file(str(self.identity))
        hosts.write_text(f"[127.0.0.1]:{self.port} {wrong.get_name()} {wrong.get_base64()}\n")
        with self.assertRaises(paramiko.BadHostKeyException):
            Store(self.source, port=self.port, identity=str(self.identity), known_hosts=str(hosts), timeout=5)
        self.assertEqual(json.loads(self.state.read_text())["auth_attempts"], before)

    def test_real_sftp_list_download_atomic_title_trash_restore_and_purge(self):
        audio = b"disposable recording bytes" * 5000
        doc = manifest(audio=audio)
        with self.connect() as store:
            store.atomic_write(FIRST + ".m4a", audio)
            store.atomic_write(FIRST + ".json", json_bytes(doc))
            repo = Repository(store)
            clips, issues = repo.inventory()
            self.assertEqual(issues, [])
            self.assertEqual(len(clips), 1)
            repo.verify(clips[0])
            result = download(store, clips[0], self.folder / "download")
            self.assertEqual(Path(result["file"]).read_bytes(), audio)
            with patch.object(store.sftp, "posix_rename", side_effect=OSError("Unsupported extension")):
                with self.assertRaises(Error):
                    repo.rename_title(clips[0], "Must not replace the original")
            self.assertEqual(store.read(FIRST + ".json"), clips[0].encoded)
            repo.rename_title(clips[0], "Edited from the CLI")
            self.assertEqual(repo.clip(FIRST).title, "Edited from the CLI")
            repo.remove(repo.clip(FIRST))
            entry = repo.trash()[0]
            self.assertEqual(entry["state"], "complete")
            self.assertEqual(repo.inventory()[0], [])
            repo.restore(entry)
            repo.verify(repo.clip(FIRST))
            repo.remove(repo.clip(FIRST))
            repo.purge(repo.trash()[0])
            self.assertEqual(repo.trash(), [])
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = main(["stats", self.source_text, "-P", str(self.port), "-i", str(self.identity), "--host-key", self.pin, "--json"])
        self.assertEqual(result, 0, stderr.getvalue())
        self.assertEqual(json.loads(stdout.getvalue())["recordings"], 0)


if __name__ == "__main__":
    unittest.main()
