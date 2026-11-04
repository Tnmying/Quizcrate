# PDF 解析工具

**简体中文** | [English](README.en.md)

这个桌面工具用于检查文字型 PDF 的解析行为，与安卓应用共享题目模型和解析规则。它使用 Apache PDFBox 2.0.27，按字形坐标重建文本、提取下划线，并输出题目 JSON 和定位信息。

## 运行环境

需要 JDK 21 和 Windows PowerShell。脚本首次运行会从 Maven Central 下载 PDFBox、FontBox、commons-logging 并校验 SHA-256；缓存齐全后可离线运行。

## 解析自制示例

从项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile examples/offlinequiz-example.pdf `
  -OutDir work/pdf-example -NoRender
```

如果找不到 JDK，可加 `-Jdk '本机 JDK 绝对路径'`。可用 `-RenderPages '1'` 指定渲染页面，或用 `-NoRender` 关闭渲染。

依赖保存在 `tools/pdf-probe/lib/`，编译输出位于 `tools/pdf-probe/build/`，解析结果位于指定的 `work/` 子目录。这些本地文件不随源码分发。

## 回归测试

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

测试覆盖绘图路径、下划线、选项标记、跨行内容、编号和自制五题型 PDF。断言失败时返回非零状态；没有私有样本时会跳过私有样本检查，部分字体相关合成测试在缺少 CJK 字体时也会明确跳过。

## 输出

- `questions.json`：题型、题干、选项、答案、页码和校对标记。
- `summary.json`：数量、编号异常、下划线和校对原因汇总。
- `raw/`：重建文本、字形/线段坐标和实际运行命令。
- `render/`：指定页面的预览图。

真实文档及完整解析结果仅保存在本地。公开演示使用 [自制示例](../../examples/README.md)，解析规则与安卓接入方式见 [架构说明](../../docs/ARCHITECTURE.md)。
