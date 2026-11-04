# 阶段 02A：最小安卓 APK 与真实解析接入

先读 AGENTS.md、PROJECT_BRIEF.md、docs/reports/01-codex-revision-01-review.md。本任务由 Codex 基于用户既定需求授权；仅执行 02A，完成后停下。不是完整刷题应用阶段。

## 目标

交付能在安卓设备安装的 debug APK，支持用户在手机系统文件选择器选 PDF、真正本地解析、预览各题型数量及题干/选项/答案/待校对提示。先证明阶段 01 路线可以构建到手机，后续再做编辑、持久化题库与完整刷题。

## 环境准备范围

- 优先复用已验证的现有 JDK。只为本项目准备必要的 Android command-line tools、单个 compile SDK platform、build-tools/platform-tools、Gradle wrapper 及构建依赖。查官方资料选择相互兼容的固定版本，并记录来源、版本及下载校验。
- 本任务明确授权在 `work/android-sdk/`、`work/gradle-user-home/` 和项目内缓存安装这些最小构建依赖。开发期间下载依赖可联网；成品解析和使用必须离线。不要安装 Android Studio、模拟器镜像、多个平台/NDK，不做全局安装，不修改系统环境变量。
- ANDROID_HOME、JAVA_HOME、GRADLE_USER_HOME 等仅在本次构建进程作用域配置，local.properties 忽略提交。若缺少单独 JDK 且现有 JBR 无法构建，可在 work/ 下准备兼容版本；不要直接推断 JBR 一定不可用。
- 遇到许可证/下载问题先按正常工具流程处理并如实报告，不绕过网络或安全限制，不读取账号凭据。若确有阻碍，保留可运行成果和准确错误，停止相同失败的反复重试。

## 实现范围

1. 建立原生 Kotlin + Compose 最小工程和可复现构建入口。暂用 OfflineQuiz/离线刷题作为开发名，最低支持 Android 10（API 29）作为可调整工程默认值，必须包含 Android 16 的构建/运行兼容目标。从官方文档选择当前兼容的 compile/target SDK、AGP、Kotlin 和 Gradle；不使用浮动版本。
2. 保留 CLI 探针和测试，把统一题目模型与结构规则隔离成可复用纯逻辑；PdfBox-Android 的资源初始化、PointF/文字/线段 API 单独封装。不能将桌面 org.apache.pdfbox/java.awt 直接打包进入安卓，也不能在运行时调用 Python、服务器或 AI。
3. 手机主界面提供“导入 PDF”。使用系统文件选择器获取用户选择的 PDF，不申请全盘文件权限。处理取消、空文件、损坏/加密/无文字 PDF 等错误，避免把失败显示为成功导入 0 题。
4. 在后台线程读取/解析，展示处理状态；不能卡住主线程。不预置真实样本，不硬编码题量/答案，不从预先生成的 questions.json 冒充 PDF 解析。
5. 结果界面展示各类数量、题目列表和详情（原编号、来源页码、题干、选项、答案、待校对原因）。六个及以上选项不能截断；异常重复键用稳定独立标识展示，不能合并覆盖。此阶段答案预览允许显示，后续刷题再隐藏。
6. 解析接口预留未来其他格式/媒体内容扩展即可，不实现 OCR、公式、AI、联网功能、账号或同步；第一版 APK 不申请 INTERNET 权限。
7. 使用自制公开题目创建小型文字 PDF 测试夹具，覆盖五类题与跨行下划线。真实样本仅本机验证，不写进 src/main/assets 或可公开 APK，不进入可提交 androidTest assets。
8. 修正阶段 01 测试入口：合成测试无私有样本也能运行；有本机样本时再额外运行 201 题的集成回归，明确区分通过与跳过。

## 交付与验证

- 实际运行逻辑单元测试、合成解析回归和 assembleDebug，保存准确命令、版本、退出结果；编译成功和真机运行分别报告。
- 复制可安装 APK 到 `outputs/apk/offlinequiz-02a-debug.apk`，记录 SHA-256 与大小。APK 不含真实文档、真实题库、个人路径或密钥；提供相应检查依据。
- 若存在已经连接且获授权的设备，可以检查其可用性；不要自动开启手机开发者选项/USB 调试，不安装到用户手机，不模拟已经运行的证据。没有设备时，交付 APK + 一页真机检查步骤，明确“已构建、真机待测”。真机需要用户手动导入 local-inputs/sample.pdf。
- 尚无真机数据时，不声称 Android 解析已经输出 201/58；提供用于本地核对的计数/诊断入口即可。
- `docs/reports/02a-dsh-report.md`：已做/实测/未测/阻碍、环境与依赖版本、构建步骤、APK 位置和已知限制。
- `docs/STATUS.md` 标记 `PHASE_02A_READY_FOR_REVIEW` 或 `PHASE_02A_BLOCKED`，完成后停止，用户会叫 Codex 检查。不自行进入 02B，不提交/推送/公开发布，不安装协作插件。
