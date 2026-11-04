# 04B：Codex 直接实施名称、图标与暖色界面

日期：2026-11-04。实施者：Codex。本报告为实施与自检记录，不是另一个独立评审者的验收结论。

## 授权与范围

用户在当前聊天明确要求 Codex 亲自将应用名称、图标与界面改为此前认可的暖色示意风格，按钮橙色更淡；首页不加品牌图标/标题，直接显示“我的题库”。用户原文“我的图库”按当前刷题应用上下文落实为既有名称“我的题库”。同时要求详细记录，方便 DSH（大肥鱼）接手。

本次授权覆盖此前“仅收集、不改代码”的限制，但仅针对名称、图标、配色和相关排版。其他 BACKLOG（校对状态、题目管理页、按需纠错、统计合并和遍数规则）仍未实施；没有派发 DSH 新任务。

## 已完成

- 应用显示名称：离线刷题 → **题匣**。
- 启动图标：接入用户选择的第一版橙黄图标，保留原始素材；制作 Android 自适应图标，普通与圆形图标使用同一资源。原图只做确定性的打包缩放，不重新生成或重绘。
- 统一暖白背景、深灰正文、暖灰次要文字、白色题卡与较浅的杏橙按钮。主操作用深色文字，文字链接/单选控件继续用深橙保证对比度。正确/记得反馈用淡绿，错误/没记住用淡红。
- 首页只显示“我的题库”，没有品牌区或额外图标；数量与备份入口一行，导入按钮独占下一行，题库卡片使用白底细边框、增加间距。原有确认状态、来源页数、时间与重命名/打开入口保留。
- 刷题页：白色题干卡、独立选项卡片、选中时浅橙底与橙色边框；提交按钮占满一行，下面是“跳过本题”。单选/多选/判断选项的整行点击由一个父级事件处理，保留禁用保护及无障碍角色；内部单选框/复选框不再重复持有点击回调。
- 包版本：`versionCode 17` / `versionName 0.4b-warm-branding`，便于区分旧版测试包。

## DSH 需要知道的文件改动

以下路径均相对项目根目录。

| 文件 | 改动 |
| --- | --- |
| `android/app/build.gradle.kts` | 版本号 16 → 17；版本名更新；未增加依赖 |
| `android/app/src/main/AndroidManifest.xml` | 增加 icon/roundIcon 引用 |
| `android/app/src/main/res/values/strings.xml` | app_name 改为“题匣” |
| `android/app/src/main/res/values/themes.xml` | 原生窗口、状态栏、导航栏暖白色与深色系统图标；原生强调色深橙 |
| `android/app/src/main/res/values/colors.xml` | 新增启动图标背景色 |
| `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 新增自适应启动图标定义（minSdk 29，无需低版本资源） |
| `android/app/src/main/res/drawable/ic_launcher_foreground.xml` | 前景图安全内缩 12dp，保留图标主体 |
| `android/app/src/main/res/drawable-nodpi/ic_launcher_art.png` | 由第一版原图缩放的 432 × 432 PNG |
| `android/app/src/main/java/com/offlinequiz/app/Theme.kt` | 完整暖色 Material 配色、圆角；保留原主题函数名 |
| `android/app/src/main/java/com/offlinequiz/app/ui/WarmButton.kt` | 新增浅杏橙按钮组件，深色文字，最小高度 48dp，统一禁用默认行为 |
| `…/ui/HomeScreen.kt` | 首页标题、导入按钮位置、卡片外观、间距；使用 WarmButton |
| `…/ui/PracticeScreen.kt` | 题干/选项外观、整行选择语义、提交/跳过布局、反馈颜色；使用 WarmButton |
| `…/ui/QuestionHistoryScreen.kt` | 使用 WarmButton；正确/记得历史反馈改淡绿 |
| `…/ui/BackupScreen.kt`、`ImportScreen.kt`、`LibraryDetailScreen.kt`、`QuestionEditScreen.kt`、`WrongbookScreen.kt` | 仅将 Material Button 导入换为 `WarmButton as Button`，不改各自流程和布局 |
| `tools/prepare-branding-assets.ps1` | 新增可复现图标打包缩放脚本，输出项目内资源 |
| `docs/design/assets/warm-ui-concept-v1.png` | 保存此前用户认可的 UI 示意图，仅为设计参考，不是运行截图 |
| `docs/design/app-branding.md` | 补充已接入与设计参考状态，保留最初素材记录 |
| `docs/PROJECT_BRIEF.md`、`DSH_HANDOFF.md`、`CODEX_HANDOFF.md`、`STATUS.md` | 补充本次授权、实施范围和交接入口，保留历史记录 |
| `README.md`、`android/README.md` | 更新应用名、当前版本、测试包路径与状态 |

新增按钮通过导入别名复用原有调用，避免逐个改动业务回调。若后续增加实心主按钮，请优先使用 WarmButton；文字按钮仍用 Material TextButton。没有修改包名 `com.offlinequiz.app`、签名设置、Room schema 3、数据库名/迁移、解析器、判题/统计逻辑、备份格式和 ViewModel。

## 配色值

| 用途 | 色值 |
| --- | --- |
| 背景 | `#FFF9F2` |
| 白色题卡 | `#FFFFFF` |
| 正文 / 次要文字 | `#302A26` / `#6F6359` |
| 按钮底 / 按钮文字 | `#F6B575` / `#482A19` |
| 链接、选择控件、选中边框 | `#A64B16` |
| 选中背景 | `#FFEBD8` |
| 普通边框 | `#E4D7CA` |
| 正确反馈底 / 错误反馈底 | `#E2F0E3` / `#FFDAD6` |

## 实际验证

- 最终源码执行 `tools/android-build.ps1 -ApkName tixia-04b-warm-debug.apk`：`:app:testDebugUnitTest` 与 `:app:assembleDebug` 均成功。最终测试 XML 汇总 **236 项，0 失败、0 错误、0 跳过**。最后一次 UI 修改之后已重新编译并重跑测试；最终类文件时间晚于源码。
- 资源 XML 均实际解析通过；aapt2 检查已打包 APK：名称“题匣”，包名不变，版本 17，minSdk 29 / targetSdk 36。
- apksigner 校验新 APK 成功；新旧 APK 的签名证书输出一致。未安装或执行实际覆盖升级。
- APK **246 条目，0 个 PDF**，无 INTERNET 或广泛存储权限。
- 实际计算主要文字对比度：按钮 **7.27:1**、正文 **13.53:1**、次要文字 **5.57:1**、文字操作 **5.52:1**、选中项文字 **12.21:1**，均高于 4.5:1。这仅是所列颜色组合的数值验证，不代替整页无障碍验收。
- 已生成并目视检查圆形/圆角方形启动图标裁切预览，主体完整；该图为本地几何模拟，并非手机启动器截图。
- 实际运行 adb devices，设备列表为空。没有连接手机或启动模拟器，没有安装新工具。
- 构建仍有既有 SDK XML 版本 3/4 提示，未阻止测试/构建；未为此升级 SDK 或修改系统配置。

产物：`outputs/apk/tixia-04b-warm-debug.apk`，**45,040,245 bytes**。

SHA-256：`B9CC4503492BB60705CC3D01AB47DA1C976C63169992DCC2C8F2226B527B961D`。

历史 APK 原样保留。本次没有提交、推送、创建 Release 或公开发布。

## 尚未验证与阻碍

- 新版外观尚未在用户手机上实际查看；长题目、字体放大、窄屏、多选/判断触控、系统遮罩、旋转及系统栏显示仍需手机验看。
- 22 项仪器测试未执行；本次没有新增或声称通过仪器测试，也没有把实施者自检等同独立验收。
- 实际覆盖安装及保留既有题库的结果尚未真机验证；本次未打开真实用户库试恢复。
- 其他 BACKLOG 与先前设备验收未完成项保持原状态。当前无构建阻碍；手机验证受限于未连接设备。

建议用户手机只先看三处：桌面名称/图标；首页暖色与导入入口；打开现有题库完成一道练习，看选中框与提交/跳过布局。复杂验收仍按既有清单另行处理。

## 留档与恢复参考

- 修改前快照：`work/branding-20261104/before/`，包含原 main 源码/资源、构建配置与所改的既有交接文档。
- 文本逐文件差异：`work/branding-20261104/source-changes.diff`；文件哈希/测试/颜色/包验证：`verification.json`；验证脚本：`verify_branding.py`。
- 最终构建日志：`work/branding-20261104/final-build.log`；初轮日志 `build.log` 不是最终包依据。
- 包检查：同目录 `apk-badging.txt`、`signature-new.txt`、`signature-previous.txt`。
- 裁切预览：`work/branding-20261104/icon-mask-preview.png`。
- 图标原图：`docs/design/assets/tixia-icon-concept-v1.png`，原始 SHA-256 保持 `34C66148A4070EF12ED0EAF28414AC84D9AFE4DF1D432C34D4B4E1A43E236583`；打包缩放图 SHA-256：`D22FCBC39BCA600531B3BBF064B6376F08923432C0ABC1AA3AF99BCD827D3E50`。
- 若需返工，先对照差异和当前文件，避免覆盖后续 DSH 编辑；本次没有自动恢复或删除任何旧文件。

本阶段实施和自检完成后停止，等待用户手机外观反馈或后续评审。
