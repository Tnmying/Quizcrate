# 阶段 03A 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。练习页首次加载存在阻断问题，不能验收通过。用户本轮明确要求验收后先停止，因此**未给 DSH 下达返工或下一阶段任务**，等待用户安排。

## 必须修复的发现

### [P1] 首次进入练习永远停在加载中

文件：`android/app/src/main/java/com/offlinequiz/app/viewmodel/PracticeViewModel.kt`，第 39、57–60 行。

- 新建 ViewModel 时 `loading` 初始化为 `true`。
- `load(sessionId)` 在启动加载协程前执行 `if (loading || saving || exiting) return`。
- 首次调用必然在这里返回，`repository.loadState` 一次也不会执行，`loading` 没有机会改为 false。
- `PracticeScreen` 第 57 行通过 LaunchedEffect 调用此方法，第 84 行在 loading=true 时只显示进度圈；因而顺序、新随机会话和重启后的继续练习首次进入都会受影响。返回重进一个全新 ViewModel 也不能解决。

独立动态复现：在 `work/codex-review-03a/tests/PracticeEntryReviewTest.kt` 构造**实际 PracticeViewModel**，仅用反射绕过 Android Application/数据库初始化并注入测试仓库；设置可控主调度器，调用首次 load 并执行所有待运行协程。结果：

```text
REVIEW initial load: sessionReads=0 loading=true error=null
First load must reach the repository expected:<1> but was:<0>
1 test completed, 1 failed
```

该测试实际运行，因上述业务断言失败（不是编译或测试环境失败），Gradle 退出码 1。它使用缺失会话验证错误出口，但由于根本没有访问仓库，该缺陷同样发生在有效会话上。

建议后续修复时将“初始展示加载中”与“已有加载任务正在执行”分开，或用实际 loadJob 状态防重入；同时覆盖首次成功、首次失败、失败重试、重复加载和恢复反馈。此处是验收建议，**本轮没有分派修复任务，也没有改产品代码**。

## 实际验证

- 确认根目录并读取需求/任务/报告，检查练习模型、资格过滤、判题、仓库、事务 DAO、草稿 revision、ViewModel、导航与练习界面。
- 独立离线强制重建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：成功，79 个任务全部执行；原有 JUnit **72 tests / 0 failures / 0 errors**。
- 共享 PDF 回归独立重跑：**30 PASS / 0 FAIL**，含真实样本 201 题。
- 原有测试覆盖判题与仓库，但没有 PracticeViewModel 首次入口测试；因此其全部通过不能证明页面可进入。
- 额外验收测试通过 work/ 下的 Gradle init 脚本临时加入测试源，不修改正式 Gradle 或 src 文件。第一次尝试修正了验收脚本本身的 Gradle 项目引用及测试编译可见性问题；最终测试已成功编译执行，得到上面的明确业务失败。
- 日志：`work/codex-review-03a/build.log`、`parser-tests.log`、`entry-test.log`、`entry-test-result.xml`。运行入口测试后 Gradle 默认 HTML/XML 测试目录显示的是这项额外失败测试，原 72 项成功来自此前完整强制构建。
- 交付 APK 与独立重建 SHA-256 一致：`9555F9C707D962A3D7B99BF587C9763C5661DAA499851F774B72A430BC6989A6`，43,264,793 字节，调试签名沿用；aapt2 检查无 INTERNET/存储权限。

## 尚未验证及边界

- 无安卓设备，13 个仪器测试只编译未运行。Room v1→v2 迁移、真实持久化及 Compose 完整流程未做设备实测；代码中为新增表的显式迁移，保留旧 schema，未发现 destructive fallback，但不能将静态检查当作升级实测。
- 本轮入口动态复现属于 JVM 测试，不是真机截图/操作；不存在“已在手机观察到转圈”的声明。
- 未进入后续开发，未修产品代码，未提交/推送/发布。用户要求验收后等待，故不创建下一任务、不通过 DSH UI 发消息。
