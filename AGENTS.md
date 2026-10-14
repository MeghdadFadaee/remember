# Repository Guidelines

## Scope & Project Status

These instructions apply throughout the repository. Remember is an experimental microphone recorder with optional self-hosted SFTP backup for round Wear OS watches, with Galaxy Watch 7 as the primary design target. Physical-watch validation is still pending; successful builds or emulator tests do not establish production or Play Store readiness. Keep changes focused on the requested behavior and preserve unrelated work.

## Project Structure & Module Organization

Remember is a standalone Wear OS audio recorder with one Android module, `app`. Kotlin sources live in `app/src/main/java/meghdad/remember/`: `MainActivity.kt` manages screens, `RecordingService.kt` handles foreground capture, and supporting files implement recordings, limits, storage, and custom visuals. Resources and the manifest live in `app/src/main/res/` and `app/src/main/AndroidManifest.xml`. JVM tests are in `app/src/test/`; device tests are in `app/src/androidTest/`. Screenshots live in `docs/`, dependency versions in `gradle/libs.versions.toml`, and CI in `.github/workflows/android.yml`.

- `Recordings.kt`: private audio files, JSON metadata, recovery, and duration formatting.
- `RecordingLimits.kt`: persisted preferences and minute-limit policy.
- `StorageUsage.kt` / `StorageChartView.kt`: storage accounting and chart rendering.
- `MinuteCounterView.kt` / `SpatialDrawables.kt`: original vector numerals, motion, and dial/edge visuals.

## Build, Test, and Development Commands

Use JDK 21, Android SDK platform 36.1, and Build Tools 36.1.0. Configure the SDK through `ANDROID_HOME` or an untracked `local.properties`. Run commands from the repository root:

Use the included Gradle 9.4.1 wrapper. AGP 9.2.1 supplies built-in Kotlin support; do not add a separate Kotlin Android plugin without an intentional tooling migration. The app supports API 31+, targets API 36, and currently uses Java 11 source/target compatibility despite requiring JDK 21 for the build.

- `./gradlew :app:assembleDebug` — build the debug APK.
- `./gradlew :app:lintDebug :app:testDebugUnitTest` — run Android lint and JVM tests.
- `./gradlew :app:assembleDebugAndroidTest` — compile device tests without running them.
- `./gradlew :app:connectedDebugAndroidTest` — run tests on connected devices.

The combined local check is:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

On Windows, use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. CI verifies the wrapper, runs the combined check, and uploads reports and a debug APK; it does not run device tests. When updating Gradle, regenerate the wrapper and update both its distribution checksum and `gradle/wrapper/gradle-wrapper.jar.sha256`. Keep CI action commits pinned and repository permissions read-only unless the requested change requires otherwise.

For local use, select a Wear OS Large Round emulator in Android Studio and run the `app` configuration.

## Coding Style & Naming Conventions

Follow the configured official Kotlin style with four-space indentation. Use PascalCase for classes and filenames, camelCase for functions and properties, and descriptive behavior names for tests. Keep code in `meghdad.remember` and follow neighboring code patterns. Android lint is the configured static check; no standalone formatter is configured.

The UI uses native Android views and custom drawing, without Compose or a runtime UI framework. Follow this approach for routine changes. Put new user-facing text in string resources where practical. Keep blocking storage/accounting work off the main thread and guard asynchronous UI updates against destroyed activities and stale screen requests.

## Recording & Data Invariants

- Capture begins through an explicit user action with microphone permission. Preserve the microphone foreground service, visible notification with Save action, and wake-lock cleanup on completion or failure.
- Leaving the activity or turning off the screen must not end recording. Playback pauses when the activity leaves the foreground.
- Measure captured duration with a monotonic clock; exclude pauses from duration, bookmark timestamps, and minute limits.
- Snapshot the recording limit at session start. Preference changes apply to the next session; automatic parts retain the current session policy. Disabling the limit preserves its configured length and action. Valid lengths are 1–1,440 minutes.
- At the limit, save and stop or save and start a new part at zero. Preserve the completed part's audio and bookmarks; reset elapsed time and bookmarks for the new part. Keep the foreground service and wake lock alive during rollover. A failed save must stop rollover.
- Preserve the per-clip 500 MB safety cap, which saves and stops the session in either mode. Current encoding is mono AAC/M4A, 44.1 kHz at 128 kbps; intentional format changes need matching documentation and playback tests.
- Audio lives under private `filesDir/recordings`: capture uses `<id>.pending`, finalized audio uses `<id>.m4a`, and metadata uses `<id>.json`. Preserve stable IDs, millisecond timestamps, and metadata compatibility. Write metadata through a temporary file and rename it into place.
- Recovery must skip the active clip and retain interrupted files that cannot yet be recovered. Never erase audio as a migration or error-handling shortcut. Renaming changes metadata; deletion requires user confirmation.

## Watch Design & Storage

- Preserve the dark olive/lime appearance and original rounded vector numeral behind foreground controls. The numeral represents measured whole minutes, with gentle floating motion; the semicircle represents seconds within the minute. Do not add a caption beneath the numeral or labels beside the edge lines without a requested design change.
- Keep the edge lines functional: microphone level/free space/playback state on the left and bookmarks/navigation/seeking on the right. Retain native button and slider alternatives, rotary access, and spoken descriptions for custom visuals.
- Settings entry labels are `Storage` and `Recording limit`. The storage chart fills most of the first viewport; readings and buttons can sit below it and remain reachable by scrolling. Keep the page visually readable at a glance.
- Storage reads use Android's device capacity/available accounting and Remember's own app usage. `dataBytes` already includes cache: do not add `cacheBytes` again. Mark fallback app accounting as approximate. Show proportional chart slices; a small visibility marker must not inflate the app's actual share.
- Preserve five-second storage refresh while that page is open, manual Refresh, and stale-result guards. Do not request usage access to other apps for this feature.
- Stop decorative motion when animations are disabled, battery saver is active, the activity is backgrounded, or capture is paused. Avoid extra background polling or animation work.

## Backup Invariants

- `BackupActivity.kt` owns backup pages; `BackupSettings.kt` stores destination details, encrypted key material, and receipts. `BackupTransport.kt` handles pinned-key SFTP; `BackupWorker.kt` schedules persisted Android jobs and serializes network actions with `BackupGate`.
- Require an explicit server configuration and an approved SHA256 host-key fingerprint. First-time discovery must reject the fetched key before authentication and save nothing until the user approves the approval screen. Block changed identities; explicit Forget trust pauses backups. Never disable host verification, authenticate an unapproved server, or log/export private keys. Encrypt stored SSH private keys using Android Keystore.
- Generate new watch identities as Ed25519 using the Android-compatible BC backend and OpenSSH private-key format. Preserve existing RSA identities until explicit replacement; pause backups and require server authorization of the new public key when replacing one.
- Upload only finalized clips. Preserve local audio, metadata, and bookmarks. Local deletion must not propagate to server backups. Scope receipts to the destination; acknowledge only the snapshot actually uploaded.
- Resume temporary uploads, verify full-file SHA-256, then publish metadata as the completion marker. Do not mark a failed or incomplete upload as backed up. Respect cancellation, socket timeouts, bounded buffers, and one active connection at a time.
- Keep automatic uploads opt-in, constrained to charging and unmetered connections by default. Use Android JobScheduler; scheduling is approximate and must not claim exact-time execution.
- Remote listening uses a verified temporary cache, not streaming. Pause playback outside the foreground and retain the previous cache if download verification fails. Remote filenames and manifests are untrusted: enforce safe IDs, sizes, and bounded metadata reads.
- SSH encrypts transport, not stored server files. Do not present at-rest encryption, restore, or remote deletion controls until implemented.

## Testing Guidelines

Use JUnit 4 for JVM tests and AndroidJUnit4/AndroidX instrumentation for device tests; Espresso is available. Name test classes `*Test` and methods after expected behavior. Cover changed boundaries and recording behavior; no numeric coverage threshold is configured. Recording-service changes require device tests on a disposable microphone-enabled emulator. Ensure only the intended emulator is connected, and never test during a personal recording.

The connected-test task can target every connected device. Verify device identity before testing, installing, clearing data, or stopping the app, and use an explicit serial for device-specific commands. Never wipe or repurpose a user's emulator for testing; create a separate disposable AVD. Check that no recording is active before reinstalling or stopping the app.

Two recording-limit integration tests capture for a real minute. Keep meaningful coverage for pause exclusion, screen-off/background behavior, stop/split boundaries, playable output, bookmarks, and preference persistence when those behaviors change. Compile device tests even when no suitable emulator is available, and report that execution remains unverified. For UI changes, inspect scrolling, round-screen clipping, large multi-digit minutes, rotary input, and accessibility descriptions. Documentation-only edits do not need an Android rebuild.

## Commit & Pull Request Guidelines

Use concise, descriptive commit subjects. Keep PRs focused, explain behavior changes, and report validation. Include round-screen screenshots for UI changes and link relevant issues when applicable. Use disposable recordings for screenshots. Report which checks ran, any failures or skipped device tests, and remaining physical-watch limitations. Do not commit or push unless the user asks; preserve their staging and unrelated changes.

## Privacy & Watch Behavior

Preserve audio and bookmarks, rotary access, spoken descriptions, and animation/battery preferences. Discuss accounts, networking, or analytics before introducing them. Keep personal audio, credentials, signing keys, local configuration, and build output untracked. See `CONTRIBUTING.md` and `PRIVACY.md` for additional guidance.

Keep capture standalone and local. Optional SFTP networking is authorized for user-configured backups and remote playback; automatic backup is off by default. Do not add app accounts, analytics, ads, or other network services without discussing them. Android backups are disabled; uninstalling removes local recordings, SSH identity, and preferences but leaves server copies intact. Do not include private recordings, identifying logs, or machine-specific paths in documentation, issues, or screenshots.

Keep `README.md`, `PRIVACY.md`, and screenshots aligned with changes to behavior, setup, permissions, or limitations. Code and original artwork use MIT; maintain `THIRD_PARTY_NOTICES.md` and relevant notices when adding dependencies or assets. The design is inspired by Samsung's Spatial Number face, but no Samsung font or wallpaper is bundled; use original or appropriately licensed assets.
