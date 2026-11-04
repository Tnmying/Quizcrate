# 02B R1 真机检查清单

日期：2026-11-03。目标设备：RMX3800 / Android 16 / realme UI 7.0。

本文件用于验收 `outputs/apk/offlinequiz-02b-r1-debug.apk`。当前环境没有连接安卓设备，下列**仪器测试和手工步骤均未执行**，不能把“已编译测试 APK”当成真机通过。

## 安装

```powershell
adb install -r "F:\New project\outputs\apk\offlinequiz-02b-r1-debug.apk"
```

- 期望：versionCode 5、versionName `0.2b-r1-library-edit`、applicationId `com.offlinequiz.app`，沿用 02B/02A 调试签名。
- 期望：应用详情中没有 INTERNET、存储、相机权限。

## 仪器测试（需要设备）

本机没有设备，以下 7 个测试只编译了测试 APK，未运行：

- `QuizDatabaseTest.questionsAndCountsAreIsolatedPerLibrary`
- `QuizDatabaseTest.confirmationUpdateIsPersistedAndTouchesLibrary`
- `QuizDatabaseTest.failedTransactionRollsBackLibraryAndQuestions`
- `QuizDatabaseTest.savedLibrarySurvivesDatabaseReopen`
- `PdfStorageDeviceTest.copyFromContentResolverStoresPrivateCopyAndDeleteRemovesIt`
- `PdfStorageDeviceTest.nonPdfInputFailsWithoutLeavingBrokenCopy`
- `PdfStorageDeviceTest.rendersOnlyTheRequestedPageAndRejectsOutOfRangePage`

有设备后运行：

```powershell
$env:JAVA_HOME='F:\New project\work\jdk-21.0.13+9'
$env:ANDROID_HOME='F:\New project\work\android-sdk'
$env:ANDROID_USER_HOME='F:\New project\work\android-user-home'
$env:GRADLE_USER_HOME='F:\New project\work\gradle-user-home'
& "F:\New project\android\gradlew.bat" --project-dir "F:\New project\android" :app:connectedDebugAndroidTest
```

## 手工主流程

1. 冷启动应用。
   - 期望：首页“我的题库”，空列表提示；没有“阶段 02A/02B”等开发标记。
2. 点“导入新题库”，用系统选择器选择真实样本 PDF（仅本机）。
   - 期望：后台解析，显示总题数约 201、来源页数、下划线计数、题型分布、待校对数量；可修改题库名称。
3. 点“保存到本地”。
   - 期望：进入题库详情，显示名称、题量、待校对/已确认、来源文件和“原 PDF 私有副本可用”。
4. 返回首页。
   - 期望：出现新题库卡片，题量/待校对数量正确。
5. 强制停止应用并重新打开。
   - 期望：题库、名称、题量、待校对数量仍在；无需重新导入。
6. 打开题库 → 任选一题 → 修改题干/选项/答案。
   - 期望：可以编辑；点“仅保存”后提示已保存，题目状态仍为待校对（如果原本未确认）。
7. 再点“保存并确认”。
   - 期望：提示已保存并标记为已确认；详情页已确认数量 +1、待校对数量 -1。
8. 强制停止并重开，进入同一题。
   - 期望：修改内容和确认状态仍在。
9. 修改一道已确认题并“仅保存”。
   - 期望：该题变回待校对（编辑后需重新确认），修改内容保留。
10. 点“查看原页”。
    - 期望：用系统 PdfRenderer 显示题目来源页；多来源页可用上一页/下一页；关闭后不崩溃。再次打开仍可渲染。
11. 修改题库名并重开应用。
    - 期望：新名称保留，题目不变。

## 填空合并专项（R1 重点）

1. 构造/打开 `前____、____后` 的填空，答案 `[甲, 乙]`，给两个空分别填可接受答案 `[A]`、`[B]`，点第二个空的“并入上一空”。
   - 期望：题干变成 `前____后`；参考答案 `甲、乙`；合并空的可接受答案为空；提示可接受答案已清空，需重新设置；不得残留 `A/B` 半答案。
2. 构造三空 `前____、____；____后`，答案 `[甲,乙,丙]`，合并中间空。
   - 期望：题干保留第三个空及其分隔；答案顺序和第三个空的可接受答案不变。
3. 构造 `前____ ____后`，合并。
   - 期望：题干 `前____后`；参考答案 `甲 乙`，用空格连接，不凭空加顿号。
4. 构造 `____和____` 或 `____后又____`，合并。
   - 期望：拒绝，提示中间有正文、需手动编辑；题干、答案、可接受答案全部不变。
5. 构造题干占位符数与答案组数不一致，合并。
   - 期望：拒绝，提示先校对；所有内容不变。
6. 构造其中一个空答案为空，合并。
   - 期望：拒绝，提示先补全；所有内容不变。

## 保存期间编辑专项（R1 重点）

1. 打开一题，修改题干，点“仅保存”（或“保存并确认”）。
2. 在保存进行中快速尝试修改题干、选项、判断答案、填空答案、可接受答案、添加/删除/合并空。
   - 期望：控件处于禁用状态，无法输入或点击；返回按钮也不可点。
3. 如果保存较慢，观察保存结束后：
   - 期望：保留的是保存开始时的内容；保存期间没有偷偷接受的编辑被回读覆盖；有失败反馈时原编辑内容仍在。
4. 保存失败（可低存储模拟）后：
   - 期望：提示失败，当前编辑内容保留，可继续修改重试。

## 结构校验专项

逐项确认保存被拒绝且不破坏旧内容，错误为中文：

- 空题干。
- 两个选项标记相同（如 A/A）。
- 单选题选两个正确项（导入异常题可验证）。
- 多选题只选一个正确项。
- 正确项在选项列表里不存在（例如手改选项标记后）。
- 填空空位数与答案组数不一致。
- 简答题参考答案清空。

## 多题库与失败路径

1. 连续导入两次同一 PDF（或两个不同 PDF），分别命名。
   - 期望：出现两个独立题库；题号相同也不互相覆盖；题目数量/确认状态互不影响。
2. 导入预览阶段直接返回首页。
   - 期望：不产生半成品题库；旧题库不变。
3. 保存题库时如果 PDF 私有副本无法写入（可用低存储/撤销文件权限模拟）：
   - 期望：显示中文失败原因；首页不出现坏题库；旧题库不受影响。
4. 保存题目失败（可用低存储模拟）：
   - 期望：提示保存失败，题目旧内容不变，不出现半成品状态。

## 未覆盖/后续阶段

- 顺序/随机练习、判分、错题本、统计、简答 n-gram、备份导入导出不在本阶段。
- 扫描件、图片题、公式题不支持。
- 旋转/多栏/超大 PDF 版式未验证。
- 本阶段不要求保留未保存的导入预览或编辑草稿；应用被系统杀死后，重新打开恢复的是最后一次成功保存的内容。
