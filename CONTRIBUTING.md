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
