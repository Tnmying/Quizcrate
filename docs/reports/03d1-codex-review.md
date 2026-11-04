# 阶段 03D1 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。ZIP/JSON核心与新ID计划已实现，但练习数据的关联校验有两项业务问题。用户已授权验收后直接下指令，返工见 docs/tasks/03d1-revision-01.md，不进入03D2。

## [P2] 合法错题子集/随机练习的作答会被拒绝备份

BackupValidator.kt:236把历史snapshot.position与answer.position要求相等。前者是题目在原题库中的位置，后者是本轮会话数组的索引。PracticeRepository.startSession直接保留题目位置构建快照，record使用本轮索引保存作答，两者在错题子集/随机顺序中通常不同。

新增独立测试通过真实PracticeRepository.startWrongbook + skip，在内存Store中生成合法记录，再调用BackupWriter。实际：

```text
REVIEW actual subset: answerPosition=0 snapshotPosition=5
BackupException: 作答参考快照与作答不对应
```

写出失败，未到读取步骤。应按session.questions[answer.position]核对题目身份和当时快照，不能把原题库位置当作本轮索引。原正式备份样例恰好采用原题库顺序，因此未覆盖此路径。

## [P2] 缺少当前作答的反馈会话仍被当作有效备份接受

validateLibrary检查了已有答案是否有会话/索引，但没有反向核对会话phase/currentIndex所要求的答案是否齐全。独立反例：active + feedback + currentIndex=0、答案列表为空，BackupWriter导出且BackupReader读取成功：

```text
REVIEW missing feedback: acceptedAnswers=0
```

此状态不能作为有效可恢复进度：PracticeRepository.advance依赖answerAt当前索引，反馈页没有该记录也无法正常继续。需验证作答/反馈/完成阶段应有的连续作答位置集合，保留superseded当时阶段；缺失/超前记录应明确拒绝而非编造作答。

## 已核查与实际执行

- 确认F:\New project，读取需求/交接/任务/报告，检查BackupWriter/Reader/Validator/Json/RestorePlanner及正式测试；未改产品实现。
- 格式不打包DB/WAL；私有PDF路径不入包；读取不落盘/不写库；PDF流式核对头/实际大小/SHA；恢复计划重写ID/引用/幂等键。手机入口、真实Room事务、文件发布/恢复资源处理仍未接入，符合本轮边界。
- 离线 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest 成功，正式29类 **206 tests / 0 failures / 0 errors**；79任务中1执行/78 UP-TO-DATE。本轮测试实际重跑，构建为增量检查。
- 既有4项独立测试加新增BackupSessionReviewTest共6项实际编译执行，**6 tests / 2 failures**：既有4项通过；新增两项分别因合法记录被拒绝和非法进度被接受失败，不是环境/编译失败。
- 测试源码 work/codex-review-03d1/tests/BackupSessionReviewTest.kt；同目录reviewer.init.gradle仅临时加入测试源，未改产品Gradle。日志/结果：build.log、unit-summary.txt、review-tests.log、独立XML。
- 共享解析回归实际 **30 PASS / 0 FAIL**，含201题样本，parser-tests.log。默认Gradle结果目录最后是额外独立测试，206项正式汇总来自前次完整执行。

## 限制与后续

19项仪器测试仅编译未执行；JVM用合成内存Store，无真实Room/手机文件读写/大文件/设备性能验证。未改版本、未复制新APK；最新可用交付仍03C R1，本轮编译产物不代表手机备份已可用。

按用户授权直接下达03D1 R1，先修复关联及进度验证，再考虑03D2，不提交推送发布。
