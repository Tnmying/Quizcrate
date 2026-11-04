# 第三方材料与许可说明

**简体中文** | [English](THIRD_PARTY_NOTICES.en.md)

核对日期：2026-11-04，应用版本 29 / `0.1.0`。

本文记录项目使用的源码依赖、构建工具、示例和资源来源；各组件继续遵循原许可证。题匣项目原创代码与随附文档采用根目录 [MIT 许可证](LICENSE)；第三方代码、字体及资源继续遵循各自原许可证。

## 应用依赖

| 组件 | 当前版本/用途 | 许可与来源 |
| --- | --- | --- |
| AndroidX Activity、Compose、Lifecycle、Room、Core 等 | 直接版本见 `android/app/build.gradle.kts`，传递版本见下方元数据清单 | Apache-2.0；[AndroidX 原文](https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt) |
| Kotlin 标准库、kotlinx.coroutines | Kotlin 2.2.21；协程 1.10.2 | Apache-2.0；[协程原文](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/LICENSE.txt) |
| PdfBox-Android | `com.tom-roush:pdfbox-android:2.0.27.0`，手机 PDF 解析 | 缓存中该版本 Maven POM 声明 Apache-2.0；[上游项目](https://github.com/TomRoush/PdfBox-Android)。内部字体、CMap 等资源还需保留各自声明 |
| Bouncy Castle | `bcprov-jdk15to18`、`bcpkix-jdk15to18`、`bcutil-jdk15to18` 1.72，PDFBox 传递依赖 | [该版本许可原文](docs/licenses/Bouncy-Castle-1.72-LICENSE.html)，上游版权及宽松许可声明 |
| Guava listenablefuture | 1.0，AndroidX 传递依赖 | [Guava Apache-2.0 原文](https://github.com/google/guava/blob/v26.0/LICENSE)，该组件缓存 POM 未直接列出许可，保留为上游核对项 |

运行依赖报告通过 `:app:dependencies --configuration debugRuntimeClasspath --offline` 生成。图中 111 个去重坐标（包含 BOM/约束引用，不能等同于 111 个 APK 内二进制）及缓存 POM 原始许可字段见 [runtime-license-metadata.tsv](docs/licenses/runtime-license-metadata.tsv)。4 个坐标的 POM 未直接列出许可，已在上表说明；不能把 POM 缺字段解释成没有许可。

通用 Apache 2.0 原文保存在 [Apache-2.0.txt](docs/licenses/Apache-2.0.txt)。具体版权和 NOTICE 仍应按实际组件保留，不能只用这份通用文本代替全部声明。

## 桌面解析、构建与测试工具

- 桌面 PDFBox / FontBox **2.0.27**、commons-logging **1.2**：脚本按校验和下载，本地 JAR 不上传；从现有 JAR 原样提取的 LICENSE、NOTICE 位于 [docs/licenses/](docs/licenses/)。PDFBox 许可文件内还包括附带资源的第三方条款，不能只保留 Apache 标题。
- Gradle wrapper **8.13**：仓库保留 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar` 及 properties。保留 [Gradle 8.13 上游完整许可文件](docs/licenses/Gradle-8.13-LICENSE.txt)，其中包含 Gradle 发行版的第三方条款，不代表这些组件全部进入应用 APK。
- AGP **8.13.2**、Kotlin/Compose 插件 **2.2.21**、KSP **2.2.21-2.0.5**：通过 Gradle 获取，不将本机缓存上传；项目自身许可证不替代其上游许可。
- JUnit **4.13.2**：测试依赖，保留 [EPL 1.0 原文](docs/licenses/JUnit-4.13.2-LICENSE.txt)。测试依赖不同于应用运行依赖。
- `org.json:json:20240303`：JVM 测试依赖，该版本上游声明 [Public Domain](docs/licenses/JSON-java-20240303-LICENSE.txt)。
- 示例再生成可选用 Python + fontTools；这些工具及系统字体不随源码分发，使用其他字体重新生成示例时需重新核对其许可。

## 自制示例与图像

- `examples/offlinequiz-example.pdf` 和生成源码是项目自制 7 题示例，不包含私有真实题库。
- 示例 PDF 内嵌 Noto Serif SC 字体子集。字体使用 SIL OFL 1.1；保留 [OFL 原文](docs/licenses/Noto-Serif-OFL-1.1.txt)及[实际本机源字体的版权/许可元数据](docs/licenses/Noto-Serif-SC-font-metadata.txt)。生成脚本仅在 `work/` 派生兼容性 cmap 副本，完整字体文件不上传。
- 项目图标源文件位于 `docs/design/assets/tixia-icon-concept-v1.png`，用于制作启动图标。辅助工具的使用说明见 [开发与致谢](ACKNOWLEDGMENTS.md)。

## APK 随包许可材料

APK 的 `assets/third_party/` 保留项目 MIT、Apache 2.0、Bouncy Castle、PDFBox/FontBox 完整 LICENSE/NOTICE、实际 LiberationSans 字体元数据，以及从依赖 AAR/JAR 提取的许可原文。PDFBox 许可文件包含 AFM、CMap、字形映射和 Liberation 字体相关条款。部分重复的 `META-INF/LICENSE/NOTICE` 由打包配置排除，随包原文集中在上述 assets 目录，分发时应一并保留。
