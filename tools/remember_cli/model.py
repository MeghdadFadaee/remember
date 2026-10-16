"""The watch's version-1 completion manifest, never a source of filesystem paths."""

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone

MAX_METADATA = 128 * 1024
MAX_AUDIO = 500 * 1024 * 1024
CHUNK = 64 * 1024
ID = re.compile(r"[a-fA-F0-9-]{36}\Z")


class Error(Exception):
    """An actionable CLI error, without a traceback or credential dump."""


def safe(value):
    """Do not let remote titles/filenames inject terminal control sequences."""
    return "".join(c if c.isprintable() else ascii(c)[1:-1] for c in str(value))


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Error(f"Duplicate metadata field: {safe(key)}")
        result[key] = value
    return result


def load_json(data):
    if len(data) > MAX_METADATA:
        raise Error("Metadata exceeds 128 KiB")
    try:
        def constant(_):
            raise Error("Non-finite JSON numbers are not supported")
        return json.loads(data, object_pairs_hook=_object, parse_constant=constant)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise Error("Invalid JSON metadata") from exc


def json_bytes(value):
    data = (json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
    if len(data) > MAX_METADATA:
        raise Error("Metadata exceeds 128 KiB")
    return data


def integer(value, name, low=-(2**63), high=2**63 - 1):
    if type(value) is not int or not low <= value <= high:
        raise Error(f"Invalid {name}")
    return value


@dataclass(frozen=True)
class Clip:
    id: str
    raw: dict
    encoded: bytes

    @classmethod
    def parse(cls, data, expected_id):
        doc = load_json(data)
        if not isinstance(doc, dict) or type(doc.get("version")) is not int or doc["version"] != 1:
            raise Error("Unsupported manifest version")
        if not ID.fullmatch(expected_id) or doc.get("id") != expected_id:
            raise Error("Manifest ID does not match its filename")
        if not isinstance(doc.get("title"), str):
            raise Error("Invalid title")
        integer(doc.get("created"), "creation timestamp")
        integer(doc.get("duration"), "duration", 0)
        integer(doc.get("bytes"), "audio size", 1, MAX_AUDIO)
        digest = doc.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise Error("Invalid SHA-256")
        marks = doc.get("marks", [])
        if not isinstance(marks, list) or len(marks) > 10000:
            raise Error("Invalid bookmarks")
        for mark in marks:
            integer(mark, "bookmark timestamp")
        return cls(expected_id, doc, data)

    @property
    def title(self):
        return self.raw["title"][:200]

    @property
    def marks(self):
        return [min(max(mark, 0), self.raw["duration"]) for mark in self.raw.get("marks", [])]

    def summary(self):
        return {**self.raw, "title": self.title, "marks": self.marks}


def duration(milliseconds):
    seconds = milliseconds // 1000
    return f"{seconds // 3600:02d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


def date(milliseconds):
    try:
        return datetime.fromtimestamp(milliseconds / 1000, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except (OverflowError, OSError, ValueError):
        return str(milliseconds)


def hash_stream(stream, maximum=None):
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(CHUNK):
        size += len(chunk)
        if maximum is not None and size > maximum:
            raise Error("Audio exceeds its declared size")
        digest.update(chunk)
    return size, digest.hexdigest()
