# Third-party materials and licenses

[简体中文](THIRD_PARTY_NOTICES.md) | **English**

Reviewed: 2026-11-04, application version 28 / `0.4m-single-review`.

This document lists dependencies, build tools, examples, and resources. Each component retains its original license. Original Quizcrate code and accompanying documentation use the root [MIT License](LICENSE); third-party code, fonts, and resources retain their own terms.

## Application dependencies

| Component | Version/use | License and source |
| --- | --- | --- |
| AndroidX Activity, Compose, Lifecycle, Room, Core, and related components | Direct versions in `android/app/build.gradle.kts`; transitive versions in the metadata list below | Apache-2.0; [AndroidX license](https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt) |
| Kotlin standard library and kotlinx.coroutines | Kotlin 2.2.21; coroutines 1.10.2 | Apache-2.0; [coroutines license](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/LICENSE.txt) |
| PdfBox-Android | `com.tom-roush:pdfbox-android:2.0.27.0`, on-device PDF parsing | The cached Maven POM declares Apache-2.0; [upstream project](https://github.com/TomRoush/PdfBox-Android). Embedded fonts, CMaps, and other resources may carry additional notices |
| Bouncy Castle | `bcprov-jdk15to18`, `bcpkix-jdk15to18`, and `bcutil-jdk15to18` 1.72; PDFBox transitive dependencies | [Version-specific license](docs/licenses/Bouncy-Castle-1.72-LICENSE.html), including upstream copyright and permissive terms |
| Guava listenablefuture | 1.0; AndroidX transitive dependency | [Guava Apache-2.0 license](https://github.com/google/guava/blob/v26.0/LICENSE). Its cached POM has no direct license field, so upstream verification is recorded separately |

The runtime dependency report was generated with `:app:dependencies --configuration debugRuntimeClasspath --offline`. The [cached POM metadata](docs/licenses/runtime-license-metadata.tsv) lists 111 distinct coordinates, including BOM/constraint references; this is not a count of binaries inside the APK. Four POMs lack direct license fields, as noted above. A missing field does not mean a component is unlicensed.

The standard Apache 2.0 text is in [Apache-2.0.txt](docs/licenses/Apache-2.0.txt). Component-specific copyright and NOTICE statements still need to be retained; a generic license text does not replace them.

## Desktop parsing, build, and test tools

- Desktop PDFBox / FontBox **2.0.27** and commons-logging **1.2** are downloaded with checksum verification; local JARs are excluded from Git. LICENSE and NOTICE texts extracted from these JARs are in [docs/licenses/](docs/licenses/). The PDFBox license file also contains terms for bundled resources.
- The Gradle **8.13** wrapper includes `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, and its properties. The [full upstream Gradle license](docs/licenses/Gradle-8.13-LICENSE.txt) contains third-party terms for the Gradle distribution; this does not mean every listed component is part of the app APK.
- AGP **8.13.2**, Kotlin/Compose plugins **2.2.21**, and KSP **2.2.21-2.0.5** are fetched through Gradle. Local caches are not distributed, and the project license does not replace their upstream licenses.
- JUnit **4.13.2** is a test dependency; its [EPL 1.0 text](docs/licenses/JUnit-4.13.2-LICENSE.txt) is retained. Test dependencies are distinct from application runtime dependencies.
- `org.json:json:20240303` is a JVM test dependency; that upstream version declares [Public Domain](docs/licenses/JSON-java-20240303-LICENSE.txt).
- Regenerating the sample may use Python and fontTools. These tools and system fonts are not bundled with the source. Check the license of any replacement font before distributing a regenerated sample.

## Original sample and images

- `examples/offlinequiz-example.pdf` and its generator contain seven original questions, not private question-bank content.
- The PDF embeds a Noto Serif SC subset under SIL OFL 1.1. Preserve the [OFL text](docs/licenses/Noto-Serif-OFL-1.1.txt) and [copyright/license metadata from the local source font](docs/licenses/Noto-Serif-SC-font-metadata.txt). The generator derives a compatibility-cmap copy only in `work/`; the full font is not committed.
- The icon source is `docs/design/assets/tixia-icon-concept-v1.png`, used to prepare the launcher icon. See [development acknowledgments](ACKNOWLEDGMENTS.en.md) for assistance used during development.

## Before distributing an APK

The source includes a dependency index and major license materials. The current `android/app/build.gradle.kts` excludes some `META-INF/LICENSE/NOTICE` entries. Before distributing an APK, check the final dependencies and resources, include readable notices, and verify PdfBox-Android fonts/CMaps, transitive dependencies, and the final distribution package.
