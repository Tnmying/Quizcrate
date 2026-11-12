# 题匣（Quizcrate）

**简体中文** | [English](README.en.md)

题匣的开发目的是把学习资料变成随身题库。导入含参考答案的**文字型 PDF、TXT 或粘贴文本** 后，就能在安卓手机上离线解析、校对、练习、复习错题和保存学习记录。

无需登录、服务器、联网 AI 或运行时模型；应用不申请网络权限。支持 **Android 10（API 29）及以上**。

项目文档提供中英文版本；App 界面和自制示例 PDF 目前为中文。

## 当前状态

GitHub 已发布 **v0.3.0 · 混合导入与识别优化**预览版，提供正式签名 APK。新增 TXT／粘贴导入、混合题型自动识别、分区答案表和填空多种答案兼容。详见[更新对照](docs/releases/0.3.0.md)和[支持格式与模板](docs/IMPORT_FORMATS.md)。

**[下载 Android APK](https://github.com/Tnmying/Quizcrate/releases/download/v0.3.0/Quizcrate-0.3.0.apk)** · [查看发布说明](https://github.com/Tnmying/Quizcrate/releases/tag/v0.3.0)

在安卓手机上下载并打开 APK，按系统提示安装。需要 Android 10 及以上。

v0.2.0 换上了白蓝界面和冰蓝玻璃题匣图标，新增首页续刷、全局学习记录、上一题回看和题目搜索。在 Android 16 上从正式签名的 v0.1.0 覆盖安装后，自制题库和已结束的练习记录均保留。

本版发布后暂缓新增功能开发；现有使用范围与限制见下方说明。

## 功能

- **文字资料变题库**：导入 PDF、TXT 或粘贴文本，默认自动识别混合题型；预览、命名保存多个题库，支持单选、多选、判断、填空、简答。
- **按需校对**：正常题可直接练习，缺答案或有歧义的题保留处理提示；可编辑题干、选项、答案和填空位置，并查看来源 PDF 页或文本原文。
- **顺序与随机练习**：保存进度和草稿；刷题时可纠错，新轮次使用修改后的题，当前轮次与历史保留原快照。
- **跳过与偷看答案**：跳过直接进入下一题，不计有效题量、对错或正确率；偷看答案按错误记录，进入错题复习范围。
- **判题与自评**：单选/判断按参考答案，多选必须全部匹配；填空支持可接受答案；填空/简答可在心中作答后自评记得或没记住。简答文字匹配度仅辅助对照，不自动判对。
- **错题复习**：筛选待复习或全部历史错题，批量重练，或点击错题卡只复习这一题；单题复习保留原整轮进度与草稿，答对或自评记得后更新待复习状态，历史错误保留。
- **统计**：正确/记得与错误/没记住合并展示；累计有效题量与普通练习完成遍数分开。普通练习走完一轮计一遍，包含跳过；错题单题复习不增加整库遍数。
- **大题库定位**：按题干或选项搜索、拖动右侧滑块；打开题目后返回管理列表保留浏览位置。
- **本地备份与恢复**：导出题库、练习记录、草稿及可用来源 PDF／文本；恢复前预览摘要，确认为新副本导入，不覆盖原题库。

## 支持哪些文件与题目格式

| 导入方式 | 支持范围 |
| --- | --- |
| PDF（`.pdf`） | 有文字层、可提取文字的 PDF；推荐单栏排版 |
| TXT（`.txt`） | UTF-8、带 BOM 的 UTF-16；高级选项可手动选择 GB18030 |
| 粘贴文本 | 直接粘贴已有题目与参考答案，无需先保存文件 |

一份资料可以混合**单选、多选、判断、填空、简答**，默认自动识别，无需指定整库题型。推荐题号开头、选项逐行排列、答案以“答案：”或“正确答案：”标注；题型标记可选。支持逐题答案和按题号对应的答案表；同一空的多种答案用 `\` 分隔，不同空用 `||` 分隔。

TXT／粘贴内容单次上限 **512 KiB、20000 行**（解码后的 UTF-8 内容也受大小限制）。资料应已有题目及参考答案，不会从普通教材自动生成题目。

**暂不支持**扫描／加密 PDF、照片、图片题、公式题、OCR，以及直接导入 Word（`.doc/.docx`）、Excel（`.xls/.xlsx`）、CSV、PPT。可将已有文字题整理成 TXT 后导入；复杂双栏或表格仍可能需要人工校对。

查看[完整格式规范与五种题型示例](docs/IMPORT_FORMATS.md)，或直接使用[五题文本模板](examples/offlinequiz-import-template.txt)。

## 开始使用

1. 准备含参考答案的文字型 PDF、TXT 或文本，可先用[五题模板](examples/offlinequiz-import-template.txt)或 [7 题 PDF](examples/offlinequiz-example.pdf)。
2. 在「学习」页进入导入，选择文件或粘贴文本，无需指定整库题型；可查看“格式说明”，检查预览后命名保存。
3. 根据“需处理”提示补齐缺失或不确定的答案，正常题直接开始练习。
4. 选择顺序或随机练习；结束后查看统计和错题本，点击错题卡可单题复习。
5. 通过「我的」里的「备份与恢复」把资料保存到自己选择的位置。**备份不加密**，请妥善保管。

示例共 7 题，故意保留 1 道缺答案题以体验校对；[示例说明](examples/README.md)包含答案和期望解析结果。

## 导入与数据边界

- 支持有文字层的 PDF；不包含 OCR，不支持扫描件、图片题和公式题。复杂版式可能需要人工校正，不能保证任意 PDF 都能识别。
- 解析依赖题号、选项、括号答案、判断符号、下划线和“参考答案”等版式标记；识别不确定时提示处理，不编造答案。
- 填空支持 PDF 下划线及显式空位；同一空可用 `\` 列出可接受写法，不同空用 `||` 分隔，参见[导入规范](docs/IMPORT_FORMATS.md)。
- 简答“文字匹配度”是字符 2/3-gram 重合程度，不理解语义，也不是正确率。
- PDF 经系统选择器授权后复制到应用私有目录；原文件不被删除。应用无账号同步，请自行导出备份。
- 备份为未加密 ZIP；文件提供方异常时可能留下未完成的导出文件，应用会提示失败。
- 数据库 schema 为 4，备份格式为 2，兼容旧格式 1 备份。文本来源随新备份保存，新备份不能由 v0.2.0 等旧版读取。升级前请导出备份，不要卸载正式版；同包名、同签名 APK 可覆盖升级，测试包数据独立。
- 源码与安装包分别提供，正式签名 APK 可从 [GitHub Releases](https://github.com/Tnmying/Quizcrate/releases) 下载。

## 从源码构建

当前应用源码已更新到 v0.3.0（内部版本号 31）。正式安装包使用维护者的私有签名；自行构建的 Debug 包不能直接覆盖正式签名版。

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

生成文件：`android/app/build/outputs/apk/debug/app-debug.apk`。本机已有 `android/local.properties` 时应确保 SDK 路径与当前环境一致。更详细的项目内依赖配置、设备测试命令见 [Android 开发说明](android/README.md)。

解析器的回归测试使用合成题目，可以单独运行。脚本需要 JDK，首次运行会下载并校验 PDFBox 依赖。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 -Jdk $env:JAVA_HOME
```

## 许可与第三方材料

项目原创代码与随附文档已按 [MIT 许可证](LICENSE) 开源，署名为 `Copyright (c) 2026 Tnmying`。允许使用、修改、分发和商用，复制或分发软件或其实质部分时须保留版权声明与许可说明。软件按原样提供，不作担保；无需将修改版公开源码。许可原文与解释来源见 [MIT 说明](https://choosealicense.com/licenses/mit/)。

第三方代码、字体及资源仍遵循各自原许可证；项目 MIT 许可证不替代或重新授权第三方材料。

依赖、字体及构建工具的说明和许可原文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)与 [docs/licenses/](docs/licenses/)。APK 内的 `assets/third_party/` 保留了随包许可与版权声明。

## 目录

```text
android/           Kotlin + Jetpack Compose + Room 安卓工程、测试和 schema
tools/            构建脚本、共享 PDF 解析器与回归测试
examples/          自制 PDF、生成脚本和期望结果
docs/              架构说明、图标素材与第三方许可
```

[架构说明](docs/ARCHITECTURE.md)记录了技术选型和数据设计。[开发与致谢](ACKNOWLEDGMENTS.md)列出了开发中用到的工具。
