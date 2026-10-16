"""The `remember` command. All destinations are explicit; no remembered server/config file."""

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

import paramiko

from . import __version__
from .model import Error, date, duration, safe
from .repository import Repository, download
from .store import Source, Store


def parser():
    common = argparse.ArgumentParser(add_help=False)
    for flags, kwargs in [
        (("-P", "--port"), dict(type=int, help="SSH port (like scp -P)")),
        (("-i", "--identity"), dict(help="SSH private-key file; otherwise use SSH agent/default keys")),
        (("--host-key",), dict(help="verified SHA256:... host fingerprint instead of known_hosts")),
        (("--known-hosts",), dict(help="OpenSSH known_hosts file (default: ~/.ssh/known_hosts)")),
        (("--password",), dict(action="store_true", help="prompt for an SSH password (never place it in arguments)")),
        (("--ask-passphrase",), dict(action="store_true", help="prompt for an encrypted private key's passphrase")),
        (("--timeout",), dict(type=int, help="connection and socket timeout in seconds (default: 30)")),
        (("--json",), dict(action="store_true", help="machine-readable JSON on stdout; diagnostics on stderr")),
    ]:
        common.add_argument(*flags, default=argparse.SUPPRESS, **kwargs)
    root = argparse.ArgumentParser(prog="remember", parents=[common], description="Work with Remember backups over SFTP or on disk. No application config file required.",
        epilog="Example: remember ls user@server:/backups/remember --search meeting\nRemote paths also accept sftp://user@host:2222/path and [user@][IPv6]:/path.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    root.add_argument("--version", action="version", version=f"remember-backup {__version__}")
    commands = root.add_subparsers(dest="command", required=True)
    def command(name, help, aliases=()):
        child = commands.add_parser(name, aliases=aliases, parents=[common], help=help, description=help)
        child.add_argument("source", metavar="SOURCE", help="[user@]host:/backup/root, SSH URL, or local directory")
        return child
    def filters(child):
        child.add_argument("--search", help="case-insensitive title/ID search")
        child.add_argument("--since", type=parse_date, help="created on/after this UTC date or ISO timestamp")
        child.add_argument("--until", type=parse_date, help="created before this UTC date or ISO timestamp (exclusive)" )
        child.add_argument("--marked", action="store_true", help="only recordings with bookmarks")
        child.add_argument("--sort", choices=["created", "title", "duration", "bytes"], default="created")
        child.add_argument("--reverse", action="store_true", help="reverse the default sort (created: newest first)")
        child.add_argument("--limit", type=positive, help="maximum number of matched recordings")
    filters(command("ls", "List and search completed recordings", aliases=["list"]))
    filters(command("stats", "Summarize completed recordings, audio bytes, time, and bookmarks"))
    child = command("info", "Show metadata, SHA-256, and bookmarks", aliases=["stat"])
    child.add_argument("id", help="full recording ID or unambiguous prefix; 'latest' selects newest")
    child = command("marks", "Show a recording's bookmarks as timestamps")
    child.add_argument("id")
    child = command("get", "Download selected audio + metadata; verify before publishing", aliases=["cp"])
    child.add_argument("ids", nargs="+", help="recording IDs/prefixes or latest")
    child.add_argument("-o", "--output", required=True, metavar="DIRECTORY")
    transfer_options(child)
    child = command("sync", "Incrementally download backups; never propagate server deletions locally")
    child.add_argument("output", metavar="DIRECTORY")
    filters(child); transfer_options(child)
    child = command("verify", "Read audio and check every size and SHA-256 (uses download bandwidth)", aliases=["check"])
    child.add_argument("ids", nargs="*", help="IDs/prefixes; omit to verify every completed recording")
    command("doctor", "Report invalid manifests, missing/orphaned audio, uploads, and damaged trash")
    child = command("play", "Verify a temporary local copy, then play with ffplay, mpv, or afplay")
    child.add_argument("id")
    child.add_argument("--player", help="blocking player command, e.g. 'mpv --no-video'; no shell is used")
    child = command("rename", "Rename a backup's title in metadata; never rename its stable file ID")
    child.add_argument("id"); child.add_argument("title")
    child.add_argument("--dry-run", action="store_true")
    child = command("rm", "Move selected backups to server-side trash (reversible)", aliases=["remove"])
    child.add_argument("ids", nargs="+")
    child.add_argument("-y", "--yes", action="store_true", help="confirm the explicitly selected recordings without prompting")
    child.add_argument("--dry-run", action="store_true")
    command("trash", "List recoverable trash entries, including interrupted moves")
    child = command("restore", "Restore a trash entry, verify its audio, and refuse existing-file conflicts")
    child.add_argument("id", help="trash ID or unambiguous prefix")
    child.add_argument("--dry-run", action="store_true")
    child = command("purge", "Permanently delete explicitly selected trash entries; cannot be undone")
    child.add_argument("ids", nargs="+", help="trash IDs/prefixes (no implicit 'all')")
    child.add_argument("-y", "--yes", action="store_true")
    child.add_argument("--dry-run", action="store_true")
    return root


def positive(value):
    try:
        result = int(value)
        if result > 0:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("must be a positive integer")


def parse_date(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return int(parsed.replace(tzinfo=timezone.utc).timestamp() * 1000) if parsed.tzinfo is None else int(parsed.timestamp() * 1000)
    except (ValueError, OverflowError) as exc:
        raise argparse.ArgumentTypeError("use YYYY-MM-DD or an ISO timestamp") from exc


def transfer_options(child):
    child.add_argument("--force", action="store_true", help="replace differing local audio only after successful verification")
    child.add_argument("--no-resume", action="store_true", help="restart this tool's partial downloads from zero")
    child.add_argument("--dry-run", action="store_true", help="show selected transfers without writing local files")


def selected(clips, selectors):
    result = []
    for selector in selectors:
        if selector == "latest":
            matches = clips[:1]
        else:
            if not re.fullmatch(r"[a-fA-F0-9-]{4,36}", selector):
                raise Error("Use a full recording ID, a prefix of at least four characters, or latest")
            exact = [clip for clip in clips if clip.id == selector]
            matches = exact or [clip for clip in clips if clip.id.lower().startswith(selector.lower())]
        if len(matches) != 1:
            raise Error(f"{safe(selector)}: {'ambiguous prefix; use more of the ID' if matches else 'no completed recording found'}")
        if matches[0].id not in {clip.id for clip in result}:
            result.append(matches[0])
    return result


def filtered(clips, args):
    result = [clip for clip in clips if
              (not args.search or args.search.casefold() in (clip.title + " " + clip.id).casefold()) and
              (args.since is None or clip.raw["created"] >= args.since) and
              (args.until is None or clip.raw["created"] < args.until) and
              (not args.marked or clip.marks)]
    key = (lambda clip: clip.title.casefold()) if args.sort == "title" else (lambda clip: clip.raw[args.sort])
    result.sort(key=lambda clip: (key(clip), clip.id), reverse=(args.sort == "created") != args.reverse)
    return result[:args.limit] if args.limit else result


def output(value, args):
    if getattr(args, "json", False):
        print(json.dumps(value, ensure_ascii=False, allow_nan=False))
    elif isinstance(value, str):
        print(safe(value))
    else:
        print(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False))


def table(headers, rows):
    rows = [[safe(value) for value in row] for row in rows]
    if not rows:
        print("No matching recordings.")
        return
    widths = [max(len(header), *(len(row[i]) for row in rows)) for i, header in enumerate(headers)]
    for row in [headers, *rows]:
        print("  ".join(value.ljust(width) for value, width in zip(row, widths)).rstrip())


def human_bytes(value):
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if amount < 1024 or unit == "TiB":
            return f"{amount:.1f} {unit}" if unit != "B" else f"{value} B"
        amount /= 1024


def download_progress(clip_id, args):
    last = [0.0]
    def report(done, total):
        now = time.monotonic()
        if sys.stderr.isatty() and not getattr(args, "json", False) and (now - last[0] >= .5 or done == total):
            last[0] = now
            print(f"\r{clip_id[:12]}  {done / total:6.1%}  {human_bytes(done)} / {human_bytes(total)}", end="\n" if done == total else "", file=sys.stderr, flush=True)
    return report


def confirm(args, message):
    if args.dry_run or args.yes:
        return
    if getattr(args, "json", False) or not sys.stdin.isatty():
        raise Error("Confirmation required: review with --dry-run, then pass --yes")
    print(message, file=sys.stderr)
    print("Type yes to continue: ", file=sys.stderr, end="", flush=True)
    if input().strip() != "yes":
        raise Error("Cancelled; no changes made")


def trash_selection(entries, selectors):
    chosen = []
    for selector in selectors:
        if not re.fullmatch(r"[a-f0-9]{4,32}", selector):
            raise Error("Use a trash ID or a prefix of at least four characters")
        matches = [item for item in entries if item["trash_id"].startswith(selector)]
        if len(matches) != 1:
            raise Error(f"Trash ID {safe(selector)} is missing or ambiguous")
        entry = matches[0]
        if entry["state"] == "damaged":
            raise Error(f"Trash entry requires manual inspection: {safe(entry.get('error'))}")
        if entry not in chosen:
            chosen.append(entry)
    return chosen


def player_command(value):
    if value:
        result = shlex.split(value, posix=os.name != "nt")
        if result:
            return result
        raise Error("Player command is empty")
    for executable, options in [("ffplay", ["-nodisp", "-autoexit"]), ("mpv", ["--no-video"]), ("afplay", [])]:
        if shutil.which(executable):
            return [executable, *options]
    raise Error("Install ffplay/mpv, or pass --player 'your blocking audio player'")


def run(args):
    options = {key: getattr(args, key) for key in ("port", "identity", "host_key", "known_hosts", "password", "ask_passphrase", "timeout") if hasattr(args, key)}
    if options.get("timeout", 30) <= 0:
        raise Error("Timeout must be positive")
    source = Source.parse(args.source)
    with Store(source, **options) as store:
        repo = Repository(store)
        command = {"list": "ls", "stat": "info", "cp": "get", "check": "verify", "remove": "rm"}.get(args.command, args.command)
        if command in ("trash", "restore", "purge"):
            entries = repo.trash()
            if command == "trash":
                if getattr(args, "json", False):
                    output(entries, args)
                else:
                    table(["TRASH ID", "STATE", "DELETED (UTC)", "TITLE"], [[item["trash_id"], item["state"], date(item.get("deleted", 0)), item.get("manifest", {}).get("title", item.get("error", ""))] for item in entries])
                return 1 if any(item["state"] != "complete" for item in entries) else 0
            chosen = trash_selection(entries, [args.id] if command == "restore" else args.ids)
            if command == "purge":
                confirm(args, "Permanently delete these trash entries (cannot be undone):\n" + "\n".join(item["trash_id"] + " " + safe(item["manifest"]["title"]) for item in chosen))
            results = []
            for entry in chosen:
                result = {"trash_id": entry["trash_id"], "id": entry["manifest"]["id"], "action": command, "dry_run": args.dry_run}
                try:
                    if not args.dry_run:
                        repo.restore(entry) if command == "restore" else repo.purge(entry)
                except (Error, OSError) as exc:
                    result["error"] = str(exc)
                results.append(result)
            output(results, args)
            return 1 if any("error" in result for result in results) else 0
        clips, issues = repo.inventory()
        if command == "doctor":
            trash = repo.trash()
            trash_issues = [entry for entry in trash if entry["state"] != "complete"]
            output({"completed": len(clips), "issues": issues, "trash_issues": trash_issues}, args)
            return 1 if issues or trash_issues else 0
        for issue in issues:
            print(f"warning: {safe(issue['file'])}: {safe(issue['problem'])}", file=sys.stderr)
        if command in ("ls", "stats", "sync"):
            clips = filtered(clips, args)
        elif command in ("info", "marks", "play", "rename"):
            clips = selected(clips, [args.id])
        elif command in ("get", "rm") or command == "verify" and args.ids:
            clips = selected(clips, args.ids)
        if command == "ls":
            if getattr(args, "json", False):
                output([clip.summary() for clip in clips], args)
            else:
                table(["ID", "CREATED (UTC)", "DURATION", "SIZE", "MARKS", "TITLE"], [[clip.id, date(clip.raw["created"]), duration(clip.raw["duration"]), human_bytes(clip.raw["bytes"]), len(clip.marks), clip.title] for clip in clips])
        elif command == "stats":
            result = {"recordings": len(clips), "bytes": sum(clip.raw["bytes"] for clip in clips), "duration_ms": sum(clip.raw["duration"] for clip in clips), "bookmarks": sum(len(clip.marks) for clip in clips), "invalid_or_incomplete": len(issues)}
            if getattr(args, "json", False):
                output(result, args)
            else:
                print(f"Recordings: {result['recordings']}\nAudio:      {human_bytes(result['bytes'])}\nDuration:   {duration(result['duration_ms'])}\nBookmarks:  {result['bookmarks']}\nIssues:     {result['invalid_or_incomplete']}")
        elif command == "info":
            clip = clips[0]
            if getattr(args, "json", False):
                output(clip.summary(), args)
            else:
                print(f"Title:    {safe(clip.title)}\nID:       {clip.id}\nCreated:  {date(clip.raw['created'])}\nDuration: {duration(clip.raw['duration'])}\nAudio:    {human_bytes(clip.raw['bytes'])} ({clip.raw['bytes']} bytes)\nSHA-256:  {clip.raw['sha256']}\nBookmarks: {len(clip.marks)}")
                for i, mark in enumerate(clip.marks, 1):
                    print(f"  {i:3d}  {duration(mark)}  ({mark} ms)")
        elif command == "marks":
            marks = [{"number": i + 1, "milliseconds": mark, "time": duration(mark)} for i, mark in enumerate(clips[0].marks)]
            if getattr(args, "json", False):
                output(marks, args)
            else:
                table(["MARK", "TIME", "MILLISECONDS"], [[item["number"], item["time"], item["milliseconds"]] for item in marks])
        elif command == "verify":
            results = []
            for clip in clips:
                try:
                    repo.verify(clip)
                    results.append({"id": clip.id, "ok": True})
                except (Error, OSError) as exc:
                    results.append({"id": clip.id, "ok": False, "error": str(exc)})
            output(results, args)
            return 1 if any(not item["ok"] for item in results) or issues else 0
        elif command in ("get", "sync"):
            results = []
            for clip in clips:
                if args.dry_run:
                    results.append({"id": clip.id, "bytes": clip.raw["bytes"], "action": "download or verify existing", "dry_run": True})
                    continue
                try:
                    result = download(store, clip, args.output, force=args.force, resume=not args.no_resume, progress=download_progress(clip.id, args))
                    results.append(result)
                    if not getattr(args, "json", False):
                        print(f"{result['status']}: {safe(result['file'])}", file=sys.stderr)
                except (Error, OSError) as exc:
                    results.append({"id": clip.id, "status": "failed", "error": str(exc)})
            output(results, args)
            return 1 if any(item.get("status") == "failed" for item in results) or issues else 0
        elif command == "play":
            player = player_command(args.player)
            with tempfile.TemporaryDirectory(prefix="remember-play-") as folder:
                result = download(store, clips[0], folder, progress=download_progress(clips[0].id, args))
                # Server titles never form commands or local paths. Keep the verified file until the player exits.
                subprocess.run([*player, result["file"]], check=True, stdout=sys.stderr)
            output({"id": clips[0].id, "played": True}, args)
        elif command == "rename":
            if not 1 <= len(args.title.strip()) <= 200:
                raise Error("Title must contain 1–200 characters")
            value = {**clips[0].raw, "title": args.title.strip()} if args.dry_run else repo.rename_title(clips[0], args.title)
            output({"id": clips[0].id, "title": value["title"], "dry_run": args.dry_run}, args)
        elif command == "rm":
            confirm(args, "Move these recordings to recoverable trash:\n" + "\n".join(clip.id + " " + safe(clip.title) for clip in clips))
            # Validate all selections before making the first change.
            for clip in clips:
                repo.unchanged(clip)
            results = []
            for clip in clips:
                result = {"id": clip.id, "title": clip.title, "action": "trash", "dry_run": args.dry_run}
                try:
                    if not args.dry_run:
                        result["trash_id"] = repo.remove(clip)["trash_id"]
                except (Error, OSError) as exc:
                    result["error"] = str(exc)
                results.append(result)
            output(results, args)
            return 1 if any("error" in result for result in results) else 0
        return 0


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        return run(args)
    except KeyboardInterrupt:
        print("Interrupted; unverified downloads remain resumable. Inspect trash for interrupted moves.", file=sys.stderr)
        return 130
    except BrokenPipeError:
        return 0
    except (Error, OSError, ValueError, EOFError, paramiko.SSHException, subprocess.SubprocessError) as exc:
        if getattr(args, "json", False):
            print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        else:
            print("remember: " + safe(exc), file=sys.stderr)
        return 1
