# 阶段 02A 交接报告：最小安卓 APK 与真实解析接入

状态：**PHASE_02A_READY_FOR_REVIEW**  
日期：2026-11-02  
工作目录：`F:\New project`

> 历史报告提示：2026-11-03 的 `docs/tasks/02a-blank-rules.md` 已修改填空跨行合并与顿号拆空规则；本报告关于“6 个跨行警告”和 14/15 项回归的旧记录仅代表旧版，最新规则、样本对比和新 APK 以 `docs/reports/02a-blank-rules-dsh-report.md` 为准。

> 结论：debug APK 已实际构建成功，逻辑单元测试和阶段 01 解析回归通过；APK 无真实样本、无 `INTERNET`/存储权限，包含系统文件选择器导入、后台本地解析和只读题目预览。**没有可用真机，Android 端实际解析/预览/性能尚未验证**，因此不能声称手机端已经输出 201 题或已通过真机验收。真机检查步骤见 `docs/reports/02a-device-checklist.md`。

## 1. 已完成、实际验证、尚未验证、阻碍

### 已完成

- 在 `work/android-sdk/` 准备最小 Android SDK；在 `work/gradle-user-home/` 准备 Gradle 缓存；在 `work/jdk-21.0.13+9/` 准备带 `jlink` 的完整 JDK。
- 建立原生 Kotlin + Compose 最小工程 `android/`，固定 compile/target SDK 36、minSdk 29，包含 Android 16 构建目标。
- 用 PdfBox-Android 独立封装 Android 端字形/线段提取；统一模型和结构规则复用桌面纯逻辑，不打包桌面 `org.apache.pdfbox`/`java.awt`。
- 主界面“导入 PDF”使用系统 `OpenDocument`，无全盘文件权限；文件复制到应用缓存后在 `Dispatchers.IO` 后台解析，完成后删除缓存副本。
- 处理取消、空文件、损坏、加密、无文字层、0 题等错误；不会把失败显示为成功导入 0 题。
- 结果页展示总数、各题型数量、原编号、来源页、题干、全部选项、答案、待校对原因；六选项不截断，重复选项键按独立标记展示。
- 无真实样本预置、无 `questions.json` 冒充、无联网/OCR/AI。
- 构建脚本 `tools/android-build.ps1` 和 Gradle wrapper；单元测试和 `assembleDebug` 已实际执行。
- 阶段 01 测试入口已拆分：无私有样本时合成测试照常运行，有样本时才额外运行 201 题集成回归并明确 SKIP。

### 实际验证

- `:app:testDebugUnitTest`：2 个本地 JVM 单元测试，0 失败（XML 见 `work/android-build/test-results/`）。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- APK：`outputs/apk/offlinequiz-02a-debug.apk`，41,670,947 bytes，SHA-256 `C7697701960F81F6C639CD838D1E8C76292660244164AB34D99736159921DD53`。
- `aapt2 dump badging`：package `com.offlinequiz.app`，minSdk 29，targetSdk 36，compileSdk 36。
- `aapt2 dump permissions`：没有 `INTERNET`、存储或相机权限；只有 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- `apksigner verify --print-certs`：debug APK 签名有效（Android Debug 证书）。
- APK 内容扫描：236 个 entry，112 个 PdfBox 资源；没有 `sample.pdf`、`questions.json`、`local-inputs` 或样本题干/答案字符串。
- `adb devices -l`：无设备。
- 阶段 01 桌面回归：有样本时 **15 PASS / 0 FAIL**（含新增合成五题型 PDF）；无样本时 **14 PASS / 0 FAIL + sample SKIP**。
- 没有安装 APK 到任何手机，没有真机解析结果；本轮不把 JVM 结果当成 Android 实测。

### 尚未验证

- APK 在 Android 16 / RMX3800 上的安装、文件选择器行为、真实 PDF 解析、UI 渲染和错误提示。
- PdfBox-Android 的 `PDFBoxResourceLoader` 在真机上的资源加载、内存和耗时。
- 文件选择器不同提供方（下载、云盘、文件管理器）的 Uri/显示名/输入流行为。
- 六选项/重复选项在真机 UI 的可读性；当前只验证了解析和预览代码路径。
- 其他 PDF 版式、加密/损坏/无文字层的真机错误路径。
- 完整刷题、编辑、题库持久化、统计、错题本和 02B 功能（明确不在本任务）。

### 阻碍

- 没有已授权连接设备或模拟器；不能执行真机安装/解析。构建和 APK 本身不因此受阻。
- 未下载模拟器镜像，未安装 Android Studio，未修改全局环境变量。

## 2. 环境与版本

### 项目内安装

| 项 | 版本/路径 | 来源/校验 |
|---|---|---|
| Android command-line tools | 23.0，`work/android-sdk/cmdline-tools/latest` | `commandlinetools-win-16111833_latest.zip`，SHA-1 `57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d` |
| Android platform | API 36 / Android 16，`platforms/android-36` | 通过 `android sdk install platforms/android-36`；`AndroidVersion.ApiLevel=36` |
| Build Tools | 36.0.0，`build-tools/36.0.0` | 通过 `android sdk install build-tools/36.0.0` |
| Platform Tools | 37.0.1，`platform-tools` | 通过 `android sdk install`（未安装到 PATH） |
| Gradle | 8.13，wrapper | `gradle-8.13-bin.zip`，SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` |
| JDK | Temurin 21.0.13+9，`work/jdk-21.0.13+9` | `OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip`，SHA-256 `a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9` |

### 为什么额外准备 JDK

现有 PyCharm JBR 21.0.9 能运行阶段 01 的 JVM 探针，但 AGP 的 `JdkImageTransform` 需要 `jlink.exe`；JBR 的 `bin` 中没有 `jlink`，首次构建报：

```text
jlink executable ...jbr\bin\jlink.exe does not exist
```

因此在 `work/` 内准备了完整 Temurin JDK 21；`tools/android-build.ps1` 现在只选择同时含 `java/javac/jlink` 的 JDK。没有安装到系统、没有改 `JAVA_HOME` 系统设置。

### 工程版本

| 组件 | 固定版本 |
|---|---|
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 29（Android 10） |
| AGP | 8.13.2 |
| Gradle | 8.13 |
| Kotlin | 2.2.21 |
| Compose BOM | 2025.10.01 |
| Activity Compose | 1.11.0 |
| Lifecycle ViewModel | 2.9.4 |
| Coroutines | 1.10.2 |
| PdfBox-Android | 2.0.27.0（Apache-2.0） |

版本均为固定值，无 `+`、`latest` 或动态版本。AGP 8.13.2 与 Gradle 8.13、JDK 21、compileSdk 36 在本机实际构建通过。构建过程中出现 SDK XML v4 的警告（新旧 command-line tools 的元数据版本差异），不影响本次构建结果。

## 3. 构建与测试入口

### 构建

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/android-build.ps1
```

脚本在当前进程作用域设置（不写入系统环境）：

```text
JAVA_HOME=F:\New project\work\jdk-21.0.13+9
ANDROID_HOME=F:\New project\work\android-sdk
ANDROID_SDK_ROOT=F:\New project\work\android-sdk
ANDROID_USER_HOME=F:\New project\work\android-user-home
GRADLE_USER_HOME=F:\New project\work\gradle-user-home
```

`android/gradlew.bat` 固定 Gradle 8.13 和 distribution SHA-256；`android/local.properties` 的 SDK 路径被 `.gitignore` 忽略。

实际构建任务：

```text
:app:testDebugUnitTest
:app:assembleDebug
```

最终日志：

```text
BUILD SUCCESSFUL in 36s
```

构建记录：

- `work/android-build/build-summary.txt`
- `work/android-build/build-log-utf8.txt`
- `work/android-build/test-results/TEST-com.offlinequiz.app.QuestionParserTest.xml`

### 单元测试

`android/app/src/test/java/com/offlinequiz/app/QuestionParserTest.kt`：

1. `syntheticFiveTypesAndCrossLineUnderline`：五类题 + 跨行填空。
2. `duplicateAndSixOptionMarkersRemainVisible`：六选项完整、重复 B 可见且需要校对。

结果：**2 tests，0 failures，0 errors**。

### 阶段 01 回归

```powershell
# 有本机样本：15 通过 / 0 失败
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1

# 无私有样本：14 通过 / 0 失败 + 样本集成 SKIP
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 `
  -Sample local-inputs/does-not-exist.pdf
```

新增的合成 PDF 测试在 `PdfProbeRegression.java` 中生成自制五题型 PDF（含跨行下划线），不依赖真实样本、不写入 APK。阶段 01 的 201 题真实样本集成回归仍只在提供 `local-inputs/sample.pdf` 时运行。

## 4. 实现范围

### 工程结构

```text
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew / gradlew.bat / gradle/wrapper/
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/com/offlinequiz/app/
      OfflineQuizApplication.kt
      AndroidPdfExtractor.kt
      PdfBoxAndroidParser.kt
      ImportViewModel.kt
      MainActivity.kt
      Theme.kt
    src/test/java/com/offlinequiz/app/QuestionParserTest.kt
```

统一模型/结构规则复用 `tools/pdf-probe/src/pdfprobe/Model.java` 和 `QuestionParser.java`。Gradle 用 `stageCoreSources` 只复制这两个纯逻辑文件到生成目录，不把 `TextExtractor.java`（桌面 PDFBox/java.awt）或 CLI 打包进 APK。

### 导入流程

1. Compose 主界面点击“选择 PDF”。
2. `ActivityResultContracts.OpenDocument` 打开系统文件选择器，不申请全盘/存储权限。
3. `PdfBoxAndroidParser` 通过 `ContentResolver` 复制到应用 cache；空文件直接报错。
4. `PDDocument.load` 在 `Dispatchers.IO` 后台线程读取；损坏/加密/无文字层/0 题分别给出错误。
5. `AndroidPdfExtractor` 用 PdfBox-Android 的 `PDFTextStripper` + `PDFGraphicsStreamEngine` 提取字形和实际描边线段；沿用阶段 01 的路径暂存、`n` 丢弃、下划线匹配规则。
6. `QuestionParser` 生成统一题目模型。
7. 解析后删除 cache 中的 PDF 临时副本，不保存真实文档。
8. 结果页只读预览；不做编辑/持久化/刷题。

### 错误处理

- 取消文件选择器：不解析，保持当前界面。
- 空文件：`所选文件为空`。
- 加密：`PDF 已加密，当前版本无法读取`。
- 损坏/读取失败：`PDF 读取失败，文件可能损坏或格式不支持`。
- 无文字层：`PDF 没有可提取的文字层，暂不支持扫描件`。
- 无题目：`没有识别到题目...`。
- 失败不会显示为成功 0 题。

### 结果预览

- 总数、各题型数量、下划线总数/匹配数。
- 每题：题型、原编号、来源页、完整题干、全部选项、答案、待校对原因。
- 六选项全部渲染；重复键保留每个标记，UI 显示“重复标记，第 N 个”，不以 key 合并。
- 当前样本 6 个跨行填空会显示 `cross_line_underline` 待校对。

## 5. APK 产物与隐私检查

| 项目 | 结果 |
|---|---|
| APK | `outputs/apk/offlinequiz-02a-debug.apk` |
| 大小 | 41,670,947 bytes |
| SHA-256 | `C7697701960F81F6C639CD838D1E8C76292660244164AB34D99736159921DD53` |
| 签名 | Android Debug 证书；`apksigner verify` 通过 |
| package | `com.offlinequiz.app` |
| minSdk / targetSdk / compileSdk | 29 / 36 / 36 |
| 权限 | 无 `INTERNET`、无存储/相机权限；只有 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| APK 内真实文档 | 未发现 `sample.pdf`、`questions.json`、`local-inputs`、样本题干/答案字符串 |
| APK 内 assets | 112 个 PdfBox cmap/glyph 资源；没有 app 预置 PDF |

检查命令/记录：

```text
work/android-build/apk-badging.txt
work/android-build/apk-permissions.txt
work/android-build/apk-signature.txt
work/android-build/apk-privacy-scan.txt
```

`aapt2 dump permissions` 只显示：

```text
permission: com.offlinequiz.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.offlinequiz.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

这是 AndroidX 的组件隔离权限，不是联网/存储权限。真实样本只允许用户手动从手机文件系统导入，不进入 APK。

## 6. 未验证与 Android 边界

- **没有可用设备**：`adb devices -l` 返回空列表；没有安装 APK 到任何手机，没有 Android 端 201/58 的结论。
- 真机上的系统文件选择器、PdfBox-Android 资源加载、字体解析、UI 性能和内存均未验证。
- 没有模拟器镜像；未安装 Android Studio/模拟器，未开启开发者选项/USB 调试。
- APK 是 debug 签名，不是发布签名；阶段 02A 不发布。
- 当前预览只读；编辑、题库保存、刷题、统计和 02B 未实现。
- 真实样本仍只在 `local-inputs/` 和 `work/`；APK 和公开源码不含真实题库。

真机步骤见 `docs/reports/02a-device-checklist.md`。没有真机数据前，不声称 Android 解析已经输出 201/58。

## 7. 产物清单

- `android/`：最小 Compose 工程。
- `tools/android-build.ps1`：项目内构建入口。
- `android/gradlew.bat`、`android/gradle/wrapper/`：Gradle 8.13 wrapper。
- `outputs/apk/offlinequiz-02a-debug.apk`：可安装 debug APK。
- `work/android-build/build-summary.txt`：APK/SHA/版本摘要。
- `work/android-build/build-log-utf8.txt`：构建日志。
- `work/android-build/test-results/`：Android 单元测试 XML。
- `work/android-build/apk-*.txt`：APK badging/权限/签名/隐私扫描。
- `work/android-build/phase01-tests-with-sample-summary.txt`：阶段 01 15/0。
- `work/android-build/phase01-tests-no-sample.log`：无样本 14/0 + SKIP。
- `docs/reports/02a-device-checklist.md`：一页真机检查步骤。

## 8. 下一步

1. 用户在真机安装 APK，按 `docs/reports/02a-device-checklist.md` 手动导入 `local-inputs/sample.pdf`，记录计数、错误和性能。
2. Codex 复查构建、APK、隐私检查和真机结果；确认后才考虑 02B。
3. 若真机解析计数与 JVM 不一致，保留手机诊断输出（不要上传真实题库），再针对 Android 资源/字体/路径 API 修正。
