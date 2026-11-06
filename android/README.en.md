# Android development guide

[简体中文](README.md) | **English**

Quizcrate uses Kotlin, Jetpack Compose, and Room. The application ID is `com.offlinequiz.app`; minimum Android version is 10 (API 29), with compileSdk/targetSdk 36.

The current public release is **v0.1.0** (version code 29). The project README covers features and usage. [Release-signed APK and release notes](https://github.com/Tnmying/Quizcrate/releases/tag/v0.1.0) · [Project README](../README.en.md)

## Build environment

- JDK 21
- Android SDK platform 36 / build-tools 36.0.0
- Gradle 8.13 wrapper and AGP 8.13.2
- Kotlin 2.2.21 and KSP 2.2.21-2.0.5

The first build requires network access for dependencies. Once cached, add `--offline` if needed. The app runs without network access.

Run from the repository root in PowerShell, replacing the paths with your local JDK and SDK:

```powershell
$env:JAVA_HOME = 'Absolute path to your JDK 21'
$env:ANDROID_HOME = 'Absolute path to your Android SDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $PWD 'work/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $PWD 'work/android-user-home'
& ./android/gradlew.bat --project-dir ./android --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. An existing `android/local.properties` must point to the same SDK.

`tools/android-build.ps1` supports dependencies stored inside the project and copies the APK to `outputs/apk/`. Pass a new `-ApkName` explicitly to avoid overwriting an existing build.

## Release builds and signing

`tools/android-release.ps1` runs release unit tests, builds the APK, runs Lint, aligns the ZIP, signs it, and verifies the signature. It outputs an APK and SHA-256 file. JDK/SDK defaults point into the project's `work/` directory; override them with `-Jdk` and `-Sdk`.

Prepare signing material first. Defaults are `quizcrate-release.p12` and `keystore-password.txt` in the ignored local `local-inputs/signing/` directory, with alias `quizcrate-release`. Override these with `-Keystore`, `-PasswordFile`, and `-KeyAlias`. Back up the private key and password securely; do not distribute them with source code or APKs.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/android-release.ps1
```

Use `-Online` to fetch missing Gradle/Maven check dependencies, or `-RerunTasks` for a forced rebuild. Outputs go to `outputs/releases/<version>/`. The script stops if the final APK already exists, avoiding an overwrite.

Old debug builds and the release APK use different signing certificates. Export a backup before switching installations. Future release updates must continue using the same release key and increasing internal version codes.

## Tests

- `app/src/test/`: JVM logic, services, controllers, and session tests.
- `app/src/androidTest/`: device automation for Room, migrations, transactions, backups, and PDF storage.
- `tools/pdf-probe/run-tests.ps1`: synthetic parser regression tests; private-sample checks are skipped when the sample is absent.

For the blue-and-white v0.2.0 update (version code 30), all 304 JVM tests and 28 instrumentation tests passed. Instrumentation ran on Android 16. Release Lint reported 0 errors, 15 warnings, and 2 hints. An in-place upgrade using the project sample preserved the questions, their review status, and completed practice records.

With a test device connected:

```powershell
& ./android/gradlew.bat --project-dir ./android :app:connectedDebugAndroidTest
```

## Architecture and licenses

See [architecture and data design](../docs/ARCHITECTURE.en.md) for module boundaries, compatibility, and grading rules, and [third-party notices](../THIRD_PARTY_NOTICES.en.md) for dependencies and resources.

Some duplicate `META-INF/LICENSE/NOTICE` files are excluded by the packaging configuration; dependency and resource license texts and copyright notices are retained under `assets/third_party/` in the APK.
