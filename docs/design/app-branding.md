# 应用名称与图标素材

记录日期：2026-11-04。用户确认将名称和选中的图标保存到项目，供后续接入；本次不是新的开发任务。

同日后续实施更新：用户已进一步授权 Codex 接入名称、图标及暖色 UI，详见 `../reports/04b-codex-branding-implementation.md`。以下“尚未接入/未构建”描述的是最初保存素材时的历史状态。应用内首页不放图标和“题匣”品牌标题，仅显示“我的题库”。暖色示意图保存于 `assets/warm-ui-concept-v1.png`，仅为设计参考，不是应用运行截图；实际按钮较示意图更浅，使用深色文字保证清晰。

## 名称

- 中文应用名：**题匣**。
- 项目技术代号：`OfflineQuiz`，现有包名、工程名及 APK 文件名暂不调整。
- 可选介绍文案：把你的文档，变成随身题库。（建议文案，尚未单独确认。）
- 名称的同名应用及商标情况尚未核查。

## 选定图标

![题匣图标第一版](assets/tixia-icon-concept-v1.png)

- 文件：`docs/design/assets/tixia-icon-concept-v1.png`。
- 用户选择最初生成的版本：橙黄收纳匣、奶白题卡与橙色勾，浅奶油色背景，无文字，简洁圆润。
- 用户认为多色翻盖版更乱，确认回到第一版；不要采用多色翻盖版，也不要仅为突出“匣子”增加复杂结构。
- 当前为 AI 生成的栅格设计素材，通过内置 image_gen 工具生成；不是已接入的 Android 启动图标资源。
- 实际文件规格：PNG，1254 × 1254 像素，不透明背景。
- SHA-256：`34C66148A4070EF12ED0EAF28414AC84D9AFE4DF1D432C34D4B4E1A43E236583`。

## 后续接入范围

后续开发任务下达后，再设置应用显示名称、制作 Android 自适应图标前景/背景及所需启动图标资源，并检查圆形、圆角矩形遮罩和手机桌面小尺寸显示效果。当前不改包名、签名、备份格式或历史 APK。

## 本次完成及验证

- 已将用户选择的第一版原图复制到项目；源文件与项目内副本 SHA-256 一致。
- 已实际读取图片，确认 PNG 格式与上述像素尺寸。
- 未修改应用代码、未构建新 APK、未替换实际桌面图标；手机效果尚未验证。
- 无素材保存阻碍；工作目录不是 Git 仓库，未执行提交、推送或发布。

## 生成提示词记录

工具：内置 image_gen；首版原始提示词如下（提示中的尺寸为生成请求，实际输出尺寸以上述验证为准）：

```text
Use case: logo-brand. Asset type: single square Android app launcher icon concept preview for a personal offline PDF quiz app, working Chinese name 题匣. Create ONE polished icon, not a sheet or mockup. Style: minimalist rounded vector-like shapes, warm, friendly, crisp and timeless. Subject: a small open orange question-card box, with two cream-white study cards emerging from it; front card has one bold amber-orange check mark, rear card mostly blank. A distinctive compact silhouette, slightly softened corners, restrained very subtle depth, mostly flat graphic design. Palette: warm tangerine orange, golden yellow, pale cream. Background: solid warm pale cream filling entire square to the edges, opaque. Center the complete box and cards within the central 65 percent of the canvas with generous safe margin for Android circular/squircle cropping. Strong contrast readable at tiny launcher size. No letters, no Chinese characters, no text, no numbers, no Android robot, no graduation cap, no books, no sparkles, no face, no watermark, no border, no phone mockup. Output a single 1024x1024 square artwork.
```
