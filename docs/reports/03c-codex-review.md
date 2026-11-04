# 阶段 03C 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。匹配算法主体、界面接入及自评隔离已实现，数字中的标点被无条件删除造成错误等同，需集中返工；另需落实长文预处理的工作量边界。用户授权验收后直接下指令，任务 docs/tasks/03c-revision-01.md。

## 已验证的有效实现

- 字符2/3-gram多重集Dice、两侧长度不足规则、单字符回退、Unicode码点与全半角/大小写处理已接入。否定字词不删；同义低分/反义高分如实展示局限，不自动判对。
- ViewModel只在展开后/自评反馈时计算，使用会话固定快照；切题与恢复刷新，空输入不显示0分或100%。UI实际使用结果，匹配不进入判题、统计或掌握口径。
- 176项正式测试与两份既有冲突/取消独立测试均通过。

## [P2] 数字标点被删除，1.5与15/1:2与12显示完全一致

ShortAnswerTextMatch.kt 的PUNCTUATION包含句点和冒号，normalizeToCodePoints不检查上下文直接删除。实际独立测试调用原实现：

- 输入“温度为15摄氏度”，参考“温度为1.5摄氏度”：score=1.0，percent=100。
- 输入“比例为12”，参考“比例为1:2”：score=1.0，percent=100。

work/codex-review-03c/tests/ShortAnswerNumericReviewTest.kt 两项业务断言失败；测试成功编译运行，非环境失败。这不是n-gram不能理解语义的已知局限，而是预处理主动抹掉了数值表达的区别，应保留小数点/比例等有意义符号。普通句末标点仍可保守忽略。

## 长文预处理边界仍未落实

match先对两侧完整调用normalizeToCodePoints，再copyOf(2000)。规范化先构建全长StringBuilder、lowercase副本、ArrayList<Int>和IntArray；2000上限只限制随后gram比较，不能支持注释/报告所称整体有界内存。refreshShortAnswerMatch在主线程同步调用，超长输入/导入参考仍先完整分配与遍历。源码可直接确认；本轮未做手机卡顿或OOM实测。返工需让预处理也受明确限制，或把大文本计算移出主线程并隔离迟到结果，保留可自评/跳过和截断提示。

## Codex实际检查

- 目录 F:\New project；读取需求/任务/交接/报告，核查算法、ViewModel、UI及正式测试；未改产品实现。
- 离线执行 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest，成功，79任务中1执行/78 UP-TO-DATE。测试实际执行：27类176 tests / 0 failures / 0 errors；构建为增量检查，未声称本轮强制重建。
- 用work下init临时加入既有两份独立测试及新增数值测试，实际4 tests / 2 failures：既有两份通过，数值两项失败。结果XML及review-tests.log存work/codex-review-03c/。默认结果目录最后是额外测试，正式汇总保存在unit-summary.txt。
- 共享解析回归实际30 PASS / 0 FAIL，含201题样本，日志parser-tests.log。
- APK与当前构建产物SHA一致：999B794C8C116040325A35D68E5C8D899F7BAF0E1AB41EAD5472ACAB4215E715；versionCode12 / 0.3c-short-answer；apksigner通过，原证书；aapt2仅列应用内部权限，无INTERNET/存储权限。

## 限制与后续

19项仪器测试仅编译未执行，真实Room/手机触控/旋转/强杀/长文手感仍待设备。DSH真机说明中“展开后修改输入”与当前输入框展开后锁定不一致，返工同步更正说明即可，不要求改变既有锁定行为。

直接下达03C R1，保留有效实现，不进入备份或发布，不提交推送。
