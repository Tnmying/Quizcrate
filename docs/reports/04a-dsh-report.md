# 阶段 04A 报告：真机验收材料与项目说明

日期：2026-11-03。执行者：DSH。状态：`PHASE_04A_READY_FOR_REVIEW`。本轮只整理自制验收材料/说明，未改产品功能或解析器，未升版本/复制新 APK，未做真机测试。

## 结论

- 新增 `examples/`：完全自制的五题型文字 PDF、可复现 Java/字体/生成脚本、逐题期望结果。
- 实际用现有 `pdf-probe` 解析：**7 题**，单选 1、多选 1、判断 1、填空 3、简答 1；待校对 1；下划线 3 段全部匹配，跨行合并 1 次。逐题答案与预期一致，未为凑题数改解析器。
- 渲染查看 1 页布局清楚；题目/答案/下划线/跨行位置可读。
- 新增 `docs/reports/04a-device-checklist.md`：约 10 分钟首轮手机流程、期望结果、可填写异常位置、22 项仪器测试命令与额外检查；明确手机实测尚未进行。
- 更新根 `README.md` 与 `android/README.md` 到实际功能/备份/schema 3；说明纯文字 PDF 边界、离线存储/备份、构建/测试入口、已知限制、真机未验收状态。
- 共享解析回归 **30 PASS / 0 FAIL**；本轮无产品代码/构建行为变更，因此未重复 Android 全量强制构建。
- 最新包仍为 03D2 R2：`outputs/apk/offlinequiz-03d2-r2-debug.apk`（versionCode 16，SHA-256 `620165237995BCDA53824B9E4C4681ABE2EBA40E30032F7E2DC2A308E78ED468`）；未升版本、未复制内容相同的“新”包。

## 1. 自制验收题库

### 文件

| 文件 | 说明 |
| --- | --- |
| `examples/offlinequiz-example.pdf` | 1 页 A4、文字层、7 题，34,397 bytes，SHA-256 `978E1875605CA31DDF227EF5BB34CB8F2CD0EE1F814814187EB344B9373CFABA` |
| `examples/MakeExamplePdf.java` | PDFBox 2.0.27 生成源码；题目、答案、下划线位置均自制 |
| `examples/prepare-example-font.py` | 从本机 Noto Serif SC 派生去兼容区 cmap 的工作字体（仅 `work/`，不提交） |
| `examples/generate-example-pdf.ps1` | 复用 `work/` JDK 与 `tools/pdf-probe/lib` 的 PDFBox 重新生成 |
| `examples/README.md` | 期望解析结果、逐题答案、生成命令、字体来源/限制 |

字体来源：本机 Windows 的 Noto Serif SC（SIL OFL）；生成的 PDF 只嵌入实际使用的字形子集，字体文件本身不进入仓库。默认命令会用 Python `fontTools` 去掉 Noto 变量字体的康熙部首兼容 cmap；如直接提供标准 Unicode cmap 的 `-FontPath`，可跳过该步骤。

### 内容与期望

- 单选题：标准大气压沸点，答案 A。
- 多选题：可再生能源，答案 A、C（括号内 `AC`）。
- 判断题：地球公转，答案 √。
- 填空题 1：四大发明中一个下划线覆盖“火药、指南针、印刷术”，按 `、` 拆成 3 个学习空。
- 填空题 2：`阳` 在行末、`光` 在下一行行首，跨行下划线合并为“阳光”，1 空 2 段。
- 填空题 3（待校对）：故意无下划线，原因 `no_learning_blanks`、`no_underline_answers`。
- 简答题：光合作用意义，参考答案“制造有机物，释放氧气，并参与碳氧平衡。”。

### 实际解析与渲染

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile examples/offlinequiz-example.pdf `
  -OutDir work/04a/final-parse -RenderPages "1"
```

实际输出：

```text
pages=1
questions=7
byType={single_choice=1, multiple_choice=1, true_false=1, fill_blank=3, short_answer=1}
underlines={total=3, matched=3, unmatched=0, crossLineBlanks=1, crossLineMerges=1}
withReview=1
```

逐题答案（从 `questions.json` 读取）：

| 题 | 类型 | 答案 | 备注 |
| --- | --- | --- | --- |
| 1 | 单选 | A | 4 选项 |
| 2 | 多选 | A、C | 括号 `AC` |
| 3 | 判断 | √ | 括号符号 |
| 4 | 填空 | 火药 / 指南针 / 印刷术 | 1 原始空，3 学习空，`splitByEnumeration=true` |
| 5 | 填空 | 阳光 | 跨行 1 空，2 段 |
| 6 | 填空 | （空） | 待校对；`no_learning_blanks`、`no_underline_answers` |
| 7 | 简答 | 制造有机物，释放氧气，并参与碳氧平衡。 | “参考答案” |

- 第 6 题确认前不进入练习；确认后最短练习顺序为原顺序 1→2→3→4→5→6→7。
- 渲染图仅放 `work/04a/final-parse/render/page-01.png`；已实际查看，题干/选项/答案/下划线/跨行布局清晰。中间图和解析输出不入公开目录。
- 解析器未修改；没有为固定题数改规则。实际结果与设计预期一致。

## 2. 手机验收清单

`docs/reports/04a-device-checklist.md` 提供约 10 分钟流程：

覆盖安装（绝对路径/版本/SHA/调试签名）→ 断网/权限检查 → 导入自制 PDF → 预览/待校对编辑 → 顺序/随机/错题 → 简答匹配/自评 → 旋转或强杀恢复 → 导出/取消 → 恢复预览取消 → 正式恢复新副本 → 原库/新库/PDF/次数核对 → 22 项仪器测试命令 → 额外旋转/强杀/低存储检查。

- 清单明确“手机实测尚未进行”，每步留结果/异常填写位置。
- 明确要求用自制测试数据；不能先拿用户唯一真实数据试恢复。
- 没有启动模拟器/监控，也没有把准备命令写成执行成功。

## 3. 项目说明

- 新增根 `README.md`：用途、安卓流程、纯文字 PDF 边界、离线数据/schema 3/备份、示例、构建/测试、已知限制、真机未验收、待用户决定名称/许可证/仓库地址。
- 重写 `android/README.md`：从旧“03A/schema 2、无错题/匹配/备份”更新为实际 03D2 R2 功能；schema 3、备份/恢复、错题/统计/历史、简答匹配、22 项仪器测试只编译、版本 16。
- 构建命令显式传 `-ApkName offlinequiz-03d2-r2-debug.apk`，不覆盖旧包；未改 `tools/android-build.ps1` 默认值/产品构建行为。
- 未提供下载链接、徽章、发布版本或手机截图；未替用户选择正式名称/许可证/GitHub 地址。

## 4. 公开目录隔离检查（只读）

`.gitignore` 已排除：

- `local-inputs/`（真实样本/本地题库）、`work/`（构建依赖、解析/渲染、临时文件）、`outputs/`（APK/产物）；
- `local.properties`、`*.keystore`、`*.jks`、`.env*`；
- `.gradle/`、`**/build/`、IDE/缓存、`tools/pdf-probe/lib/`。

检查结果：

- `examples/` 只有自制 PDF/源码/脚本/README；未摘录 `local-inputs/sample.pdf` 或真实题库，无本机用户名/私密路径/凭据。
- 根/Android/示例 README 使用相对路径或通用 Windows 字体路径；清单中的绝对 APK 路径是用户本机验收所需，不含凭据。
- `docs/` 为项目文档/历史报告，未包含真实样本文件或完整真实题库；未发现需要修改的隔离规则。
- 没有创建 `.git` 提交/推送/Release；未删除旧文件。

## 5. 实际执行与未执行

实际执行：

- `tools/pdf-probe/run.ps1` 解析自制 PDF，实际得到 7 题、3 段下划线全匹配、1 次跨行合并、1 题待校对；渲染 1 页并查看。
- `examples/generate-example-pdf.ps1` 实际生成 PDF（Noto 派生字体，34,397 bytes）。
- `tools/pdf-probe/run-tests.ps1` → **30 PASS / 0 FAIL**（含真实样本 201 题）。
- 未改产品代码/构建行为，故本轮未重复 Android 全量强制构建（按任务允许）。

未执行：

- 真机 SAF 导入/导出/恢复、触控、旋转/强杀、低存储、系统 DocumentProvider 异常。
- 22 项仪器测试未运行（无设备）；真实 Room/系统文件选择未验证。
- 未打开本机真实用户库试恢复。

## 6. 交付/最新包

- 最新 APK：`F:\New project\outputs\apk\offlinequiz-03d2-r2-debug.apk`
  - versionCode 16 / versionName `0.3d2-r2-mobile-backup`
  - 43,920,157 bytes，SHA-256 `620165237995BCDA53824B9E4C4681ABE2EBA40E30032F7E2DC2A308E78ED468`
  - Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`
  - 无 INTERNET/广泛存储权限；旧 APK 全部保留。
- 本轮**未升版本、未复制同内容新 APK**；入口/安装信息见清单。

## 7. 待用户决定

- 正式应用名称、开源许可证、GitHub 仓库地址/发布方式；本轮不替用户选择，也不创建 Release。

## 变更文件

新增：`examples/offlinequiz-example.pdf`、`examples/MakeExamplePdf.java`、`examples/prepare-example-font.py`、`examples/generate-example-pdf.ps1`、`examples/README.md`、根 `README.md`、`docs/reports/04a-device-checklist.md`、`docs/reports/04a-dsh-report.md`。

修改：`android/README.md`、`docs/STATUS.md`、`docs/DSH_HANDOFF.md`。

未改：产品代码/解析器/构建默认值、APK 版本/签名/旧包、Room schema、真实用户数据。

## 阻碍

无实现阻碍；唯一限制是无安卓设备/模拟器，真机项未执行。完成本轮并写报告后停止，等待用户手动通知 Codex 验收。
