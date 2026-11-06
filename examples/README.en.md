# Original five-type question sample

[简体中文](README.md) | **English**

This small, original sample covers all five question types and can be used to check import, parsing, practice, and backup. It contains no excerpts from private question banks. **The PDF and expected answer strings are in Chinese.**

## Files

- `offlinequiz-example.pdf`: one-page A4 PDF with a text layer and all five question types.
- `MakeExamplePdf.java`: PDFBox 2.0.27 generator; includes question text, answers, and underline positions.
- `prepare-example-font.py`: removes compatibility-range cmap mappings from a local Noto Serif SC font so PDFBox does not emit Kangxi radicals such as `U+2F00` instead of `U+4E00`.
- `generate-example-pdf.ps1`: generates the PDF using the JDK in `work/` and PDFBox in `tools/pdf-probe/lib`.

## Expected parsing result

Recorded output from parsing `offlinequiz-example.pdf` with `tools/pdf-probe/run.ps1` (2026-11-03):

```text
questions=7
byType={single_choice=1, multiple_choice=1, true_false=1, fill_blank=3, short_answer=1}
underlines={total=3, matched=3, unmatched=0, crossLineBlanks=1, crossLineMerges=1}
withReview=1
```

| No. | Type | Expected answer | Notes |
| --- | --- | --- | --- |
| 1 | Single choice | A | Boiling point of water at standard atmospheric pressure |
| 2 | Multiple choice | A, C | Renewable energy; the parenthesized answer is `AC` |
| 3 | True/false | √ | Earth orbits the Sun |
| 4 | Fill-in | 火药 / 指南针 / 印刷术 | One underlined region contains three items separated by `、`, producing three study blanks |
| 5 | Fill-in | 阳光 | The underline crosses a line boundary: `阳` ends one line and `光` starts the next; one blank, two segments |
| 6 | Fill-in | Missing | Deliberately has no underline; review reasons: `no_learning_blanks`, `no_underline_answers` |
| 7 | Short answer | 制造有机物，释放氧气，并参与碳氧平衡。 | Separated by the “参考答案” marker |

There are seven questions: one single choice, one multiple choice, one true/false, three fill-in, and one short answer. Question 6 needs correction and cannot be practiced until fixed and confirmed.

For the editing check, choose “添加一个空” (Add a blank), replace its entire stem with `校对测试：今天学习____。` (one placeholder), set the first blank's answer to `离线刷题`, then save and confirm. The sequential practice order is then 1→2→3→4→5→6→7.

Question 4 has one original blank and three study blanks. Question 5 has one original blank and one study blank, with `crossLine=true`.

## Regenerating the PDF

Requires the JDK at `work/jdk-21.0.13+9` and PDFBox/FontBox/commons-logging under `tools/pdf-probe/lib` (prepared by `tools/pdf-probe/run-tests.ps1`). Fonts are reused from the local machine, not downloaded or committed.

The default path uses Python 3 and fontTools to derive a working font. An explicit `-FontPath` with a standard Unicode cmap avoids that fontTools step.

```powershell
# Uses C:\Windows\Fonts\NotoSerifSC-VF.ttf by default; derives a copy in work/
powershell.exe -NoProfile -ExecutionPolicy Bypass -File examples/generate-example-pdf.ps1

# Or supply a local CJK font explicitly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File examples/generate-example-pdf.ps1 `
  -FontPath "C:\Windows\Fonts\simhei.ttf"
```

- The original local font is Noto Serif SC, licensed under SIL OFL. The PDF embeds only the used subset, not the full font file.
- Preserve [the OFL text](../docs/licenses/Noto-Serif-OFL-1.1.txt) and [source-font copyright/license metadata](../docs/licenses/Noto-Serif-SC-font-metadata.txt) when distributing the sample. See [third-party notices](../THIRD_PARTY_NOTICES.en.md).
- Use `-FontPath` for another local CJK TTF/OTF, or `-SourceFont` to change the default source font.

Reparse the generated PDF rather than checking only its appearance:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile examples/offlinequiz-example.pdf `
  -OutDir work/04a/parse -RenderPages "1"
```

Parsing and rendering outputs go to `work/`.
