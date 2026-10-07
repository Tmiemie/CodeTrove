# CodeTrove M1.6 MR Head 同步与幂等 Merge 验证记录

## 1. 实施范围

M1.6 在 M1.5 Merge Request、Diff 与评论基础上补齐 Codebase 的基础合并能力：

- 成功 push 普通分支后，同步匹配 OPEN MR 的 `head_commit/version`。
- 记录 MR 初始 head 与后续 head 历史。
- 在单节点仓库级 JVM 写锁内串行化 ReceivePack 与 REST Merge。
- 只支持 `MERGE_COMMIT`，通过 JGit 三方合并检测冲突并生成两个父提交。
- 通过目标引用 compare-and-set 防止目标分支竞态覆盖。
- 使用仓库范围 `Idempotency-Key` 与持久化 MergeOperation 实现重放及失败窗口恢复。

本阶段不实现 Review/Approval、Check Suite/Run 或“Checks 全绿后才允许合并”的完整门禁；这些能力属于 M2。

## 2. 实现证据

- Flyway V5：`backend/codetrove-bootstrap/src/main/resources/db/migration/V5__create_merge_history_and_operation.sql`
- 仓库写锁：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryWriteLockService.java`
- ReceivePack Hook：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryReceiveHooks.java`
- Ref 更新公共边界：
  - `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryRefUpdate.java`
  - `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryRefUpdateListener.java`
- MR head 同步：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestHeadSynchronizer.java`
- MR head 历史：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestHistoryRepository.java`
- 合并操作持久化：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeOperationRepository.java`
- 合并服务：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestMergeService.java`
- Merge REST API：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestController.java`
- 集成测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/mergerequest/MergeRequestApiTests.java`
- 真实环境脚本：`scripts/verify-m1-merge.ps1`

## 3. 数据模型与不变量

Flyway V5：

- 为 `codetrove_merge_request` 增加 `merge_commit`。
- 创建 `codetrove_merge_request_commit`，以 `(merge_request_id, commit_id)` 与 `(merge_request_id, sequence_number)` 保证提交历史去重与顺序唯一。
- 创建 `codetrove_merge_operation`，记录仓库、MR、幂等键、请求哈希、expected head、目标旧提交、merge commit、操作状态、合并人和完成时间。
- MergeOperation 状态只允许 `PENDING/SUCCEEDED`；`(repository_id, idempotency_key)` 唯一。

关键不变量：

- 创建 MR 时写入 sequence 1 的初始 head 历史。
- 只有成功且非删除的普通分支 ref 更新才同步 OPEN MR；删除源分支不写空 head，保留最后快照。
- Merge 请求的 expected head 必须同时等于 MR 当前 head 与源分支实际 head。
- 生成合并结果后再次读取源引用；目标引用只允许从临界区读取的旧值 CAS 到 merge commit。
- merge commit 的 parent 0 是合并前目标提交，parent 1 是源 head。
- 同一幂等键只能绑定同一 `iid + expectedHeadCommit + strategy` 请求哈希。

## 4. 并发与失败恢复

- ReceivePack 和 REST Merge 复用仓库级 `RepositoryWriteLockService`，同一仓库的写操作串行，不同仓库互不阻塞。
- 当前实现使用公平 `Semaphore` 和原子幂等 `Lease`，而不是 `ReentrantLock`。
- 原因：JGit pre/post receive 回调不保证在同一线程；跨线程释放 `ReentrantLock` 会抛 `IllegalMonitorStateException`。
- pre-receive 获取 Lease；post-receive 在成功、拒绝或异常路径安全关闭；仅 `Result.OK` 的 ref 更新触发 MR 同步。
- Merge 顺序为：生成 merge 对象 → 保存 PENDING → CAS 更新目标引用 → 更新 MR/操作数据库终态。
- 若 Git 引用已更新而数据库未完成，相同 key 重试时验证目标引用等于已记录 merge commit，再恢复 MR 与 MergeOperation 终态。
- 若目标分支已被其他写入改变，CAS 失败并返回 `GIT_REF_CHANGED`，不覆盖新引用。

该锁只保证单 JVM、单节点仓库文件系统内的正确性，不是分布式锁，多实例部署前仍需共享存储/节点路由或分布式协调设计。

## 5. Merge API 行为

实现：

```text
POST /api/v1/repositories/{repoId}/merge-requests/{iid}/merge
Idempotency-Key: <key>
```

请求：

```json
{
  "expectedHeadCommit": "<40-hex>",
  "strategy": "MERGE_COMMIT"
}
```

行为：

- 需要仓库 `MERGE` 权限；OWNER、MAINTAINER、DEVELOPER 可调用，REPORTER 被拒绝。
- 只接受 `MERGE_COMMIT`。
- MR 必须为 OPEN，源/目标引用必须存在，expected head 必须与数据库和 Git 实际值一致。
- 三方合并冲突返回 `MR_MERGE_CONFLICT`，目标引用和 MR 状态均不变化。
- 相同 key + 相同请求返回同一 merge commit，并设置 `idempotentReplay=true`。
- 相同 key + 不同请求返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 成功后 MR 返回 `MERGED`、`mergedBy`、`mergedAt` 与 `mergeCommit`。

## 6. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- 后端共 57 项测试全部通过，0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 打包成功。
- `MergeRequestApiTests` 共 17 项全部通过。
- M1.6 专项覆盖：
  - 成功 ref 更新同步 OPEN MR head/version，并保存初始与后续 head 历史。
  - 成功 Merge 生成两个父提交，父提交顺序符合契约。
  - 同一幂等键同请求重放返回相同 merge commit。
  - 同一幂等键不同请求返回确定冲突。
  - expected head 错误时 main 不变化。
  - 从共同祖先分别修改 README 同一行时返回真实三方合并冲突，MR 保持 OPEN。
  - 模拟 Git 引用已更新、数据库仍为 PENDING 后，同一 key 重试恢复数据库终态。
  - REPORTER Merge 被拒绝且 main 不变化。
- Git CLI 回归继续覆盖 clone、feature push、fetch、默认分支保护和 REPORTER push 拒绝，证明新 Hook 没有破坏协议主链路。

首次运行 `clean verify` 时，旧后端仍占用 Windows JAR，Maven Clean 无法删除文件；确认 8080 进程属于 CodeTrove 后停止旧 JAR，重新执行即完整通过。该失败不属于代码或测试失败。

## 7. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m1-merge.ps1
```

环境：

- Java 17
- Spring Boot 3.5.7 最终可执行 JAR
- Windows MySQL 8.0.43
- Flyway V5
- Docker Redis 7.4.2
- Git for Windows 2.55.0

脚本完成 7 个阶段：

1. 检查最终 JAR Readiness 与真实 MySQL Flyway V5。
2. 创建随机临时 OWNER、私有仓库并通过 Git Smart HTTP push feature 分支。
3. 创建 MR 后再次 commit/push，验证 OPEN MR 的 head/version 自动更新，并在数据库中形成 sequence 1/2 历史。
4. 调用 REST Merge，fetch 远端 main，验证目标引用等于 merge commit，且该 commit 恰有两个父提交。
5. 使用相同 Idempotency-Key 重放，验证返回原 merge commit，并核对 MR/MergeOperation 终态。
6. 核对 head 历史数量与成功合并操作数量。
7. 输出 `Real M1.6 merge verification passed`。

验收脚本初版已完成真实 Merge 和幂等重放，但使用拼接字符串比较数据库 `CHAR(40)` 终态时产生格式误判；`finally` 已完成清理与服务恢复。断言改为带 `RTRIM` 的精确条件计数后，完整 7 阶段通过。

最终复核：

- 临时 M1.6 用户数量：0。
- 临时 M1.6 仓库数量：0。
- 临时 M1.6 合并操作数量：0。
- Flyway 版本：5。
- 后端 Readiness：UP。
- Redis 容器正常运行，数据卷保留。

## 8. 结论与边界

M1.6 MR head 同步与基础幂等 Merge 已通过。当前可以真实声明：

- 普通分支成功 push 后，平台会更新匹配 OPEN MR 的 head/version 并保存提交历史。
- 平台可在单节点仓库级写锁内重新校验引用、检测三方合并冲突、生成两父 merge commit，并用 CAS 更新目标分支。
- Merge 支持持久化幂等键重放和 Git 成功/数据库未完成窗口恢复。

仍未完成：

- Review/Approval。
- Check Suite/Run 与阻塞型 Check 门禁。
- Outbox/Kafka 事件和新 head 触发的异步评审/测试。
- 多节点 Git 存储与分布式仓库锁。
- 前端接入真实 Merge API。

因此简历可以描述“基础 Merge、冲突检测、CAS、幂等与恢复”，不能描述“Checks 全绿后才允许合并”或“分布式锁控制合并”。
