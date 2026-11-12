# 自制五题型示例

v0.3.0 可导入[五题模板](offlinequiz-import-template.txt)，混合识别五种题型，第 4 题含两个空、每空两种可接受答案。另有[混合文本](offlinequiz-mixed-example.txt)、[分区答案表](offlinequiz-section-answers-example.txt)示例。详见[导入规范](../docs/IMPORT_FORMATS.md)。

**简体中文** | [English](README.en.md)

这份小型示例覆盖五种题型，可用于检查导入、解析、练习和备份流程，不摘录真实题库。

## 文件

- `offlinequiz-section-answers-example.txt`：4 道自制题，覆盖章节重置编号、局部答案表、全角题号/选项和一行多条答案，使用默认自动识别即可。
- `offlinequiz-mixed-example.txt`：10 道自制混合题，前半带逐题标签、后半无标签；默认自动识别时预期五题型各 2 题，不需要指定整份题库类型。
- `offlinequiz-text-example.txt`：v0.3.0 的 UTF-8 自制示例；5 题、五种题型各 1 题。选择 TXT 或粘贴全文，默认自动识别即可。填空的多空答案以 `||` 分隔。
- `offlinequiz-example.pdf`：1 页、文字层、A4，覆盖五题型。
- `MakeExamplePdf.java`：用 PDFBox 2.0.27 生成 PDF 的源码；题目文本、答案、下划线位置都在这里。
- `prepare-example-font.py`：把本机 Noto Serif SC 的兼容区 cmap 映射去掉，避免 PDFBox 写出 `U+2F00` 等康熙部首而不是 `U+4E00`。
- `generate-example-pdf.ps1`：使用项目 `work/` 内 JDK 与 `tools/pdf-probe/lib` 的 PDFBox 生成并保存 PDF。

## 期望解析结果

用 `tools/pdf-probe/run.ps1` 解析 `offlinequiz-example.pdf` 的实际结果（2026-11-03）：

```text
questions=7
byType={single_choice=1, multiple_choice=1, true_false=1, fill_blank=3, short_answer=1}
underlines={total=3, matched=3, unmatched=0, crossLineBlanks=1, crossLineMerges=1}
withReview=1
```

| 题号 | 类型 | 答案 | 说明 |
| --- | --- | --- | --- |
| 1 | 单选 | A | 水在标准大气压下的沸点 |
| 2 | 多选 | A、C | 可再生能源；答案为括号内 `AC` |
| 3 | 判断 | √ | 地球围绕太阳公转 |
| 4 | 填空 | 火药 / 指南针 / 印刷术 | 一个下划线覆盖带顿号的三项，应用拆成 3 个学习空 |
| 5 | 填空 | 阳光 | **跨行下划线**：`阳` 在行末、`光` 在下一行行首，解析合并为 1 空、2 段 |
| 6 | 填空 | （空） | 故意没有下划线，待校对；原因 `no_learning_blanks`、`no_underline_answers` |
| 7 | 简答 | 制造有机物，释放氧气，并参与碳氧平衡。 | “参考答案”分隔 |

- 总计 7 题：单选 1、多选 1、判断 1、填空 3、简答 1；待校对 1（第 6 题）。
- 第 6 题故意缺少答案，仅用于校对编辑测试，确认前不可练习。先点“添加一个空”，将整个题干改为 `校对测试：今天学习____。`（只有一个占位符），第 1 空答案设为 `离线刷题`，保存并确认；此后最短练习顺序为原顺序 1→2→3→4→5→6→7。
- 第 4 题含一个原始空、3 个学习空；第 5 题含 1 个原始空、1 个学习空，`crossLine=true`。

## 重新生成

前置：项目 `work/` 内已有 JDK 21（`work/jdk-21.0.13+9`）和 `tools/pdf-probe/lib` 下的 PDFBox/FontBox/commons-logging（运行过一次 `tools/pdf-probe/run-tests.ps1` 即可）。生成器使用本机已有的字体。默认路径会调用 Python 3 + `fontTools`（`prepare-example-font.py`）派生工作字体；若直接给出标准 Unicode cmap 的 `-FontPath`，则不需要 fontTools。

```powershell
# 默认使用 C:\Windows\Fonts\NotoSerifSC-VF.ttf，并在 work/ 下派生一个去兼容区 cmap 的副本
powershell.exe -NoProfile -ExecutionPolicy Bypass -File examples/generate-example-pdf.ps1

# 或显式指定本机 CJK 字体（标准 Unicode cmap 的字体可直接用，不再派生）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File examples/generate-example-pdf.ps1 `
  -FontPath "C:\Windows\Fonts\simhei.ttf"
```

- 字体来源：本机 Windows 的 Noto Serif SC（SIL OFL）；生成的 PDF 只嵌入实际用到的字体子集。
- 字体许可与实际源字体版权元数据已随项目保存在 `docs/licenses/Noto-Serif-OFL-1.1.txt` 和 `docs/licenses/Noto-Serif-SC-font-metadata.txt`；分发本示例时应一并保留。完整第三方说明见根目录 `THIRD_PARTY_NOTICES.md`。
- 若使用其它系统，请通过 `-FontPath` 指向本机可用的 CJK TTF/OTF；`-SourceFont` 可改默认源字体。
- 重新生成后建议实际解析核对，而不是只看 PDF 截图：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile examples/offlinequiz-example.pdf `
  -OutDir work/04a/parse -RenderPages "1"
```

解析和渲染结果输出到 `work/`。
