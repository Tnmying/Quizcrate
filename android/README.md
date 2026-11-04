# 题匣 Android 开发说明

原生 Kotlin + Jetpack Compose + Room，完全离线；应用包名 `com.offlinequiz.app`，minSdk 29，compileSdk/targetSdk 36。

当前版本 **28 / `0.4m-single-review`**，功能与已知限制见 [项目 README](../README.md)。当前版已有 295 项 JVM 测试及完整构建通过、3 项桌面内存 SQLite 检查通过；28 项设备测试只编译未运行。用户确认当前版手机完整检查通过，Codex 未进行独立设备验收。当前改动总记录见 [DSH_HANDOFF](../docs/DSH_HANDOFF.md)。

## 构建环境

JDK 21、Android SDK platform 36 / build-tools 36.0.0、Gradle 8.13 wrapper、AGP 8.13.2、Kotlin 2.2.21、KSP 2.2.21-2.0.5。首次构建需联网获取 Gradle/Maven 依赖，缓存齐全后可加 `--offline`。

普通开发环境的构建命令见根 README。本项目现有机器使用项目内依赖；以下从项目根目录运行，只设置当前终端环境，不改系统设置：

```powershell
$env:JAVA_HOME = (Resolve-Path './work/jdk-21.0.13+9').Path
$env:ANDROID_HOME = (Resolve-Path './work/android-sdk').Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = (Resolve-Path './work/android-user-home').Path
$env:GRADLE_USER_HOME = (Resolve-Path './work/gradle-user-home').Path
& ./android/gradlew.bat --project-dir ./android --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

`work/` 不上传，新获取源码者需自行准备依赖并调整路径。`android/local.properties` 属于本机配置，不上传。

- 应用 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
- 设备测试 APK 编译成功不代表设备测试运行成功；连接测试设备后单独运行 `:app:connectedDebugAndroidTest`。
- 现有封装脚本 `tools/android-build.ps1` 使用项目内 SDK，并复制包到 `outputs/apk/`；必须显式提供一个**从未使用过的新文件名**作为 `-ApkName`，脚本会覆盖同名文件。README 不再提供会覆盖历史包的固定输出名。
- 本次只更新文档，未重新构建、签名或生成 APK。

## 关键实现约定

- 解析规则以 `tools/pdf-probe/src/pdfprobe/Model.java` 和 `QuestionParser.java` 为唯一源，由 `stageCoreSources` 同步到构建生成目录，不能修改生成副本。
- 数据库 `offlinequiz.db`、Room schema 3，显式迁移，禁止清库或 destructive fallback；schema 历史随源码保留。
- 练习采用固定题目快照、幂等作答、草稿保存与写入中离页保护；修改题库不重写旧会话。
- 单题错题复习使用 `single_review` 来源，与已有普通/批量错题整轮共存，不计整库完成遍数。
- 备份格式 1，恢复创建新 ID 和独立副本，保留旧库；新单题复习记录与旧版应用的兼容边界见根 README。
- PDF 与备份使用系统 SAF；私有 PDF 位于 `files/libraries/<libraryId>/source.pdf`。应用不申请 INTERNET、广泛存储或相机权限。

## 第三方许可

见 [第三方材料说明](../THIRD_PARTY_NOTICES.md)。当前 Gradle 配置排除了部分 `META-INF/LICENSE/NOTICE`；正式 APK 分发前需核对最终依赖与资源，保留随包许可材料。本次不改构建打包行为，也不把文档整理视为 APK 许可验收完成。

## 验证入口

- `app/src/test/`：JVM 逻辑、服务、控制器与会话测试。
- `app/src/androidTest/`：Room、事务、备份、PDF 存储、迁移与整轮/单题隔离测试，需设备执行。
- `tools/pdf-probe/run-tests.ps1`：共享解析合成回归，无私有样本时跳过私有样本检查。
- [早期手机清单](../docs/reports/04a-device-checklist.md)为历史流程材料，不是当前版逐项实测记录。
