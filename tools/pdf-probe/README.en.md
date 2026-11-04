# PDF parser tool

[简体中文](README.md) | **English**

This desktop tool checks text-based PDF parsing and shares question models and parsing rules with the Android app. It uses Apache PDFBox 2.0.27 to reconstruct text from glyph coordinates, extract underlines, and produce question JSON and position data.

## Requirements

JDK 21 and Windows PowerShell. On first run, the script downloads PDFBox, FontBox, and commons-logging from Maven Central and verifies their SHA-256 checksums. Cached dependencies allow offline use.

## Parse the original sample

Run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile examples/offlinequiz-example.pdf `
  -OutDir work/pdf-example -NoRender
```

If JDK detection fails, pass `-Jdk 'Absolute path to your JDK'`. Use `-RenderPages '1'` to render selected pages, or `-NoRender` to disable rendering.

Dependencies are stored in `tools/pdf-probe/lib/`, compiled classes in `tools/pdf-probe/build/`, and results in the specified `work/` directory. These local files are excluded from the repository.

## Regression tests

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

Coverage includes drawing paths, underlines, option markers, cross-line content, numbering, and an original five-type PDF. Failed assertions return a nonzero exit status. Private-sample tests are skipped when the sample is absent; some font-dependent synthetic tests also explicitly skip if a CJK font is unavailable.

## Outputs

- `questions.json`: question types, stems, options, answers, page numbers, and review flags.
- `summary.json`: counts, numbering anomalies, underline statistics, and review reasons.
- `raw/`: reconstructed text, glyph/line coordinates, and the executed command.
- `render/`: previews of selected pages.

Real documents and complete extracted question banks stay local. Public demonstrations use the [original sample](../../examples/README.en.md). See [architecture and data design](../../docs/ARCHITECTURE.en.md) for parsing rules and Android integration.
