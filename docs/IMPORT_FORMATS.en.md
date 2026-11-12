# Question import formats (v0.3.0)

[简体中文](IMPORT_FORMATS.md)

Supports **text-layer PDFs, TXT files, and pasted text** containing existing questions and reference answers. The app does not generate questions from textbooks or invent missing answers. Use the [five-question template](../examples/offlinequiz-import-template.txt); parser labels and the app UI are currently Chinese.

A document can mix single choice, multiple choice, true/false, fill-in-the-blank and short answer questions. Automatic detection is the default. Number each question, put options on separate lines, and label reference answers. Type labels such as `[单选题]` are optional but help disambiguation. See the [Chinese guide](IMPORT_FORMATS.md) for all five annotated examples.

| Content | Recommended format |
| --- | --- |
| Question number | `1.`, `1、`, `（1）`, or `第1题`; globally unique numbers are safest |
| Options | `A.`, `B.`, etc.; answers use one letter or multiple letters such as `AC` |
| True/false | Reference answers `对 / 错` or `√ / ×` |
| Blanks | `____` or empty `()`; separate answers to different blanks with `\|\|`; numbered `(1)`, `(2)` answers are also supported |
| Alternative answers | Separate alternatives for the same blank with `\`, e.g. `7\七`; either is accepted |
| Reference answer | Label with `正确答案：`, `答案：`, or `参考答案：`; personal-answer records are excluded |

Underlined answers in existing PDFs can also be recognized. For material you prepare yourself, explicit blanks and separate answer lines are recommended. Short answers are self-assessed; text similarity is only an aid, not semantic grading.

An answer table headed `答案表` can contain several numbered answers on one line, e.g. `1.B 2.AC 3.对`. If numbering restarts by chapter or question type, repeat the matching chapter/type heading in the answer table. Ambiguous mappings are flagged for review. See the [sectioned example](../examples/offlinequiz-section-answers-example.txt).

Prefer a single column and clear reading order. Complex tables, columns and missing question numbers may need correction. TXT defaults to UTF-8, supports BOM-marked UTF-16, and offers manual GB18030 selection in advanced options. TXT/pasted text is limited to 512 KiB and 20,000 lines; decoded UTF-8 content is also subject to the size limit.

No OCR, scanned/encrypted PDFs, photos, image or formula questions, or direct Word/Excel/CSV/PPT import. Existing text questions from these documents can be rearranged as TXT or pasted text.

Check the preview's question count, types, options and answers. Uncertain questions remain pending and can be edited after saving, with their source PDF/text available for reference. Processing is offline and original files are unchanged. Backups are unencrypted.
