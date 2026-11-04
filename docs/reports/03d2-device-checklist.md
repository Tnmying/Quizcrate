# 03D2 真机检查清单：手机备份/恢复新副本

日期：2026-11-03。目标设备：RMX3800 / Android 16 / realme UI 7.0。验收包：`outputs/apk/offlinequiz-03d2-r2-debug.apk`（versionCode 16 / `0.3d2-r2-mobile-backup`，SHA-256 `620165237995BCDA53824B9E4C4681ABE2EBA40E30032F7E2DC2A308E78ED468`）。

本机无设备，以下步骤**均未执行**；22 个仪器测试仅编译。不能把 JVM/构建通过当作真机通过。

## 安装/权限

1. `adb install -r "F:\New project\outputs\apk\offlinequiz-03d2-r2-debug.apk"`。
   - 期望：versionCode 14、同一调试签名、applicationId `com.offlinequiz.app`；旧题库/练习保留。
   - 期望：应用详情无 INTERNET、无“所有文件访问”/存储/相机权限。
2. 首页应出现“备份与恢复”按钮；进入后显示“文件含题库和练习记录，未加密”“恢复作为新副本导入，现有题库不变”。

## 导出

1. 准备至少两个题库：五题型、待校对题、进行中/已完成/被替换会话、随机/错题子集、草稿/反馈、至少一个有 PDF、一个无 PDF。
2. 点“导出全部题库与练习记录” → 系统保存对话框选 Downloads/自制文件名 → 保存。
   - 期望：先显示生成/校验中，完成后提示“备份已导出”；文件为非空 ZIP。
   - 期望：无“广泛存储权限”弹窗。
3. 取消保存对话框：应回到空闲，不显示成功，不产生目标文件。
4. 导出过程中按返回：应取消/离开；若系统目标已留下部分文件，应用不得显示成功；应明确提示未完成（如再次导出失败场景）。

## 恢复预览/确认

1. 点“从备份文件恢复” → 选刚导出的 ZIP。
   - 期望：后台读取/校验；显示题库/题目/练习记录/作答数、待校对数、PDF 包含/缺失；提示“作为新副本导入，现有题库不变”。
2. 预览时点“取消”：回到空闲；首页题库数量不变。
3. 预览时按系统返回/顶部返回：同上，不写库。
4. 校验大文件或损坏 ZIP：显示中文失败/取消；不得出现新题库、不得改动原库。
5. 重复点击“确认导入为新副本”：只提交一次；按钮在提交中不可用。

## 提交/结果

1. 确认导入：
   - 期望：显示“正在写入”；此期间顶部返回/系统返回被拦截并提示“正在写入，不能离开”。
   - 期望：成功后提示“已作为新副本导入 N 个题库”，可“打开新题库”。
2. 打开新题库：题目数量/题型/待校对/修改历史与备份一致；原题库仍在且未变。
3. 检查新题库：
   - 活动练习草稿/展开状态可继续；反馈页有当前题反馈；随机顺序保留；错题子集位置语义正确（题库位置≠本轮索引）。
   - 历史/统计/错题本数量与备份一致；自评/跳过/自动结果分开。
   - 有 PDF 的题库可看来源页；无 PDF 的题库提示原页不可查看但题目/练习可用。
4. 再导入同一个 ZIP：产生另一个独立新题库，ID/目录不冲突；原有两个题库不变。
5. 屏幕旋转：预览/提交状态不丢失、不重复导入；成功后旋转仍显示成功。
6. 提交完成后从备份页返回首页：不能再次触发导入；重新进入备份页为干净状态。
7. 恢复期间/完成后强杀进程并重开：不得自动重放恢复；不得出现半份题库；已有新库若已提交应完整。
8. 用文件管理器删除/改坏 ZIP 中的 PDF 后导入：应整次失败，不出现只写一半的题库；原库/PDF 不受影响。

## 数据库/文件一致性（设备）

1. 打开应用私有目录（debug 可用 `run-as com.offlinequiz.app ls files/libraries`）：
   - 每个新库目录只对应一个已提交题库；不存在只发布 PDF 却无 DB 行的新目录（强杀窗口可能短暂存在，1 小时后由孤儿清理）。
2. 备份文件内不含 `offlinequiz.db`/WAL、绝对路径或原私有 PDF 路径；只含相对条目与 PDF 资源。
3. 恢复前后原库的 `libraries`/`questions`/`practice_sessions`/`practice_answers` 行数与内容不变（可用备份/导出对照）。

## 仪器测试（有设备时）

```powershell
$env:JAVA_HOME="F:\New project\work\jdk-21.0.13+9"
$env:ANDROID_HOME="F:\New project\work\android-sdk"
$env:ANDROID_SDK_ROOT="F:\New project\work\android-sdk"
$env:ANDROID_USER_HOME="F:\New project\work\android-user-home"
$env:GRADLE_USER_HOME="F:\New project\work\gradle-user-home"
F:\New project\android\gradlew.bat --project-dir "F:\New project\android" --no-daemon :app:connectedDebugAndroidTest
```

期望：22 项全部通过，重点 `BackupRoomDatabaseTest`（一致性快照/聚合插入/重复 ID 回滚）。未执行前不得声称真实 Room 已实测。

## 已知边界

- 文件不加密；获得备份者可读题库/练习。
- 系统 DocumentProvider 不保证原子替换；导出失败可能留下部分目标文件，应用会明确提示，不删除用户原文件。
- PDF 只校验头部/实际字节/SHA-256，不做完整 PDF 解析。
- 未做 release 签名/发布。
