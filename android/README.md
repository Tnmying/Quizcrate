# 题匣 Android 开发说明

我使用 Kotlin、Jetpack Compose 和 Room 开发题匣。应用包名为 `com.offlinequiz.app`，支持 Android 10（API 29）及以上，compileSdk/targetSdk 为 36。

当前版本为 **28 / `0.4m-single-review`**。我已完成当前版本的真机功能检查，暂未发现问题；295 项 JVM 测试及调试构建通过。功能和使用方法见 [项目 README](../README.md)。

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

我保留了 `tools/android-build.ps1` 作为项目内依赖的构建入口。它会将 APK 复制到 `outputs/apk/`；使用时请显式指定一个新的 `-ApkName`，避免覆盖同名包。

## 测试

- `app/src/test/`：JVM 逻辑、服务、控制器与会话测试。
- `app/src/androidTest/`：Android Room、迁移、事务、备份、PDF 存储等设备自动化测试。
- `tools/pdf-probe/run-tests.ps1`：共享 PDF 解析器的合成回归；没有私有样本时跳过私有样本部分。

我已完成真机手动检查；另外保留的 28 项设备自动化测试目前只完成编译，尚未在设备上运行。这两类检查使用不同的流程。

连接测试设备后，可执行：

```powershell
& ./android/gradlew.bat --project-dir ./android :app:connectedDebugAndroidTest
```

## 架构和许可

我的模块划分、数据兼容性和判题规则见 [架构说明](../docs/ARCHITECTURE.md)。依赖及资源许可见 [第三方材料与许可说明](../THIRD_PARTY_NOTICES.md)。

当前构建配置排除了部分 `META-INF/LICENSE/NOTICE`；我会在正式分发 APK 前核对最终依赖，并补齐随包许可材料。
