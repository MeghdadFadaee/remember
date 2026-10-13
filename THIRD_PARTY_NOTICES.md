# Third-party notices

Remember's source code and original vector artwork are covered by [LICENSE](LICENSE). Third-party tools and libraries retain their own licenses.

## Gradle wrapper

The checked-in `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are generated from [Gradle 9.4.1](https://github.com/gradle/gradle/tree/v9.4.1) and distributed under the Apache License, Version 2.0. The generated script copyright and license headers are retained, and the JAR includes `META-INF/LICENSE`. A copy of the license is available in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

The wrapper configuration pins the Gradle distribution and its official SHA-256 checksum. The included JAR checksum also matches Gradle's published checksum. These generated wrapper files are not relicensed under MIT.

## Resolved dependencies and platform resources

Android APIs, Kotlin runtime libraries, the Android Gradle Plugin, and test libraries are obtained through the Android SDK or Gradle rather than copied as source into this repository. Their own license notices apply. Dependency coordinates and versions are listed in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

The app uses Android's installed system fonts. No Samsung font, watch-face asset, or wallpaper is bundled. The Spatial Number appearance is referenced as design inspiration; the numeral paths and other app artwork are drawn in project code. Screenshots in `docs/` show the app running in an emulator.
