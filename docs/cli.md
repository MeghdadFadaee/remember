# Remember backup CLI

`remember` manages the recorder's existing backups over **SFTP** or in a local folder. Pass the destination directly, like `scp`; no Remember config file, account, database, server process, or phone app is needed. It works with the SFTP-only server account described in [backup setup](backup.md).

## Install

Python 3.10+ is required. From this repository:

```sh
pipx install .
remember --help
```

Alternatively use a Python virtual environment:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install .
.venv/bin/remember --help
```

On Windows use `.venv\Scripts\python` and `.venv\Scripts\remember`. `python -m remember_cli` is equivalent to `remember`. The checkout launcher `tools/remember` also works when its Python environment has the dependencies installed. The CLI is separate from the watch APK.

## Connect without configuration

```sh
remember ls backup@backup.example:/srv/remember
remember ls backup@backup.example:/srv/remember -P 2222 -i ~/.ssh/id_ed25519
remember ls 'sftp://backup@backup.example:2222/srv/remember'
remember ls 'backup@[2001:db8::10]:/srv/remember'
remember ls ./downloaded-backups
```

Quote the entire source if its path contains spaces. Relative remote paths and `~/...` are relative to the SFTP account's home; absolute paths are as seen inside its chroot. The root directory must already exist.

Authorize **your computer's SSH public key** for the same backup account, or use `--password` for an interactive password prompt if your server permits it. The watch's private key stays on the watch and is not needed. SSH-agent identities and normal private-key files are supported. Load encrypted keys with `ssh-add` first, or use `-i KEYFILE --ask-passphrase`.

Standard OpenSSH `known_hosts` is honored. An unknown host requires explicit interactive approval of its SHA-256 fingerprint **before authentication**. For scripts, supply an independently verified fingerprint:

```sh
remember ls backup@backup.example:/srv/remember --host-key 'SHA256:VERIFIED_FINGERPRINT'
```

There is no insecure/skip-verification option. Changed known host keys are rejected. `--known-hosts FILE` selects a trust file; this is optional SSH trust storage, not application configuration. An explicit fingerprint is a per-command pin and is not saved. If the server offers several host-key types, ensure the pin matches the negotiated key; your watch may pin a different type.

SSH aliases can reuse `HostName`, `User`, `Port`, `IdentityFile`, and `ProxyCommand` from `~/.ssh/config` when present. This is not a complete OpenSSH client: `ProxyJump`, certificates, hardware-token providers, arbitrary `ssh -o` settings, and every SSH-config directive are not implemented. A reachable alias or trusted local `ProxyCommand` can supply a jump connection. No remote shell commands are executed. `--timeout SECONDS` bounds connection and socket waits.

## Find and inspect recordings

```sh
remember ls backup@backup.example:/srv/remember
remember ls backup@backup.example:/srv/remember --search meeting --marked
remember ls backup@backup.example:/srv/remember --since 2026-10-01 --until 2026-11-01
remember ls backup@backup.example:/srv/remember --sort duration --reverse --limit 10
remember info backup@backup.example:/srv/remember latest
remember marks backup@backup.example:/srv/remember 12ab34cd
remember stats backup@backup.example:/srv/remember
remember ls backup@backup.example:/srv/remember --json
```

Use full IDs, unambiguous ID prefixes (at least four characters), or `latest`. Displayed dates are UTC. `--until` is exclusive; a date denotes midnight UTC. Filters also work with `stats` and `sync`. Human output escapes terminal control characters from server filenames and titles. `--json` gives clean JSON on stdout; diagnostics/progress go to stderr. Commands exit `0` on success, `1` on failed/incomplete operations, `2` on argument errors, and `130` on interruption.

`ls` shows only completed version-1 manifests with regular audio of the declared size. Listing does **not** read every audio checksum. Corrupt/unsupported manifests, linked files, missing audio, and incomplete uploads produce warnings; use `doctor` to inspect them. It is not limited to the watch browser's 1,000 manifests.

## Download and sync

```sh
remember get backup@backup.example:/srv/remember latest -o ./recordings
remember get backup@backup.example:/srv/remember 12ab34cd 56ef78ab -o ./recordings
remember sync backup@backup.example:/srv/remember ./recordings --dry-run
remember sync backup@backup.example:/srv/remember ./recordings
remember sync backup@backup.example:/srv/remember ./meetings --search meeting --marked
```

Downloads retain `<id>.m4a` and `<id>.json`, including titles and millisecond bookmark timestamps. Stable IDs keep files compatible with the watch backup format and avoid unsafe title-derived paths. `info` and `marks` make the metadata readable.

Each file is streamed through a bounded buffer and verified against its manifest's size and SHA-256 before publication. Audio is replaced atomically; metadata follows. Interrupted downloads keep hidden `.part` files and their snapshot receipts for the next run. Corrupt resumed prefixes restart once from zero. Use `--no-resume` to restart explicitly. A small `.remember-lock` remains in the destination; its OS lock is automatically released after crashes.

`sync` hashes existing local audio before skipping it and refreshes metadata. It **never deletes local recordings**, including when backups were removed remotely. Differing local audio is preserved unless you supply `--force`; even then a failed download preserves the previous audio. Metadata mirrors the selected server snapshot. Free space for both the existing and replacement audio is needed when replacing a file. No restore/import into the watch's private library is provided.

## Verify and diagnose

```sh
remember verify backup@backup.example:/srv/remember
remember verify backup@backup.example:/srv/remember latest 12ab34cd --json
remember doctor backup@backup.example:/srv/remember
```

Verification streams the audio to your computer without storing it; budget download bandwidth. `doctor` reports incomplete uploads, orphaned audio, invalid manifests, leftover CLI metadata writes, and partial/damaged trash. It never deletes or repairs files automatically. `.upload` files may belong to an active watch transfer; leave them alone until that transfer finishes.

## Listen

```sh
remember play backup@backup.example:/srv/remember latest
remember play backup@backup.example:/srv/remember 12ab34cd --player 'mpv --no-video'
```

The complete audio is downloaded and verified into a private temporary directory before playback. It is removed after the player exits. The default player is the first available of `ffplay`, `mpv`, or macOS `afplay`. A custom player command must block until playback finishes; shell operators are not interpreted. Audio is not streamed before verification and is never sent to a third-party service.

## Rename, trash, restore, and purge

Pause watch uploads while editing backups. There is no server transaction or shared lock with the watch: concurrent uploads can change files, recreate a trashed recording, or replace a CLI-edited title. The tool checks snapshots before changes and refuses detected conflicts, but this cannot eliminate every race with another client. Changes affect **server copies only**, not the watch's local titles, recordings, or backup receipts.

```sh
remember rename backup@backup.example:/srv/remember 12ab34cd 'Interview with Alex' --dry-run
remember rename backup@backup.example:/srv/remember 12ab34cd 'Interview with Alex'
remember rm backup@backup.example:/srv/remember 12ab34cd --dry-run
remember rm backup@backup.example:/srv/remember 12ab34cd
remember trash backup@backup.example:/srv/remember
remember restore backup@backup.example:/srv/remember TRASH_ID_PREFIX
remember purge backup@backup.example:/srv/remember TRASH_ID_PREFIX --dry-run
remember purge backup@backup.example:/srv/remember TRASH_ID_PREFIX --yes
```

`rename` changes only the title (1–200 characters) in metadata and preserves IDs, checksums, bookmarks, and unknown fields. Remote title replacement requires the SFTP server's `posix-rename@openssh.com` extension. If atomic replacement is unavailable, the tool fails without deleting the original manifest.

`rm` asks for confirmation and **moves** the selected pair into `.remember-trash/<trash-id>/` on the same server. It does not download/copy the audio and does not free disk space yet. `--yes` approves the explicit selection for scripts; there is no implicit all/wildcard removal. Completion metadata is hidden before moving audio. A small entry record makes interrupted two-file moves visible in `trash` and `doctor`.

`restore` verifies quarantined audio, moves audio first and metadata last, and refuses to overwrite existing backups. Interrupted moves can be resumed using the same trash ID when their matching pieces remain available. `purge` permanently removes only the selected trash entry's validated files and requires confirmation or `--yes`; it cannot be undone. Partial/damaged entries with unknown or linked files require manual inspection. Trash is not automatically expired. Linked backup files and trash entries are refused; an explicitly chosen local root/destination resolves to its target.

These operations also work on local backup directories. A watch re-upload after CLI deletion may recreate the recording; deleting its server copy does not mark/delete the watch's local file.

## Development and validation

```sh
python -m pip install -e . build
python -m unittest discover -s tests/cli -v
python -m build
```

Unit tests use temporary local recordings. The network integration test starts the included loopback-only disposable SFTP fixture, generates test identities, and removes its files afterwards. It never connects to a personal backup server. The Android APK does not include Python or Paramiko. Windows behavior and physical server resource consumption still require validation on the intended systems.
