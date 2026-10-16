# Third-party notices

Remember's source code and original vector artwork are covered by [LICENSE](LICENSE). Third-party tools and libraries retain their own licenses.

## Gradle wrapper

The checked-in `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are generated from [Gradle 9.4.1](https://github.com/gradle/gradle/tree/v9.4.1) and distributed under the Apache License, Version 2.0. The generated script copyright and license headers are retained, and the JAR includes `META-INF/LICENSE`. A copy of the license is available in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

The wrapper configuration pins the Gradle distribution and its official SHA-256 checksum. The included JAR checksum also matches Gradle's published checksum. These generated wrapper files are not relicensed under MIT.

## JSch SSH/SFTP library

The app includes [mwiede/JSch 2.28.8](https://github.com/mwiede/jsch/tree/jsch-2.28.8), a maintained fork of JCraft's JSch, under its BSD-style license. Its copyright, conditions, and disclaimer are retained in [licenses/JSch.txt](licenses/JSch.txt) and in the app's bundled license assets. JSch is not relicensed under MIT.

## Bouncy Castle cryptography

[Bouncy Castle bcprov-jdk18on 1.86](https://www.bouncycastle.org/) provides Android-compatible Ed25519 key generation and signatures through JSch's BC backend. It is distributed under the Bouncy Castle license (MIT-style), retained in [licenses/Bouncy-Castle.txt](licenses/Bouncy-Castle.txt) and bundled in the app's license assets. Android's installed security providers are not replaced.

## ZXing QR encoding

[ZXing Core 3.5.4](https://github.com/zxing/zxing/tree/zxing-3.5.4) generates public-key QR codes locally. It is distributed under Apache License 2.0, retained in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) and bundled in the app. The upstream [NOTICE](licenses/ZXing-NOTICE.txt) is retained in the repository and app license assets. Only the core library is included; no camera/scanner UI or online QR service is used.

## AndroidX Tiles and concurrency

The recording tile uses AndroidX Wear Tiles 1.6.2, ProtoLayout/ProtoLayout Expression 1.4.2, Concurrent Futures 1.3.0, and Annotation Experimental 1.6.0 (API opt-in markers for native tile transitions), distributed under the Apache License, Version 2.0. The transitive Guava `listenablefuture` API is also Apache-2.0 licensed. A copy of the license is retained in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) and bundled in the app's license assets. These libraries and their notices retain their upstream licenses; they are not relicensed under MIT. The tile renderer dependency is used only in device tests.

## Resolved dependencies and platform resources

Android APIs, Kotlin runtime libraries, the Android Gradle Plugin, and test libraries are obtained through the Android SDK or Gradle rather than copied as source into this repository. Their own license notices apply. Dependency coordinates and versions are listed in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

The app uses Android's installed system fonts. No Samsung font, watch-face asset, or wallpaper is bundled. The Spatial Number appearance is referenced as design inspiration; the numeral paths and other app artwork are drawn in project code. Screenshots in `docs/` show the app running in an emulator.

The optional Python backup CLI and SFTP test fixture use separately installed [Paramiko 5.x](https://pypi.org/project/paramiko/5.0.0/) (LGPL-2.1) and its dependencies, including cryptography, under their upstream licenses. These packages are installed by Python packaging tools, not vendored into this repository or bundled in the Android app. The CLI's original source is MIT; this does not relicense its dependencies.
