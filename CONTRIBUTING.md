# Contributing

Remember is an experimental Wear OS recorder. Focused fixes and improvements are welcome.

## Getting started

Follow the JDK and Android SDK setup in [README.md](README.md). Keep changes focused, explain the behavior they change, and include relevant validation in your pull request.

Before opening a pull request, run:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Recording-service changes also need the device tests. Use a disposable emulator: the tests request microphone permission, create audio files, and temporarily change app preferences. Some tests record for a real minute. Never run them while a personal recording is active.

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Design and behavior

- Keep controls comfortable on a round watch screen and reachable by touch and rotary input.
- Maintain spoken descriptions for custom visuals and native alternatives to edge gestures.
- Keep microphone activity visible to the user. Recording should begin with an explicit action.
- Preserve saved audio and bookmarks when changing recording or storage behavior.
- Respect disabled system animations and battery-saving mode.
- SFTP backup and remote playback are opt-in network features. Preserve fingerprint verification, encrypted key storage, cancellation, checksums, local retention, and charging/network preferences. Test against disposable server folders and recordings.
- Do not introduce app accounts, additional network services, or analytics without discussing the change first.

Do not include local IDE/device configuration, credentials, signing keys, build output, or personal audio in a pull request. Avoid attaching private recordings or identifying information to issues. Contributions are covered by the repository's [MIT license](LICENSE).

## Optional desktop CLI

The Python package in `tools/remember_cli` is independent of the watch APK. With Python 3.10+, use an isolated environment, install with `python -m pip install -e . build`, and run `python -m unittest discover -s tests/cli -v`. The network tests start only the included loopback SFTP fixture with temporary files and generated keys. Never point tests at personal backup servers. Build distributables with `python -m build`. CLI-only changes do not require an Android rebuild; preserve the watch manifest schema, SHA-256 checks, SSH host verification, bounded transfers, non-destructive sync, and confirmation/trash behavior. Read [docs/cli.md](docs/cli.md) before changing mutations or partial-transfer recovery.
