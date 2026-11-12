# Quizcrate（题匣）

[简体中文](README.md) | **English**

Quizcrate turns study PDFs into a question bank you can take with you. Import a **text-layer PDF, TXT file, or pasted text with reference answers** to review the extracted questions, practice, revisit mistakes, and keep your learning history offline on your Android phone.

No account, server, online AI, or runtime model is required. The app does not request network permission. Requires **Android 10 (API 29) or later**.

The project documentation is available in Chinese and English. **The app interface and bundled sample PDF are currently in Chinese.** The documentation language switch does not change the app's language.

## Current status

The release-signed **v0.3.0 · Mixed import and recognition improvements** preview is available on GitHub. It adds TXT/paste import, automatic mixed question types, scoped answer tables, and fill-answer alternatives. See the [release comparison](docs/releases/0.3.0.md) and [format guide](docs/IMPORT_FORMATS.en.md).

**[Download the Android APK](https://github.com/Tnmying/Quizcrate/releases/download/v0.3.0/Quizcrate-0.3.0.apk)** · [Release notes](https://github.com/Tnmying/Quizcrate/releases/tag/v0.3.0)

Download and open the APK on your Android phone, then follow the installation prompts. Requires Android 10 or later.

**v0.2.0** adds a blue-and-white interface, a glass question-box icon, practice resumption from the home screen, an overview of learning history, previous-question review, and question search. An in-place upgrade from the signed v0.1.0 APK on Android 16 preserved the sample question bank and completed practice records.

New feature development is on hold after this release. Supported inputs and limitations are listed below.

## Features

- **Text question banks**: import PDF, TXT, or pasted text with automatic mixed-type detection; preview, name, and save multiple banks. Supports single choice, multiple choice, true/false, fill-in-the-blank, and short-answer questions.
- **Corrections when needed**: practice questions directly when no issues are detected. Missing or ambiguous answers are flagged for review. Edit questions, options, answers, and blank positions, and view the source PDF page or original text.
- **Sequential and random practice**: save progress and drafts. Corrections apply to new sessions; existing sessions and history retain their original question snapshots.
- **Skip or peek**: skipping moves to the next question without affecting answered-question totals or accuracy. Peeking at an answer is recorded as incorrect and included in mistake review.
- **Grading and self-assessment**: single choice and true/false use the reference answer; multiple choice requires an exact option match. Fill-in-the-blank supports acceptable alternatives. Fill-in-the-blank and short answers also support answering mentally and then marking an answer as remembered or not remembered. Short-answer text similarity is only a comparison aid, not automatic grading.
- **Mistake review**: filter pending review or all historical mistakes, retry a batch, or tap a card to practice just one question. Single-question review preserves the existing full session and draft. A correct answer or a remembered self-assessment updates review status without deleting past mistakes.
- **Statistics**: correct/remembered and incorrect/not remembered are grouped. Answered-question totals and completed regular rounds are separate. Finishing a regular round counts as one pass even if some questions were skipped; single-question review does not count as a full-bank pass.
- **Navigation for large banks**: search by question or option text, drag the side slider, and return from editing without losing the list position.
- **Local backup and restore**: export banks, practice history, drafts, and available source PDFs/text. Preview a backup before restoring it as a separate copy, without overwriting existing banks.

## Supported files and question formats

| Input | Supported content |
| --- | --- |
| PDF (`.pdf`) | Extractable text layer; single-column layouts recommended |
| TXT (`.txt`) | UTF-8, BOM-marked UTF-16; manual GB18030 in advanced options |
| Pasted text | Existing questions and reference answers, no file required |

A document can mix **single choice, multiple choice, true/false, fill-in-the-blank, and short answer**. Detection is automatic; no bank-wide type selection is required. Number questions, place options on separate lines, and label answers with `答案：` or `正确答案：`. Type labels are optional. Per-question answers and numbered answer tables are supported. Use `\` between alternatives for one blank and `||` between different blanks.

TXT/pasted text is limited to **512 KiB and 20,000 lines**, including a decoded UTF-8 size check. Material must contain existing questions and reference answers; the app does not generate questions from textbooks.

**Not supported:** scanned/encrypted PDFs, photos, image/formula questions, OCR, or direct Word (`.doc/.docx`), Excel (`.xls/.xlsx`), CSV, and PPT import. Existing text questions can be rearranged as TXT. Complex columns/tables may need correction.

See the [full guide and examples](docs/IMPORT_FORMATS.en.md) or use the [five-question template](examples/offlinequiz-import-template.txt).

## Getting started

1. Prepare a text-layer PDF, TXT, or text to paste with reference answers; try the [five-question template](examples/offlinequiz-import-template.txt), or try the original [seven-question sample](examples/offlinequiz-example.pdf).
2. Open import from the Study tab, choose a file or paste text, review the result, and save it with a name.
3. Correct missing or uncertain answers flagged as needing attention. Other questions can be practiced directly.
4. Start sequential or random practice, then check statistics and mistakes. Tap a mistake card for single-question review.
5. Open “备份与恢复” (Backup and restore) under “我的” (Profile) to save your data to a location you choose. **Backups are not encrypted** and should be stored carefully.

The sample has seven questions, including one deliberately missing an answer to demonstrate editing. Its answers and expected parsing output are in the [sample guide](examples/README.en.md).

## Import and data limitations

- Requires a PDF text layer. There is no OCR support; scanned PDFs, image questions, and formula questions are not supported. Complex layouts may need manual correction, and arbitrary PDFs are not guaranteed to parse correctly.
- Parsing relies on layout markers such as question numbers, options, answers in parentheses, true/false symbols, underlines, and the Chinese “参考答案” reference-answer marker. Uncertain content is flagged, not invented. An English README does not imply verified support for arbitrary English PDF layouts.
- Fill-in questions support PDF underlines and explicit blanks. Use `\` between alternatives for one blank and `||` between different blanks; see the [format guide](docs/IMPORT_FORMATS.en.md).
- Short-answer text similarity measures character 2/3-gram overlap. It does not understand meaning and is not an accuracy score.
- Selected PDFs are copied to private app storage; the original file is not deleted. There is no account synchronization, so export backups yourself.
- Backups are unencrypted ZIP files. A failing document provider may leave a partial export; the app reports the failure.
- Database schema version is 4; backup format version is 2. Format-1 backups remain readable. New backups include text sources and cannot be read by v0.2.0 or older readers. Back up before upgrading; do not uninstall the official app. Matching package/signature APKs support in-place upgrades; test builds have separate data.
- Source code and installation packages are provided separately. Download release-signed APKs from [GitHub Releases](https://github.com/Tnmying/Quizcrate/releases).

## Building from source

Download the [v0.3.0 source snapshot](https://github.com/Tnmying/Quizcrate/archive/faa73d0b193d56c58e377edbe8b37770077d6b9a.zip). The release’s `v0.3.0` tag still points to the older commit used when uploading the APK, so its automatic source archives do not yet match this version. Use this snapshot or the current `main` branch.

Application source is updated to v0.3.0 (version code 31). Release APKs use the maintainer’s private signing key; a locally built debug APK cannot replace the release-signed app in place.

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

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. If `android/local.properties` exists, make sure its SDK path matches your setup. More details and device-test commands are in the [Android development guide](android/README.en.md).

The parser's regression tests use synthetic questions and can run on their own. The script downloads and verifies PDFBox dependencies on its first run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 -Jdk $env:JAVA_HOME
```

See the [parser guide](tools/pdf-probe/README.en.md) for test prerequisites and font-related skips.

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
```

[Architecture and data design](docs/ARCHITECTURE.en.md) covers the technical choices. [Development acknowledgments](ACKNOWLEDGMENTS.en.md) lists the tools used during development.
