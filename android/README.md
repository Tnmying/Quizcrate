# 题匣 Android 开发说明

**简体中文** | [English](README.en.md)

题匣使用 Kotlin、Jetpack Compose 和 Room 开发。应用包名为 `com.offlinequiz.app`，支持 Android 10（API 29）及以上，compileSdk/targetSdk 为 36。

当前公开版本为 **v0.1.0**（内部版本号 29）。功能和使用方法见项目 README。[正式签名 APK 与发布说明](https://github.com/Tnmying/Quizcrate/releases/tag/v0.1.0) · [项目 README](../README.md)

## 构建环境

- JDK 21
- Android SDK platform 36 / build-tools 36.0.0
- Gradle 8.13 wrapper、AGP 8.13.2
- Kotlin 2.2.21、KSP 2.2.21-2.0.5

首次构建需要联网下载依赖；缓存齐全后可以加 `--offline`。手机运行不需要网络。

从项目根目录运行以下 PowerShell 命令，并将 JDK/SDK 路径换成本机的实际位置：

```powershell
$env:JAVA_HOME = '你的 JDK 21 绝对路径'
$env:ANDROID_HOME = '你的 Android SDK 绝对路径'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $PWD 'work/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $PWD 'work/android-user-home'
& ./android/gradlew.bat --project-dir ./android --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。如果使用 `android/local.properties`，其中的 SDK 路径也应与本机一致。

`tools/android-build.ps1` 是使用项目内依赖的构建入口，会将 APK 复制到 `outputs/apk/`；使用时请显式指定一个新的 `-ApkName`，避免覆盖同名包。

## Release 构建与签名

`tools/android-release.ps1` 会运行 release 单元测试、构建和 Lint，完成 ZIP 对齐、正式签名及签名校验，并生成 APK 与 SHA-256 文件。JDK/SDK 默认为项目 `work/` 下的依赖，也可通过 `-Jdk` 和 `-Sdk` 指定。

签名材料须提前准备。默认位置为本地忽略目录 `local-inputs/signing/` 下的 `quizcrate-release.p12` 和 `keystore-password.txt`，别名为 `quizcrate-release`；也可使用 `-Keystore`、`-PasswordFile`、`-KeyAlias` 指定自己的材料。私钥和密码应安全备份，不随源码或 APK 分发。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/android-release.ps1
```

首次获取缺失的 Gradle/Maven 检查依赖时加 `-Online`；需要强制完整重建时加 `-RerunTasks`。产物位于 `outputs/releases/<版本>/`，同名发布包已存在时脚本会停止，避免覆盖。

旧调试版与正式包的签名不同，换装前应在应用内导出备份。后续正式版更新继续使用同一正式签名和递增的内部版本号。

## 测试

- `app/src/test/`：JVM 逻辑、服务、控制器与会话测试。
- `app/src/androidTest/`：Android Room、迁移、事务、备份、PDF 存储等设备自动化测试。
- `tools/pdf-probe/run-tests.ps1`：共享 PDF 解析器的合成回归；没有私有样本时跳过私有样本部分。

白蓝改版 v0.2.0（内部版本号 30）的发布构建通过 304 项 JVM 测试、Android 16 上的 28 项仪器测试及 Release Lint（0 错误、15 警告、2 提示）。覆盖安装检查使用项目自制题库，升级后核对了题目、待校对状态及已结束的练习记录。

连接测试设备后，可执行：

```powershell
& ./android/gradlew.bat --project-dir ./android :app:connectedDebugAndroidTest
```

## 架构和许可

模块划分、数据兼容性和判题规则见 [架构说明](../docs/ARCHITECTURE.md)。依赖及资源许可见 [第三方材料与许可说明](../THIRD_PARTY_NOTICES.md)。

部分重复的 `META-INF/LICENSE/NOTICE` 由打包配置排除；对应依赖及资源的许可原文与版权声明保留在 APK 的 `assets/third_party/`。
