"""Versioned backup operations, verified downloads, and recoverable server-side trash."""

import contextlib
import hashlib
import os
import re
import stat
import time
import uuid
from pathlib import Path

from .model import CHUNK, ID, MAX_METADATA, Clip, Error, hash_stream, integer, json_bytes, load_json, safe

TRASH = ".remember-trash"


class Repository:
    def __init__(self, store):
        self.store = store

    def clip(self, clip_id):
        if not isinstance(clip_id, str) or not ID.fullmatch(clip_id):
            raise Error("Invalid recording ID")
        clip = Clip.parse(self.store.read(f"{clip_id}.json"), clip_id)
        audio = self.store.stat(f"{clip_id}.m4a")
        if not stat.S_ISREG(audio.st_mode) or audio.st_size != clip.raw["bytes"]:
            raise Error("Missing, linked, or wrong-size audio")
        return clip

    def inventory(self):
        clips, issues, names = [], [], set()
        for name, attrs in self.store.entries():
            names.add(name)
            if not re.fullmatch(r"[a-fA-F0-9-]{36}\.json", name):
                continue
            try:
                if not stat.S_ISREG(attrs.st_mode):
                    raise Error("Not a regular manifest")
                clips.append(self.clip(name[:-5]))
            except (Error, OSError, ValueError, TypeError) as exc:
                issues.append({"file": name, "problem": str(exc)})
        valid = {clip.id for clip in clips}
        for name in sorted(names):
            if name.endswith(".upload"):
                issues.append({"file": name, "problem": "incomplete upload (may still be active)"})
            elif re.fullmatch(r"[a-fA-F0-9-]{36}\.m4a", name) and name[:-4] not in valid:
                issues.append({"file": name, "problem": "audio without a valid completion manifest"})
            elif name.startswith(".remember-") and name.endswith(".tmp"):
                issues.append({"file": name, "problem": "unfinished CLI metadata write"})
        clips.sort(key=lambda item: (item.raw["created"], item.id), reverse=True)
        return clips, issues

    def unchanged(self, clip):
        if self.store.read(f"{clip.id}.json") != clip.encoded:
            raise Error("Manifest changed; refresh and retry")
        audio = self.store.stat(f"{clip.id}.m4a")
        if not stat.S_ISREG(audio.st_mode) or audio.st_size != clip.raw["bytes"]:
            raise Error("Audio changed; refresh and retry")
        if any(self.store.exists(clip.id + suffix) for suffix in (".m4a.upload", ".json.upload")):
            raise Error("An upload for this recording may be active; wait before modifying it")

    def verify(self, clip):
        with self.store.open(f"{clip.id}.m4a") as stream:
            size, digest = hash_stream(stream, clip.raw["bytes"])
        if size != clip.raw["bytes"] or digest != clip.raw["sha256"]:
            raise Error("Audio size/SHA-256 verification failed")

    def rename_title(self, clip, title):
        title = title.strip()
        if not 1 <= len(title) <= 200:
            raise Error("Title must contain 1–200 characters")
        self.unchanged(clip)
        updated = {**clip.raw, "title": title}
        self.store.atomic_write(f"{clip.id}.json", json_bytes(updated), expected=clip.encoded)
        return updated

    def _trash_directory(self, trash_id):
        if not isinstance(trash_id, str) or not re.fullmatch(r"[a-f0-9]{32}", trash_id):
            raise Error("Invalid trash ID")
        return f"{TRASH}/{trash_id}"

    def trash(self):
        if not self.store.exists(TRASH):
            return []
        entries = []
        for name, attrs in self.store.entries(TRASH):
            try:
                if not stat.S_ISDIR(attrs.st_mode):
                    raise Error("Not a regular trash directory")
                entry = self.trash_entry(name)
                entries.append(entry)
            except (Error, OSError, ValueError, TypeError) as exc:
                entries.append({"trash_id": name, "state": "damaged", "error": str(exc)})
        return sorted(entries, key=lambda item: item.get("deleted", 0), reverse=True)

    def trash_entry(self, trash_id):
        base = self._trash_directory(trash_id)
        record = load_json(self.store.read(f"{base}/entry.json"))
        if not isinstance(record, dict) or type(record.get("version")) is not int or record["version"] != 1 or record.get("trash_id") != trash_id:
            raise Error("Invalid trash entry")
        integer(record.get("deleted"), "trash timestamp", 0)
        original = record.get("manifest")
        if not isinstance(original, dict):
            raise Error("Invalid saved manifest")
        clip_id = original.get("id")
        if not isinstance(clip_id, str):
            raise Error("Invalid saved recording ID")
        Clip.parse(json_bytes(original), clip_id)
        names = {name for name, _ in self.store.entries(base)}
        expected = {"entry.json", f"{clip_id}.m4a", f"{clip_id}.json"}
        if names - expected:
            raise Error("Trash entry contains unknown files; manual inspection required")
        for name in names:
            if not stat.S_ISREG(self.store.stat(f"{base}/{name}").st_mode):
                raise Error("Trash entry contains a linked or special file")
        # Interrupted two-file moves remain visible and can be restored or purged explicitly.
        complete = expected <= names
        return {**record, "state": "complete" if complete else "partial", "files": sorted(names)}

    def remove(self, clip):
        self.unchanged(clip)
        if not self.store.exists(TRASH):
            self.store.mkdir(TRASH)
        trash_id = uuid.uuid4().hex
        base = self._trash_directory(trash_id)
        self.store.mkdir(base)
        record = {"version": 1, "trash_id": trash_id, "deleted": time.time_ns() // 1_000_000, "manifest": clip.raw}
        self.store.atomic_write(f"{base}/entry.json", json_bytes(record))
        # Remove the completion marker first; never expose a completed backup with missing audio.
        self.store.rename(f"{clip.id}.json", f"{base}/{clip.id}.json")
        try:
            self.store.rename(f"{clip.id}.m4a", f"{base}/{clip.id}.m4a")
        except BaseException as exc:
            # If a concurrent watch upload published a new manifest, never overwrite it.
            if not self.store.exists(f"{clip.id}.json"):
                self.store.rename(f"{base}/{clip.id}.json", f"{clip.id}.json")
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            raise Error(f"Trash move interrupted. Inspect/restore trash entry {trash_id}") from None
        return record

    def restore(self, entry):
        base = self._trash_directory(entry["trash_id"])
        manifest = entry["manifest"]
        clip_id = manifest["id"]
        # Resolve interrupted removal/restoration using the saved hash, never by filename alone.
        for suffix in (".m4a", ".json"):
            name = clip_id + suffix
            there, here = self.store.exists(f"{base}/{name}"), self.store.exists(name)
            if there and here:
                raise Error(f"Restore would overwrite {name}; existing backups are preserved")
            if not there and not here:
                raise Error(f"Neither trash nor backup root contains {name}; cannot restore")
            if suffix == ".m4a":
                with self.store.open(f"{base}/{name}" if there else name) as stream:
                    size, digest = hash_stream(stream, manifest["bytes"])
                if size != manifest["bytes"] or digest != manifest["sha256"]:
                    raise Error("Trash audio verification failed; nothing restored")
            else:
                candidate = Clip.parse(self.store.read(f"{base}/{name}" if there else name), clip_id)
                if candidate.raw != manifest:
                    raise Error("Trash manifest changed; nothing restored")
        # Audio first, completion marker last. A repeated restore safely resumes an interrupted move.
        for suffix in (".m4a", ".json"):
            name = clip_id + suffix
            if self.store.exists(f"{base}/{name}"):
                self.store.rename(f"{base}/{name}", name)
        self.store.remove(f"{base}/entry.json")
        self.store.rmdir(base)
        return manifest

    def purge(self, entry):
        base = self._trash_directory(entry["trash_id"])
        # Only the exact, validated entry files. Never recursively remove arbitrary server paths.
        current = self.trash_entry(entry["trash_id"])
        if current["manifest"] != entry["manifest"]:
            raise Error("Trash entry changed; refresh and retry")
        for suffix in (".json", ".m4a"):
            name = f"{base}/{entry['manifest']['id']}{suffix}"
            if self.store.exists(name):
                self.store.remove(name)
        self.store.remove(f"{base}/entry.json")
        self.store.rmdir(base)


@contextlib.contextmanager
def exclusive_file(path):
    """OS releases the lock on crashes; the small lock file deliberately remains in the mirror."""
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    stream = os.fdopen(descriptor, "r+b")
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode) or os.fstat(descriptor).st_nlink != 1:
            raise Error("Unsafe local lock file")
        try:
            if os.name == "nt":
                import msvcrt
                if os.fstat(descriptor).st_size == 0:
                    stream.write(b"0"); stream.flush(); stream.seek(0)
                msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise Error("Another CLI process is using this destination") from exc
        yield
    finally:
        stream.close()


def _regular(path):
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise Error(f"Refusing linked or special local file: {safe(path)}")
    return True


def _atomic_local(path, data):
    temporary = path.parent / (".remember-" + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("xb") as stream:
            os.chmod(temporary, 0o600)
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def download(store, clip, directory, *, force=False, resume=True, progress=lambda *_: None):
    """One bounded stream, checked SHA-256, atomic publication; no local audio is overwritten implicitly."""
    directory = Path(directory).expanduser().resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    final = directory / f"{clip.id}.m4a"
    metadata = directory / f"{clip.id}.json"
    partial = directory / f".{clip.id}.m4a.part"
    receipt = directory / f".{clip.id}.part.json"
    snapshot = {key: clip.raw[key] for key in ("id", "bytes", "sha256")}
    with exclusive_file(directory / ".remember-lock"):
        if _regular(final):
            size, digest = final.stat().st_size, None
            if size == clip.raw["bytes"]:
                with final.open("rb") as stream:
                    size, digest = hash_stream(stream, clip.raw["bytes"])
            if size == clip.raw["bytes"] and digest == clip.raw["sha256"]:
                _regular(metadata)
                _atomic_local(metadata, clip.encoded)
                return {"id": clip.id, "status": "unchanged", "file": str(final), "bytes": size}
            if not force:
                raise Error(f"Local audio differs: {safe(final)}. Use --force to replace it")
        _regular(metadata)
        offset = 0
        if _regular(partial) and _regular(receipt) and resume:
            try:
                if receipt.stat().st_size <= MAX_METADATA and load_json(receipt.read_bytes()) == snapshot:
                    offset = partial.stat().st_size
            except Error:
                pass
        if offset > clip.raw["bytes"]:
            offset = 0
        _regular(receipt)
        _atomic_local(receipt, json_bytes(snapshot))
        # Retry a corrupt resumed prefix once from zero; never publish unverified audio.
        for attempt in range(2):
            descriptor = os.open(partial, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
            with os.fdopen(descriptor, "r+b") as output:
                if os.fstat(output.fileno()).st_nlink != 1 or not stat.S_ISREG(os.fstat(output.fileno()).st_mode):
                    raise Error("Unsafe partial download")
                output.truncate(offset); output.seek(offset)
                transferred = offset
                with store.open(f"{clip.id}.m4a") as remote:
                    remote.seek(offset)
                    while chunk := remote.read(min(CHUNK, clip.raw["bytes"] - transferred + 1)):
                        transferred += len(chunk)
                        if transferred > clip.raw["bytes"]:
                            raise Error("Server audio exceeds its manifest; previous local audio retained")
                        output.write(chunk)
                        progress(transferred, clip.raw["bytes"])
                    output.flush(); os.fsync(output.fileno())
                if transferred != clip.raw["bytes"]:
                    raise Error("Incomplete download; partial retained for resume")
                output.seek(0)
                size, digest = hash_stream(output, clip.raw["bytes"])
            if size == clip.raw["bytes"] and digest == clip.raw["sha256"]:
                break
            partial.unlink(missing_ok=True)
            if not offset or attempt:
                receipt.unlink(missing_ok=True)
                raise Error("SHA-256 mismatch; previous local audio retained")
            offset = 0
        # Refuse a concurrently created destination unless replacement was explicitly requested.
        if final.exists() and not force:
            raise Error("Local destination appeared during download; verified partial retained")
        os.replace(partial, final)
        _atomic_local(metadata, clip.encoded)
        receipt.unlink(missing_ok=True)
        return {"id": clip.id, "status": "downloaded", "file": str(final), "bytes": clip.raw["bytes"]}
