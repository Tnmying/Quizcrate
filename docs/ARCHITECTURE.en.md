# Architecture and data design

[简体中文](ARCHITECTURE.md) | **English**

Quizcrate separates PDF parsing, question models, practice grading, persistence, and the UI to keep the offline app maintainable and extensible.

## PDF parsing

PdfBox-Android reads text-based PDFs. Questions are identified using question numbers, options, answers in parentheses, true/false symbols, underlines, and reference-answer markers. There is no OCR or online AI; uncertain content is flagged for correction.

Shared rules live in `tools/pdf-probe/src/pdfprobe/Model.java` and `QuestionParser.java`. Android builds copy these sources through `stageCoreSources`; generated copies are not maintained separately.

Fill-in answers are detected from separate underlines. Cross-line regions are merged only when supported by clear evidence, then split into study blanks only at the Chinese enumeration comma `、`.

## Practice and grading

Each session stores a fixed question snapshot. Later edits to a bank do not rewrite existing sessions or history. Answer submission is idempotent, with saved drafts and protection against leaving during a write.

- Single choice and true/false use the reference answer; multiple choice requires an exact set match.
- Fill-in answers match acceptable alternatives with conservative whitespace and full-width/half-width normalization.
- Short answers provide character 2/3-gram Dice similarity only. It is not semantic grading or an automatic correctness threshold.
- Skipping does not affect answered-question totals, correctness counts, or accuracy. Peeking is recorded as incorrect.
- Single-question mistake review uses the `single_review` source. It can coexist with a regular or batch-mistake session and does not add a full-bank completion.

## Local storage

Room stores data in `offlinequiz.db`, schema version 3. Explicit migrations preserve question banks and history; there is no destructive fallback.

PDFs selected through the system picker are copied to private app storage. The app does not request INTERNET, broad storage access, or camera permissions.

## Backup and restore

Backup format version 1 uses ZIP + JSON for banks, questions, full sessions, answers, drafts, fixed snapshots, and available source PDFs. Backups are not encrypted and do not directly export the Room database.

Restore first validates the archive and shows a preview. After confirmation, it generates new IDs and imports a separate copy in a transaction without overwriting existing banks. PDF staging is coordinated with database commit, and a failed import rolls back its changes.

The current app accepts older backups. New backups containing `single_review` are not guaranteed to work in version 27 or earlier. Backup format versions and database schema versions are maintained separately.
