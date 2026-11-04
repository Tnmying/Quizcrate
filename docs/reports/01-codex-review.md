# 阶段 01：Codex 独立验收

日期：2026-11-02。结论：**PHASE_01_CHANGES_REQUESTED**。样本提取结果可复现，但有已复现的静默误识别问题，安卓运行能力仍未验证。

## 实际检查

- 已阅读 DSH 报告、Java 提取器/解析器/数据模型/入口和 PowerShell 运行脚本。
- 独立运行 `tools/pdf-probe/run.ps1 -InputFile local-inputs/sample.pdf -OutDir work/codex-review-01 -NoRender`，退出码 0，约 4 秒完成编译与运行。此时间仅为本次桌面验证，不代表手机性能。
- 重跑得到单选 93、多选 44、判断 32、填空 25、简答 7，共 201 题。201 个 ID 唯一，无空答案；25 道填空的空数量与答案数量一致。
- 与 DSH 原始 questions.json 比较，除 generatedAt 外完全一致。
- 抽查第 20、21 页渲染及全部 25 道填空的题干/答案清单；已查看的下划线位置与输出相符，6 个跨行题保留待校对标记。
- 独立新增临时回归 `work/codex-review-01/ReviewRegression.java`，由当前 JDK 编译运行。结果保存于同目录 `regression-result.txt`。这些是故意触发缺陷的小例子，当前输出包含失败；不是已通过的正式回归套件。
- 没有安装 Android SDK 或开始阶段 02。

## 必修问题

### R1 / P2：没有真正绘制的路径也会被当作下划线

位置：`tools/pdf-probe/src/pdfprobe/TextExtractor.java`，PageGraphics 的 lineTo、appendRectangle、endPath、strokePath。

lineTo 时立即将候选线段写入 underlines，但 lineTo 只构造 PDF 路径；若后续是 n（丢弃路径/结束剪裁路径）而没有描边，页面上实际不存在这条线。当前 strokePath 是空方法，无法区分。

独立复现：对相同文字，画水平路径，分别以 stroke 和 n 结束；当前两者均返回 1 条下划线，未描边样例期望 0。这会在新 PDF 上凭空挖空。矩形也存在构造路径即计入的问题。

修复要求：先暂存路径，按实际描边/填充操作提交候选；endPath 丢弃；处理子路径和关闭路径，避免提交两次。为有描边、无描边、剪裁、不相关形状增加有意义的回归。

### R2 / P2：选项异常被吞入正文，未标记校对

位置：`tools/pdf-probe/src/pdfprobe/QuestionParser.java`，OPTION_MARKER、optionMarkers 和 parseChoice。

当前只接收期望顺序内的 A-E，其他重复/逆序标记被忽略。后续“选项连续”检查只能检查筛选后的选项，不能发现被吞掉的异常。

独立复现：`A. first B. second B. duplicate C. third D. fourth` 得到四项，B 文本为 `second B. duplicate`，needsReview=false。六选项且正确答案为 A 时，F 项被吞入 E，仍无任何校对标记。

修复要求：先保留/检查实际观测到的全部候选标记，再决定边界；异常不能静默通过。对超出支持范围的标记必须显式待校对并保留原文，或正式支持 A-Z。添加重复、缺号、逆序、六选项和正常跨行选项回归。

### R3 / P2：技术比较使用了过时的安卓 API 信息

DSH 报告第 4 节称 PdfRenderer 无文字提取 API，这对当前安卓版本不成立。官方 PdfRenderer.Page.getTextContents() 自 API 35 提供文字内容；API 36.1 另有页对象能力，但不能把这些能力泛化到所有系统版本。

来源：https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#getTextContents()

修复要求：按 API 版本修正比较，核实目标最低版本和下划线定位所需能力，再保留或修改候选库决定。不是要求立即换库。AGPL/商业许可证也不能一概描述成“不适合公开 GitHub”，应区分许可证兼容性、项目授权选择与技术成本。

## Android 验证边界

DSH 准确标注了尚无 APK/安卓构建或真机测试，不能据此宣称手机端已验收。环境缺失单独记录，先完成 R1-R3 的小范围返工。后续阶段再准备最小安卓构建工具和验证 APK，不安装模拟器/完整 Android Studio 作为默认前提，也不需要因为缺少它们停止所有可推进工作。

报告中“Git 未找到”仅表示 DSH 当时的搜索范围。Codex 会话拥有已知的打包 Git，不应据此推断整台机器不能使用 Git；目前仍不初始化或提交仓库。

## 下一步

执行 `docs/tasks/01-revision-01.md`，完成后停止并由用户通知 Codex 复查。保持样本结果对比，不能为了修复新样例破坏 201 题提取。
