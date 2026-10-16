import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from remember_cli.cli import main, parser
from remember_cli.model import Clip, Error, json_bytes, safe
from remember_cli.repository import Repository, download, exclusive_file
from remember_cli.store import Source, Store

FIRST = "aaaaaaaa-1111-4111-8111-111111111111"
SECOND = "bbbbbbbb-2222-4222-8222-222222222222"


def manifest(clip_id=FIRST, audio=b"synthetic audio fixture", **changes):
    return {"version": 1, "id": clip_id, "title": "Meeting / notes", "created": 1_790_000_000_000,
            "duration": 90000, "marks": [1000, 12000], "bytes": len(audio),
            "sha256": hashlib.sha256(audio).hexdigest(), **changes}


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="remember-cli-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "server"
        self.root.mkdir()
        self.target = Path(self.temp.name) / "downloads"
        self.audio = b"synthetic audio fixture" * 6000
        self.doc = manifest(audio=self.audio, future_field={"preserve": True})
        self.seed(self.doc, self.audio)
        self.store = Store(Source.parse(str(self.root)))
        self.repo = Repository(self.store)

    def seed(self, doc, audio):
        (self.root / (doc["id"] + ".m4a")).write_bytes(audio)
        (self.root / (doc["id"] + ".json")).write_bytes(json_bytes(doc))

    def call(self, *arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), patch("sys.stdin", io.StringIO()):
            code = main(list(arguments))
        return code, stdout.getvalue(), stderr.getvalue()

    def test_source_scp_urls_ipv6_local_paths_and_no_password_urls(self):
        for value in ["me@box:/backups with spaces", "sftp://me@box/backups%20with%20spaces"]:
            parsed = Source.parse(value)
            self.assertEqual((parsed.host, parsed.user, parsed.root), ("box", "me", "/backups with spaces"))
        self.assertEqual(Source.parse("me@[::1]:/backup").host, "::1")
        self.assertEqual(Source.parse("ssh://me@[::1]:2222/backup").port, 2222)
        self.assertIsNone(Source.parse("./backup:notes").host)
        with self.assertRaises(Error):
            Source.parse("sftp://me:secret@host/backup")
        with self.assertRaises(Error):
            Source.parse("https://host/backup")

    def test_global_options_work_before_or_after_command(self):
        for argv in [["--json", "-P", "2222", "ls", str(self.root)], ["ls", str(self.root), "--json", "-P", "2222"]]:
            args = parser().parse_args(argv)
            self.assertTrue(args.json)
            self.assertEqual(args.port, 2222)

    def test_list_filters_bookmarks_machine_json_and_terminal_escape(self):
        code, stdout, stderr = self.call("ls", str(self.root), "--marked", "--search", "NOTES", "--json")
        self.assertEqual(code, 0, stderr)
        self.assertEqual(json.loads(stdout)[0]["future_field"], {"preserve": True})
        code, stdout, _ = self.call("marks", str(self.root), "aaaa", "--json")
        self.assertEqual(json.loads(stdout)[1], {"number": 2, "milliseconds": 12000, "time": "00:00:12"})
        self.assertNotIn("\x1b", safe("title\x1b[31m\n"))

    def test_missing_ambiguous_and_short_ids_never_select_arbitrarily(self):
        doc = manifest("aaaaaaaa-2222-4222-8222-222222222222", b"second")
        self.seed(doc, b"second")
        for selector in ["", "a", "aaaa", "ffff"]:
            code, _, error = self.call("info", str(self.root), selector)
            self.assertEqual(code, 1, selector)
            self.assertTrue(error)

    def test_malformed_oversized_duplicate_and_traversal_metadata_fail_closed(self):
        invalid = [json_bytes({**self.doc, "id": "../../private"}),
                   b'{"version":1,"version":1}', b"[1,2]", b"x" * (128 * 1024 + 1),
                   json_bytes({**self.doc, "bytes": True}), json_bytes({**self.doc, "version": True}),
                   json_bytes({**self.doc, "sha256": "bad"})]
        for payload in invalid:
            (self.root / (FIRST + ".json")).write_bytes(payload)
            clips, issues = self.repo.inventory()
            self.assertEqual(clips, [])
            self.assertTrue(issues)
        with self.assertRaises(Error):
            Clip.parse(b'{"version": NaN}', FIRST)

    def test_doctor_reports_upload_orphan_and_bad_manifest_without_removing(self):
        (self.root / (SECOND + ".m4a")).write_bytes(b"interrupted")
        (self.root / (SECOND + ".m4a.upload")).write_bytes(b"partial")
        code, stdout, _ = self.call("doctor", str(self.root), "--json")
        self.assertEqual(code, 1)
        self.assertEqual(len(json.loads(stdout)["issues"]), 2)
        self.assertTrue((self.root / (SECOND + ".m4a.upload")).exists())

    def test_verified_download_and_metadata_only_incremental_sync(self):
        clip = self.repo.clip(FIRST)
        result = download(self.store, clip, self.target)
        self.assertEqual(result["status"], "downloaded")
        self.assertEqual((self.target / (FIRST + ".m4a")).read_bytes(), self.audio)
        self.repo.rename_title(clip, "Updated title")
        result = download(self.store, self.repo.clip(FIRST), self.target)
        self.assertEqual(result["status"], "unchanged")
        local = json.loads((self.target / (FIRST + ".json")).read_bytes())
        self.assertEqual(local["title"], "Updated title")
        self.assertEqual(local["marks"], self.doc["marks"])
        self.assertEqual(local["future_field"], self.doc["future_field"])

    def test_resume_interruption_and_repair_corrupt_prefix(self):
        clip = self.repo.clip(FIRST)
        original_open = self.store.open
        class Interrupted(io.BytesIO):
            def read(self, count=-1):
                if self.tell() >= 65536:
                    raise OSError("simulated disconnect")
                return super().read(count)
        with patch.object(self.store, "open", return_value=Interrupted(self.audio)):
            with self.assertRaises(OSError):
                download(self.store, clip, self.target)
        partial = self.target / ("." + FIRST + ".m4a.part")
        self.assertEqual(partial.stat().st_size, 65536)
        partial.write_bytes(b"wrong prefix" * 100)
        download(self.store, clip, self.target)
        self.assertEqual((self.target / (FIRST + ".m4a")).read_bytes(), self.audio)

    def test_checksum_failure_never_replaces_existing_audio_even_with_force(self):
        self.target.mkdir()
        local = self.target / (FIRST + ".m4a")
        local.write_bytes(b"precious previous local audio")
        with self.assertRaises(Error):
            download(self.store, self.repo.clip(FIRST), self.target)
        (self.root / (FIRST + ".m4a")).write_bytes(b"X" * len(self.audio))
        with self.assertRaises(Error):
            download(self.store, self.repo.clip(FIRST), self.target, force=True)
        self.assertEqual(local.read_bytes(), b"precious previous local audio")

    @unittest.skipIf(os.name == "nt", "symlink creation requires extra privileges on Windows")
    def test_symlinked_audio_and_local_partial_are_not_followed(self):
        outside = Path(self.temp.name) / "outside"
        outside.write_bytes(b"keep")
        self.target.mkdir()
        (self.target / ("." + FIRST + ".m4a.part")).symlink_to(outside)
        with self.assertRaises((Error, OSError)):
            download(self.store, self.repo.clip(FIRST), self.target)
        self.assertEqual(outside.read_bytes(), b"keep")
        (self.root / (FIRST + ".m4a")).unlink()
        (self.root / (FIRST + ".m4a")).symlink_to(outside)
        clips, issues = self.repo.inventory()
        self.assertEqual(clips, [])
        self.assertTrue(issues)

    def test_rename_preserves_all_other_fields_and_rejects_changed_snapshot(self):
        clip = self.repo.clip(FIRST)
        updated = self.repo.rename_title(clip, "New name")
        self.assertEqual(updated, {**self.doc, "title": "New name"})
        with self.assertRaises(Error):
            self.repo.rename_title(clip, "stale update")
        self.assertEqual(self.repo.clip(FIRST).title, "New name")

    def test_mutations_refuse_active_uploads(self):
        clip = self.repo.clip(FIRST)
        (self.root / (FIRST + ".json.upload")).write_bytes(b"busy")
        with self.assertRaises(Error):
            self.repo.remove(clip)
        with self.assertRaises(Error):
            self.repo.rename_title(clip, "new")
        self.assertTrue((self.root / (FIRST + ".json")).exists())

    def test_trash_restore_and_purge_are_explicit_and_preserve_original_audio(self):
        code, stdout, _ = self.call("rm", str(self.root), "aaaa", "--dry-run", "--json")
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(stdout)[0]["dry_run"])
        code, _, _ = self.call("rm", str(self.root), "aaaa")
        self.assertEqual(code, 1)
        self.assertTrue((self.root / (FIRST + ".m4a")).exists())
        entry = self.repo.remove(self.repo.clip(FIRST))
        self.assertFalse((self.root / (FIRST + ".json")).exists())
        saved = self.repo.trash()[0]
        self.assertEqual(saved["state"], "complete")
        self.repo.restore(saved)
        self.assertEqual((self.root / (FIRST + ".m4a")).read_bytes(), self.audio)
        entry = self.repo.remove(self.repo.clip(FIRST))
        code, _, _ = self.call("purge", str(self.root), entry["trash_id"], "--json")
        self.assertEqual(code, 1)
        code, _, error = self.call("purge", str(self.root), entry["trash_id"], "--yes", "--json")
        self.assertEqual(code, 0, error)
        self.assertEqual(self.repo.trash(), [])

    def test_trash_failure_retains_recoverable_entry_and_original_backup(self):
        rename = self.store.rename
        def fail_audio(old, new):
            if old == FIRST + ".m4a":
                raise OSError("simulated move failure")
            rename(old, new)
        with patch.object(self.store, "rename", side_effect=fail_audio):
            with self.assertRaises(Error):
                self.repo.remove(self.repo.clip(FIRST))
        self.assertEqual(self.repo.clip(FIRST).raw, self.doc)
        entry = self.repo.trash()[0]
        self.assertEqual(entry["state"], "partial")
        self.repo.restore(entry)
        self.assertEqual(self.repo.trash(), [])

    def test_interrupted_restore_can_resume_without_overwrite(self):
        self.repo.remove(self.repo.clip(FIRST))
        entry = self.repo.trash()[0]
        rename = self.store.rename
        def fail_metadata(old, new):
            if new == FIRST + ".json":
                raise OSError("simulated restore failure")
            rename(old, new)
        with patch.object(self.store, "rename", side_effect=fail_metadata):
            with self.assertRaises(OSError):
                self.repo.restore(entry)
        self.repo.restore(self.repo.trash()[0])
        self.assertEqual(self.repo.clip(FIRST).raw, self.doc)

    def test_restore_conflict_and_unknown_trash_files_fail_closed(self):
        entry = self.repo.remove(self.repo.clip(FIRST))
        self.seed(self.doc, self.audio)
        with self.assertRaises(Error):
            self.repo.restore(self.repo.trash()[0])
        folder = self.root / ".remember-trash" / entry["trash_id"]
        (folder / "important-other-file").write_bytes(b"keep")
        self.assertEqual(self.repo.trash()[0]["state"], "damaged")
        code, _, _ = self.call("purge", str(self.root), entry["trash_id"], "--yes")
        self.assertEqual(code, 1)
        self.assertTrue((folder / "important-other-file").exists())

    def test_sync_does_not_propagate_deletions_and_dry_run_creates_nothing(self):
        code, _, error = self.call("sync", str(self.root), str(self.target), "--dry-run", "--json")
        self.assertEqual(code, 0, error)
        self.assertFalse(self.target.exists())
        self.call("sync", str(self.root), str(self.target), "--json")
        self.repo.remove(self.repo.clip(FIRST))
        code, _, error = self.call("sync", str(self.root), str(self.target), "--json")
        self.assertEqual(code, 0, error)
        self.assertEqual((self.target / (FIRST + ".m4a")).read_bytes(), self.audio)

    def test_play_uses_verified_private_temporary_audio_without_a_shell(self):
        paths = []
        def play(argv, **kwargs):
            self.assertIsInstance(argv, list)
            self.assertNotIn("shell", kwargs)
            path = Path(argv[-1])
            self.assertEqual(path.read_bytes(), self.audio)
            paths.append(path)
        with patch("remember_cli.cli.subprocess.run", side_effect=play):
            code, stdout, error = self.call("play", str(self.root), "latest", "--player", sys.executable, "--json")
        self.assertEqual(code, 0, error)
        self.assertTrue(json.loads(stdout)["played"])
        self.assertTrue(paths)
        self.assertFalse(paths[0].exists())

    def test_destination_lock_refuses_competing_downloads(self):
        self.target.mkdir()
        with exclusive_file(self.target / ".remember-lock"):
            with self.assertRaises(Error):
                download(self.store, self.repo.clip(FIRST), self.target)
        self.assertFalse((self.target / (FIRST + ".m4a")).exists())


if __name__ == "__main__":
    unittest.main()
