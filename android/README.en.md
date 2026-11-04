# Android development guide

[简体中文](README.md) | **English**

Quizcrate uses Kotlin, Jetpack Compose, and Room. The application ID is `com.offlinequiz.app`; minimum Android version is 10 (API 29), with compileSdk/targetSdk 36.

Current version: **28 / `0.4m-single-review`**. Manual functional checks on a physical phone have passed with no issues found so far. All 295 JVM tests and the debug build have passed. See the [project README](../README.en.md) for features and usage. The app interface is currently in Chinese.

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

## Tests

- `app/src/test/`: JVM logic, services, controllers, and session tests.
- `app/src/androidTest/`: device automation for Room, migrations, transactions, backups, and PDF storage.
- `tools/pdf-probe/run-tests.ps1`: synthetic parser regression tests; private-sample checks are skipped when the sample is absent.

Manual phone checks have passed. A separate set of 28 device automation tests has been compiled but has not yet been run on a device.

With a test device connected:

```powershell
& ./android/gradlew.bat --project-dir ./android :app:connectedDebugAndroidTest
```

## Architecture and licenses

See [architecture and data design](../docs/ARCHITECTURE.en.md) for module boundaries, compatibility, and grading rules, and [third-party notices](../THIRD_PARTY_NOTICES.en.md) for dependencies and resources.

The current packaging configuration excludes some `META-INF/LICENSE/NOTICE` files. The final dependency set and notices included with the APK need to be checked before distribution.
