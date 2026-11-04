# 题匣（Quizcrate）

题匣是一个完全离线的安卓 PDF 题库刷题应用。导入含参考答案的**文字型 PDF**，在手机本地解析、校对和练习，查看错题与统计，并备份自己的学习记录。

英文项目名为 **Quizcrate**，GitHub 仓库为 [Tnmying/Quizcrate](https://github.com/Tnmying/Quizcrate)；现有工程代号 OfflineQuiz 和安卓包名保持不变。

无需登录、服务器、联网 AI 或运行时模型；应用不申请网络权限。支持 **Android 10（API 29）及以上**。

## 当前状态

当前版本：**28 / `0.4m-single-review`**（2026-11-04）。本仓库提供应用源码、测试、自制 PDF 示例和项目文档，暂未提供 APK 下载链接。当前本地安装包是 **debug 测试包**，不是正式签名发布包。

| 验证项目 | 已有结果 |
| --- | --- |
| JVM 测试 | 41 类、295 项通过，0 失败/错误/跳过 |
| 完整构建 | 79 个强制重建任务执行成功，应用及设备测试 APK 编译成功 |
| 桌面内存 SQLite 检查 | 3 项通过；不代替 Android Room 设备测试 |
| 手机检查 | 用户确认当前版完整检查通过；未提供逐项记录，不等于 Codex 独立设备验收 |
| Android 仪器测试 | 28 项仅编译，尚未在设备运行 |

以上为现有版本的验证记录，本次文档整理没有重新运行构建或测试。详细改动与验证证据索引见 [交接记录](docs/DSH_HANDOFF.md)；旧阶段报告保留为历史材料。

## 功能

- **PDF 变题库**：导入、预览、命名保存多个题库；支持单选、多选、判断、填空、简答。
- **按需校对**：正常题可直接练习，缺答案或有歧义的题保留处理提示；可编辑题干、选项、答案和填空位置，并查看原 PDF 页。
- **顺序与随机练习**：保存进度和草稿；刷题时可纠错，新轮次使用修改后的题，当前轮次与历史保留原快照。
- **跳过与偷看答案**：跳过直接进入下一题，不计有效题量、对错或正确率；偷看答案按错误记录，进入错题复习范围。
- **判题与自评**：单选/判断按参考答案，多选必须全部匹配；填空支持可接受答案；填空/简答可在心中作答后自评记得或没记住。简答文字匹配度仅辅助对照，不自动判对。
- **错题复习**：筛选待复习或全部历史错题，批量重练，或点击错题卡只复习这一题；单题复习保留原整轮进度与草稿，答对或自评记得后更新待复习状态，历史错误保留。
- **统计**：正确/记得与错误/没记住合并展示；累计有效题量与普通练习完成遍数分开。普通练习走完一轮计一遍，包含跳过；错题单题复习不增加整库遍数。
- **大题库定位**：题号跳转、右侧拖动滑块；打开题目后返回管理列表保留浏览位置。
- **本地备份与恢复**：导出题库、练习记录、草稿及可用来源 PDF；恢复前预览摘要，确认为新副本导入，不覆盖原题库。

## 开始使用

1. 准备含参考答案、有文字层的 PDF；可先用项目自制的 [7 题示例](examples/offlinequiz-example.pdf)。
2. 在首页选择“导入新题库”，选 PDF 并检查解析结果，命名保存。
3. 根据“需处理”提示补齐缺失或不确定的答案，正常题直接开始练习。
4. 选择顺序或随机练习；结束后查看统计和错题本，点击错题卡可单题复习。
5. 通过“备份与恢复”把资料保存到自己选择的位置。**备份不加密**，请妥善保管。

示例共 7 题，故意保留 1 道缺答案题以体验校对；[示例说明](examples/README.md)包含答案和期望解析结果。

当前没有经过脱敏核对的实际应用截图，因此暂不展示界面截图。[图标与设计资料](docs/design/app-branding.md)中的界面示意图是设计参考，不代表当前应用截图。

## PDF 与数据边界

- 支持有文字层的 PDF；不包含 OCR，不支持扫描件、图片题和公式题。复杂版式可能需要人工校正，不能保证任意 PDF 都能识别。
- 解析依赖题号、选项、括号答案、判断符号、下划线和“参考答案”等版式标记；识别不确定时提示处理，不编造答案。
- 填空按独立下划线识别，明确跨行证据才合并；合并后仅按中文顿号 `、` 拆为学习子空。
- 简答“文字匹配度”是字符 2/3-gram 重合程度，不理解语义，也不是正确率。
- PDF 经系统选择器授权后复制到应用私有目录；原文件不被删除。应用无账号同步，请自行导出备份。
- 备份为未加密 ZIP；文件提供方异常时可能留下未完成的导出文件，应用会提示失败。
- 数据库 schema 为 3，备份格式为 1。旧备份可由当前版导入；包含单题复习记录的新备份，不保证能导入版本 27 及更早应用。
- 28 项设备测试尚未运行；现有手机反馈不能扩写为每项异常场景均经独立验证。正式 APK 签名、覆盖升级与后续分发安排另行处理。

## 从源码构建

需要 **JDK 21**、Android SDK **platform 36 / build-tools 36.0.0**。项目附带 Gradle **8.13** wrapper；AGP **8.13.2**、Kotlin **2.2.21**。首次下载 Gradle/Maven 依赖需要联网，手机运行仍完全离线。

以下为 Windows PowerShell 示例，从项目根目录执行；按本机安装位置设置路径，只影响当前终端进程：

```powershell
$env:JAVA_HOME = '你的 JDK 21 绝对路径'
$env:ANDROID_HOME = '你的 Android SDK 绝对路径'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# 将构建缓存留在项目内，不更改系统配置
$env:GRADLE_USER_HOME = Join-Path $PWD 'work/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $PWD 'work/android-user-home'
& ./android/gradlew.bat --project-dir ./android --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

生成文件：`android/app/build/outputs/apk/debug/app-debug.apk`。本机已有 `android/local.properties` 时应确保 SDK 路径与当前环境一致；该文件不随源码上传。更详细的项目内依赖配置、设备测试命令见 [Android 开发说明](android/README.md)。

解析器的合成回归可独立运行；不需要提供私有样本。脚本首次运行会下载并校验 PDFBox 依赖，需要 JDK：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 -Jdk $env:JAVA_HOME
```

缺少 `local-inputs/sample.pdf` 时会跳过私有样本部分，合成测试仍执行。请勿为运行测试而上传自己的真实题库。

## 许可与第三方材料

项目原创代码与随附文档采用 [MIT 许可证](LICENSE)，署名为 `Copyright (c) 2026 Tixia contributors`。允许使用、修改、分发和商用，复制或分发软件或其实质部分时须保留版权声明与许可说明。软件按原样提供，不作担保；无需将修改版公开源码。许可原文与解释来源见 [MIT 说明](https://choosealicense.com/licenses/mit/)。

第三方代码、字体及资源仍遵循各自原许可证；项目 MIT 许可证不替代或重新授权第三方材料。

依赖、字体及构建工具的说明和许可原文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)与 [docs/licenses/](docs/licenses/)。正式 APK 分发前仍须按最终依赖包补齐随包许可材料。

## 目录

```text
android/           Kotlin + Jetpack Compose + Room 安卓工程、测试和 schema
tools/            构建脚本、共享 PDF 解析器与回归测试
examples/          自制 PDF、生成脚本和期望结果
docs/              需求、设计、交接、阶段归档与第三方许可
local-inputs/      本地真实样本，不上传
work/              本地依赖、临时文件、审查清单，不上传
outputs/           本地历史 APK 等，不上传
```

上传准备状态及剩余决定见 [上传前清单](docs/GITHUB_PREPARATION.md)。
