# 阶段 02A 独立验收

结论：**PHASE_02A_BUILD_ACCEPTED_DEVICE_TEST_PENDING**。

## 实际复核

- 阅读构建脚本、Gradle 配置、清单、Application 资源初始化、Android 提取器、Uri 导入、ViewModel、Compose 预览及纯解析单元测试。
- 独立执行 tools/android-build.ps1：testDebugUnitTest 与 assembleDebug 均成功，首次调用的任务为 UP-TO-DATE。
- 为避免以构建缓存冒充独立执行，又以 --offline --rerun-tasks 强制执行 :app:testDebugUnitTest：BUILD SUCCESSFUL，27 tasks executed，XML 为 2 tests / 0 failures / 0 errors。详细日志 work/android-build/codex-tests-rerun.log。
- 独立计算 APK SHA-256：C7697701960F81F6C639CD838D1E8C76292660244164AB34D99736159921DD53，与 DSH 交付相同。大小 41,670,947 bytes。
- 独立运行 apksigner verify，签名有效（调试证书）。aapt2 权限检查只见 AndroidX 自有组件权限，无 INTERNET 或全盘存储权限。
- 检查 APK ZIP 目录：236 项，无嵌入 PDF，无 sample.pdf/questions.json/local-inputs。此目录检查不是对所有二进制内容的完整隐私审计。
- 代码通过系统 OpenDocument 读取 Uri，IO 后台复制及解析，最终关闭文档并清理临时文件；没有调用预生成题库替代真实解析。

## 当前边界

这是最小只读预览版本，尚无题库编辑、持久化、完整练习、错题本和统计，符合 02A 范围。
未运行 Android 设备或模拟器；不能据此宣称文件选择、PdfBox Android 字体/资源、实际 201 题提取、UI 或性能已通过手机验证。仅 JVM 解析测试通过不能替代 Android 提取器运行。

## 下一步

当前不下达 02B，先请用户手动把 outputs/apk/offlinequiz-02a-debug.apk 安装到 RMX3800，并从手机本地选择真实 PDF（不需要 USB 调试）。
至少核对安装/启动、201 题与五类计数（93/44/32/25/7）、58 条下划线、跨行填空、飞行模式导入。错误时保留错误文案或截图。
用户反馈后，Codex 再判断修复 Android 运行问题或推进下一阶段。DSH 本轮保持停止，不持续监控。
