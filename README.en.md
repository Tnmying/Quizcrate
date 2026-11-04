# Quizcrate（题匣）

[简体中文](README.md) | **English**

Quizcrate is a fully offline Android app that turns study PDFs into a question bank. Import a **text-based PDF with reference answers**, review the extracted questions, practice, revisit mistakes, and keep your learning history on your phone.

No account, server, online AI, or runtime model is required. The app does not request network permission. Requires **Android 10 (API 29) or later**.

The project documentation is available in Chinese and English. **The app interface and bundled sample PDF are currently in Chinese.** The documentation language switch does not change the app's language.

## Current status

Version: **0.1.0**, internal version code **29** (2026-11-04). The first public preview is available as a release-signed APK.

**[Download the Android APK](https://github.com/Tnmying/Quizcrate/releases/download/v0.1.0/Quizcrate-0.1.0.apk)** · [Release notes](https://github.com/Tnmying/Quizcrate/releases/tag/v0.1.0)

Download and open the APK on your Android phone, then follow the installation prompts. Requires Android 10 or later.

Core features passed manual phone checks in the previous test build, with no issues found so far. The 0.1.0 release build and all 295 JVM tests have passed.

## Features

- **PDF question banks**: import, preview, name, and save multiple banks. Supports single choice, multiple choice, true/false, fill-in-the-blank, and short-answer questions.
- **Corrections when needed**: practice questions directly when no issues are detected. Missing or ambiguous answers are flagged for review. Edit questions, options, answers, and blank positions, and view the original PDF page.
- **Sequential and random practice**: save progress and drafts. Corrections apply to new sessions; existing sessions and history retain their original question snapshots.
- **Skip or peek**: skipping moves to the next question without affecting answered-question totals or accuracy. Peeking at an answer is recorded as incorrect and included in mistake review.
- **Grading and self-assessment**: single choice and true/false use the reference answer; multiple choice requires an exact option match. Fill-in-the-blank supports acceptable alternatives. Fill-in-the-blank and short answers also support answering mentally and then marking an answer as remembered or not remembered. Short-answer text similarity is only a comparison aid, not automatic grading.
- **Mistake review**: filter pending review or all historical mistakes, retry a batch, or tap a card to practice just one question. Single-question review preserves the existing full session and draft. A correct answer or a remembered self-assessment updates review status without deleting past mistakes.
- **Statistics**: correct/remembered and incorrect/not remembered are grouped. Answered-question totals and completed regular rounds are separate. Finishing a regular round counts as one pass even if some questions were skipped; single-question review does not count as a full-bank pass.
- **Navigation for large banks**: jump by question number, drag the side slider, and return from editing without losing the list position.
- **Local backup and restore**: export banks, practice history, drafts, and available source PDFs. Preview a backup before restoring it as a separate copy, without overwriting existing banks.

## Getting started

1. Prepare a text-based PDF containing reference answers, or try the original [seven-question sample](examples/offlinequiz-example.pdf).
2. Choose “导入新题库” (Import new question bank) on the home screen, select the PDF, review the result, and save it with a name.
3. Correct missing or uncertain answers flagged as needing attention. Other questions can be practiced directly.
4. Start sequential or random practice, then check statistics and mistakes. Tap a mistake card for single-question review.
5. Use “备份与恢复” (Backup and restore) to save your data to a location you choose. **Backups are not encrypted** and should be stored carefully.

The sample has seven questions, including one deliberately missing an answer to demonstrate editing. Its answers and expected parsing output are in the [sample guide](examples/README.en.md).

## PDF support and data limitations

- Requires a PDF text layer. There is no OCR support; scanned PDFs, image questions, and formula questions are not supported. Complex layouts may need manual correction, and arbitrary PDFs are not guaranteed to parse correctly.
- Parsing relies on layout markers such as question numbers, options, answers in parentheses, true/false symbols, underlines, and the Chinese “参考答案” reference-answer marker. Uncertain content is flagged, not invented. An English README does not imply verified support for arbitrary English PDF layouts.
- Fill-in answers are detected from separate underlines. Cross-line regions are merged only with clear evidence, then split into study blanks only at the Chinese enumeration comma `、`.
- Short-answer text similarity measures character 2/3-gram overlap. It does not understand meaning and is not an accuracy score.
- Selected PDFs are copied to private app storage; the original file is not deleted. There is no account synchronization, so export backups yourself.
- Backups are unencrypted ZIP files. A failing document provider may leave a partial export; the app reports the failure.
- Database schema version is 3; backup format version is 1. The current app accepts older backups. New backups containing single-question review sessions are not guaranteed to work in version 27 or earlier.
- Source code and installation packages are provided separately. Download release-signed APKs from [GitHub Releases](https://github.com/Tnmying/Quizcrate/releases).

## Building from source

Requires **JDK 21**, Android SDK **platform 36 / build-tools 36.0.0**. The repository includes the Gradle **8.13** wrapper and uses AGP **8.13.2** and Kotlin **2.2.21**. The first build needs network access to download Gradle/Maven dependencies; the app itself runs offline.

Run the following in Windows PowerShell from the repository root, replacing the JDK and SDK paths with your own. These variables apply only to the current terminal process:

```powershell
$env:JAVA_HOME = 'Absolute path to your JDK 21'
$env:ANDROID_HOME = 'Absolute path to your Android SDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# Keep build caches inside the project
$env:GRADLE_USER_HOME = Join-Path $PWD 'work/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $PWD 'work/android-user-home'
& ./android/gradlew.bat --project-dir ./android --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. If `android/local.properties` exists, make sure its SDK path matches your setup; it is not included in the repository. More details and device-test commands are in the [Android development guide](android/README.en.md).

The parser's synthetic regression tests can run independently without the private sample. The script downloads and verifies PDFBox dependencies on its first run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 -Jdk $env:JAVA_HOME
```

Without `local-inputs/sample.pdf`, the private-sample checks are skipped. Do not upload personal question banks to run the tests. See the [parser guide](tools/pdf-probe/README.en.md) for test prerequisites and font-related skips.

## License and third-party materials

Original project code and accompanying documentation are available under the [MIT License](LICENSE), with `Copyright (c) 2026 Tnmying`. Use, modification, redistribution, and commercial use are permitted subject to the license notice requirements. The software is provided without warranty; publishing modified source code is not required. See the [MIT overview](https://choosealicense.com/licenses/mit/) for a summary.

Third-party code, fonts, and resources retain their own licenses. The project license does not replace or relicense them.

See [third-party notices](THIRD_PARTY_NOTICES.en.md) and [original license texts](docs/licenses/). License and copyright notices are bundled under `assets/third_party/` in the APK.

## Repository layout

```text
android/           Kotlin + Jetpack Compose + Room app, tests, and schemas
tools/             Build scripts, shared PDF parser, and regression tests
examples/          Original PDF sample, generator, and expected results
docs/              Architecture, icon assets, and third-party licenses
local-inputs/      Private samples, excluded from Git
work/              Local dependencies and temporary files, excluded from Git
outputs/           Local APK builds, excluded from Git
```

See [architecture and data design](docs/ARCHITECTURE.en.md) for technical details and [development acknowledgments](ACKNOWLEDGMENTS.en.md) for assistance used during development.
