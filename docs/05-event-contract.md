# CodeTrove Kafka 事件契约

## 1. 目标

本契约用于模块间异步协作，保证事件可版本化、可追踪、可幂等处理。Kafka 提供至少一次投递语义，业务正确性不能依赖“消息只到一次”。

## 2. Topic 规划

| Topic | 生产者 | 消费者 | Key |
| --- | --- | --- | --- |
| `codetrove.mr.events.v1` | MR 模块 | Check | `repository_id:mr_iid` |
| `codetrove.curator.commands.v1` | Check | Curator | `repository_id:mr_iid` |
| `codetrove.curator.results.v1` | Curator | Check | `repository_id:mr_iid` |
| `codetrove.assay.commands.v1` | Check | Assay | `repository_id:mr_iid` |
| `codetrove.assay.results.v1` | Assay | Check | `repository_id:mr_iid` |
| `codetrove.recording.events.v1` | Recording SDK | Recording Processor | `trace_id` |
| `codetrove.dlq.v1` | 消费框架 | 运维/补偿工具 | 原消息 key |

同一 MR 的事件使用相同 key，以获得分区内有序性；消费者仍需依赖版本字段而不是假设全局有序。

## 3. 统一 Envelope

```json
{
  "event_id": "uuid",
  "event_type": "mr.head-updated",
  "schema_version": 1,
  "occurred_at": "2026-10-04T05:00:00Z",
  "producer": "codetrove-merge-request",
  "trace_id": "trace-value",
  "aggregate": {
    "type": "MERGE_REQUEST",
    "id": "1234567890",
    "version": 7
  },
  "data": {}
}
```

约束：

- `event_id` 全局唯一且重试不变。
- `event_type` 使用小写点分格式。
- `schema_version` 是事件 payload 版本，不等于应用版本。
- ID 使用字符串。
- 不允许包含 Token、密码、Cookie、私钥和未脱敏个人信息。

## 4. MR 事件

### 4.1 `mr.created`

```json
{
  "repository_id": "1001",
  "mr_id": "2001",
  "mr_iid": 12,
  "source_branch": "feature/pay",
  "target_branch": "main",
  "base_commit": "...",
  "head_commit": "...",
  "author_id": "3001"
}
```

### 4.2 `mr.head-updated`

```json
{
  "repository_id": "1001",
  "mr_id": "2001",
  "mr_iid": 12,
  "previous_head_commit": "...",
  "head_commit": "...",
  "base_commit": "...",
  "change_sequence": 3
}
```

消费者开始耗时任务前必须确认 `head_commit` 仍是 MR 当前版本；过期任务标记 `CANCELLED`，不得覆盖当前结果。

### 4.3 `mr.closed`

包含 `repository_id`、`mr_id`、`mr_iid`、`head_commit`、`closed_by`。

### 4.4 `mr.merged`

包含源/目标原 head、merge commit、合并人和合并策略。

## 5. Curator 命令与结果事件

### 5.1 `curator.review-requested`

由 Check 消费者在创建当前 head 的 `curator.review` Run 后，于同一 MySQL 事务写入 Outbox。包含 `repository_id`、`mr_id`、`mr_iid`、`base_commit`、`head_commit`、`check_run_id` 和 `attempt`。Curator 只消费该命令，不与 Check 竞争原始 MR 事件；这保证 Run 已存在后才开始评审。

### 5.2 `curator.review-started`

包含 `review_task_id`、`mr_id`、`head_commit`、`check_run_id`、`attempt`。

### 5.3 `curator.review-completed`

```json
{
  "review_task_id": "4001",
  "repository_id": "1001",
  "mr_id": "2001",
  "head_commit": "...",
  "check_run_id": "5001",
  "status": "SUCCESS",
  "summary": {
    "critical": 0,
    "error": 1,
    "warning": 2,
    "info": 0,
    "finding_count": 3
  },
  "finding_ids": ["6001", "6002", "6003"],
  "report_version": 1
}
```

Finding 正文保存在 MySQL，事件只携带 ID 和摘要，避免 Kafka 消息过大。

### 5.4 `curator.review-skipped`

`reason_code` 可为 `LLM_UNAVAILABLE`、`MR_STALE`、`NO_REVIEWABLE_CHANGE`。

## 6. Assay 命令与结果事件

### 6.1 `assay.execution-requested`

由 Check 在创建当前 head 的 blocking `assay.integration` Run 后，于同一事务写入 `codetrove.assay.commands.v1`。包含 `repository_id`、`mr_id`、`mr_iid`、`head_commit`、`check_run_id`、`attempt`。Assay 必须重新确认 head 仍为当前 OPEN MR，再从该 commit 读取 `testcases/**/*.json`。

### 6.2 `assay.execution-started`

包含 `execution_id`、`repository_id`、`mr_id`、`mr_iid`、`head_commit`、`check_run_id`、`attempt` 和发现的用例数。

### 6.3 `assay.execution-completed`

```json
{
  "execution_id": "7001",
  "repository_id": "1001",
  "mr_id": "2001",
  "head_commit": "...",
  "check_run_id": "8001",
  "status": "FAILED",
  "summary": {
    "total": 12,
    "passed": 11,
    "failed": 1,
    "skipped": 0
  },
  "report_id": "9001",
  "failure_codes": ["ASSERTION_MISMATCH"]
}
```

执行基础设施异常也使用 `FAILED`，但 `failure_codes` 必须区分 `INFRASTRUCTURE_ERROR` 与业务断言失败。

## 7. 录制事件

录制数据在进入 Kafka 前必须完成基础脱敏。Envelope 中附加：

- `recording_session_id`
- `trace_id` / `span_id`
- `kind`：`HTTP_ENTRY/HTTP_EXIT/HTTP_CLIENT/DB_EFFECT`
- `captured_at`
- 经过白名单处理的 metadata
- 正文引用或受大小限制的脱敏摘要

禁止把 Authorization、Cookie、Set-Cookie 或原始数据库连接信息发入 Topic。

## 8. 发布流程

1. 业务事务更新聚合并插入 `outbox_event`。
2. Publisher 在短事务中使用 `FOR UPDATE SKIP LOCKED` 领取到期记录、递增 attempts 并写入租约截止时间，然后立即提交。
3. Kafka 发送在数据库事务外执行。
4. Broker 确认或失败后使用独立短事务按 `(id,status,attempts)` 条件回写。
5. 租约到期的 PENDING 记录可再次领取，配合消费幂等维持至少一次语义；达到上限后标记 FAILED。

注意：发布成功后进程在更新数据库前崩溃，会造成重复发送，因此消费者幂等不可省略。

## 9. 消费幂等

消费者事务模板：

1. 尝试插入 `(consumer_name, event_id)`。
2. 唯一键冲突表示已处理，直接确认消息。
3. 在同一数据库事务内执行业务更新。
4. 事务成功后确认 Kafka offset。

仅把 offset 提交视为幂等是不充分的。

## 10. 重试与死信

- 可恢复错误：依赖暂不可用、锁冲突、短暂网络失败。
- 不可恢复错误：Schema 不支持、必填字段缺失、安全策略违规。
- 可恢复错误有限次退避重试。
- 不可恢复或超限消息写入 DLQ，保留原 Topic、partition、offset、异常分类和 payload 哈希。
- 重放工具必须支持按 `event_id` 单条执行和 dry-run，不允许无条件批量回灌。

## 12. M2 最小执行口径

- MR 创建、head 更新、关闭和合并在更新业务状态的同一 MySQL 事务内插入 Outbox；不直接在业务事务中发送 Kafka。
- Outbox Publisher 批量锁定到期 PENDING 记录，Kafka broker 确认后标记 PUBLISHED；失败按有限指数退避更新 attempts/available_at，超过上限标记 FAILED。
- Check 消费者处理 `mr.created/mr.head-updated`，创建或激活 `(mr_id, head_commit)` Suite，并用 `(consumer_name,event_id)` 唯一记录实现至少一次投递下的业务幂等。
- M2 默认 Run：`curator.review` 非阻塞、`SKIPPED/NOT_IMPLEMENTED`；`assay.integration` 阻塞、`PENDING`。这只表示执行器尚未接入，不表示检查通过。
- `curator.review-completed/skipped` 与 `assay.execution-completed` 结果事件必须携带 `mr_id/head_commit/check_run_id`；head 或 run 不匹配时作为 stale 结果忽略，不覆盖当前 Suite。
- Merge 只读取 MR 当前 head 对应的 current Suite；任何 blocking Run 非 `SUCCESS` 都返回 `MR_CHECKS_NOT_PASSED`。
- 消费端有限重试后由 `DeadLetterPublishingRecoverer` 写入 `codetrove.dlq.v1`；DLQ 保存原 topic/key/value 与异常头，不在日志输出完整敏感 payload。

## 13. 兼容规则

- 同一 `schema_version` 只能新增可选字段。
- 改字段含义、删除字段、改变类型时升级版本。
- 消费者忽略未知字段，但必须拒绝未知的大版本。
- 事件 Schema 后续保存为 JSON Schema，并在 CI 中做兼容性检查。
