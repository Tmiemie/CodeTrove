# CodeTrove M2 Transactional Outbox、Kafka 与 Check 门禁验证记录

## 1. 实施范围

M2 在 M1 Codebase 基础链路上实现事件可靠性与当前 head 的合并门禁：

- MR 创建、head 更新、关闭和合并在业务事务内写 Transactional Outbox。
- Outbox Publisher 通过 Kafka 至少一次投递，支持失败计数、指数退避和最终 FAILED。
- Kafka 消费者以 `(consumer_name, event_id)` 唯一记录实现业务幂等。
- 为每个 MR/head 创建 Check Suite 与 Check Run，旧 head 自动失效。
- Merge 只接受当前 head 的 current Suite，所有 blocking Run 必须为 SUCCESS。
- 不可恢复或超过有限重试的 Kafka 消息进入统一 DLQ。
- Kafka 健康状态进入 Actuator Readiness。

M2 不实现真实 CodeCurator AI 评审或 CodeAssay 测试执行器：默认创建非阻塞 `curator.review=SKIPPED/NOT_IMPLEMENTED` 和阻塞 `assay.integration=PENDING`。这两个状态明确表达执行器边界，不冒充 AI/测试已运行。

## 2. 实现证据

- Flyway V6：`backend/codetrove-bootstrap/src/main/resources/db/migration/V6__create_outbox_and_checks.sql`
- 事件 Envelope：`backend/codetrove-eventing/src/main/java/com/codetrove/eventing/DomainEvent.java`
- 事务 Outbox：`backend/codetrove-eventing/src/main/java/com/codetrove/eventing/OutboxService.java`
- Outbox Publisher：`backend/codetrove-eventing/src/main/java/com/codetrove/eventing/OutboxPublisher.java`
- Kafka Topic/DLQ 配置：`backend/codetrove-eventing/src/main/java/com/codetrove/eventing/KafkaEventingConfiguration.java`
- Kafka Readiness：`backend/codetrove-eventing/src/main/java/com/codetrove/eventing/KafkaHealthIndicator.java`
- MR 生命周期事件：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestEventService.java`
- Check 持久化与聚合：`backend/codetrove-check/src/main/java/com/codetrove/check/CheckRepository.java`
- 幂等事件处理器：`backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventProcessor.java`
- Kafka Listener：`backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventConsumer.java`
- Check 查询与门禁：
  - `backend/codetrove-check/src/main/java/com/codetrove/check/CheckController.java`
  - `backend/codetrove-check/src/main/java/com/codetrove/check/CheckGateService.java`
- Merge 门禁接入：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestMergeService.java`
- Docker Kafka：`deploy/compose.yml`
- 自动化测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/check/CheckGateApiTests.java`
- 真实环境脚本：`scripts/verify-m2-event-check.ps1`

## 3. 数据模型

Flyway V6 创建：

- `codetrove_outbox_event`：event ID、类型、聚合、版本、Topic、Key、完整 Envelope、Trace ID、状态、重试次数、下次可用时间和截断错误。
- `codetrove_consumed_event`：`consumer_name + event_id` 唯一，保证同一消费者只执行业务投影一次。
- `codetrove_check_suite`：MR/head 唯一、聚合状态、`is_current` 与 version。
- `codetrove_check_run`：Suite、类型、稳定名称、blocking、状态、结论、attempt 和运行时间。

核心约束：

- `(event_id)` Outbox 唯一。
- `(consumer_name, event_id)` 消费唯一。
- `(merge_request_id, head_commit)` Suite 唯一。
- `(check_suite_id, name, attempt)` Run 唯一。
- Check/Outbox 业务字段使用状态检查约束，避免非法状态入库。

## 4. Outbox 发布与恢复

- MR 创建、head 更新、关闭和合并先完成业务写入，再在同一 MySQL 事务中追加 Outbox。
- Publisher 使用 `FOR UPDATE SKIP LOCKED` 批量领取到期 PENDING 记录，锁覆盖 Kafka send 与数据库状态更新。
- Kafka Producer 使用 `acks=all` 和幂等 Producer；Broker 确认后 Outbox 标记 PUBLISHED。
- 发送失败增加 attempts，以有限指数退避更新 `available_at`；达到上限标记 FAILED。
- Kafka 已确认但数据库提交前进程崩溃仍可能重复发送，这是至少一次投递的固有窗口，由消费幂等兜底，不宣称 exactly-once。
- `last_error` 只保存异常类型与清理换行后的截断信息，不写完整敏感 payload。

## 5. 消费幂等与 DLQ

- Kafka Listener 只负责传输适配，业务处理集中在事务化 `CheckEventProcessor`。
- 处理事件先写 `ConsumedEvent`，再创建/更新 Check；两者处于同一事务。
- 唯一键冲突表示事件已处理，直接返回，不重复创建 Suite/Run 或重复修改状态。
- Schema 大版本不支持、字段缺失或类型不匹配会回滚消费记录，使失败消息仍可重试。
- `DefaultErrorHandler + DeadLetterPublishingRecoverer` 有限重试两次，随后将原消息投递到 `codetrove.dlq.v1`。
- 真实验收向 MR Topic 投递 `schema_version=99` 的坏消息，DLQ 消费确认原 event ID 仍在消息值中。

## 6. Check Suite/Run 与门禁

MR 创建或 head 更新事件：

- 同一 `(mr_id, head_commit)` 最多一个 Suite。
- 新 head 的 Suite 设为 current，旧 Suite 设为 historical。
- 旧 Suite 中仍为 PENDING/RUNNING 的 Run 转为 CANCELLED/STALE_HEAD。
- 默认 Run：
  - `curator.review`：非阻塞、`SKIPPED`、conclusion=`NOT_IMPLEMENTED`。
  - `assay.integration`：阻塞、`PENDING`。

结果事件：

- 必须匹配 event type、check type、run ID、MR ID、head commit 和 current Suite。
- 过期 head 或历史 Run 的结果不覆盖当前状态。
- Run 完成后重新聚合 Suite 状态。

Merge 门禁：

- 在仓库级写锁内、生成 Git 合并对象之前检查当前 MR/head 的 Suite。
- Suite 缺失、head 不匹配、没有 blocking Run，或任何 blocking Run 为 PENDING/RUNNING/FAILED/SKIPPED/CANCELLED，均返回 409 `MR_CHECKS_NOT_PASSED`。
- 非阻塞 Curator `SKIPPED/NOT_IMPLEMENTED` 不阻塞；当前阻塞 Assay Run 为 SUCCESS 后 Merge 才放行。
- Merge 成功后 `mr.merged` 事件使当前 Suite 退出 current，并取消仍未结束的 Run。

## 7. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- 后端共 64 项测试全部通过，0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 打包成功。
- H2 MySQL 模式从空库成功执行 V1～V6。
- M2 `CheckGateApiTests` 7 项全绿，覆盖：
  - MR 创建事务内产生 Outbox。
  - 同一事件重复处理只产生一套 Suite/Run。
  - PENDING、RUNNING、FAILED blocking Run 均拒绝 Merge。
  - 合法 ASSAY SUCCESS 结果放行 Merge。
  - 新 head 使旧 Suite 非 current、旧阻塞 Run CANCELLED。
  - 过期结果不能解锁当前门禁。
  - 不合法结果事件回滚 ConsumedEvent。
  - Outbox 发布成功转 PUBLISHED。
  - 发布失败退避并达到上限转 FAILED。

## 8. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m2-event-check.ps1
```

环境：

- Java 17 / Spring Boot 3.5.7 最终 JAR
- Windows MySQL 8.0.43 / Flyway V6
- Docker Redis 7.4.2
- Docker Apache Kafka 3.9.1（单节点 KRaft）
- Git for Windows 2.55.0

脚本完成 10 个阶段：

1. 检查最终 JAR、Kafka Readiness、业务 Topic/DLQ 和 Flyway V6。
2. 创建随机 OWNER、私有仓库、feature 分支和 MR。
3. 验证 `mr.created` Outbox 发布，Kafka 消费后建立唯一 current Suite；确认 Curator 占位与 Assay 阻塞状态。
4. 停止 Kafka，真实 push 新 head；MR 数据库事务仍成功，`mr.head-updated` 留在 PENDING Outbox，Readiness 变为 DOWN。
5. 恢复 Kafka，Readiness 回到 UP，PENDING Outbox 最终 PUBLISHED，新 Suite 成为 current，旧阻塞 Run CANCELLED。
6. 验证阻塞 Check 未通过时 Merge 返回 `MR_CHECKS_NOT_PASSED`。
7. 向真实 Kafka 发送两次相同 Assay SUCCESS 结果，Run 更新一次，ConsumedEvent 只有一条。
8. 发送未知 Schema 事件，有限重试后从 DLQ 读取并确认原 event ID。
9. 当前阻塞 Check 成功后 Merge 放行，并发布 `mr.merged` Outbox。
10. 输出 `Real M2 event and check gate verification passed`。

## 9. 故障与边界验证

- Kafka 停机不回滚已提交的 MR head 更新；Outbox 留待恢复后发布。
- Kafka 停机期间 Kafka HealthIndicator 使 Readiness 返回 DOWN，恢复后自动回到 UP。
- 重复事件不重复执行 Check 投影。
- 过期 head 结果不参与当前门禁。
- 坏消息不会无限阻塞分区，有限重试后进入 DLQ。
- 第一次加入 Kafka HealthIndicator 时，测试配置关闭 Eventing 但 Readiness 仍引用 `kafka`，导致测试 Context 启动失败；已为 test profile 单独定义不含 Kafka 的健康组，真实配置继续强制 Kafka Readiness。
- 首轮实机脚本在容器内删除临时事件文件时因 Kafka 非 root 用户产生 warning；改为仅清理该随机文件且使用容器 root，第二轮 10 阶段无 warning 通过。
- Kafka 命名卷会保留历史 DLQ 消息；验收脚本改为发布前读取 DLQ partition 的 end offset，并从该 offset 消费本次新增消息，随后在已有历史消息的环境中再次完整执行 10 阶段并通过。

最终复核：

- 临时 M2 用户：0。
- 临时 M2 仓库：0。
- 孤立 Outbox：0。
- 孤立 ConsumedEvent：0。
- Kafka 临时事件文件：0。
- Flyway：V6。
- Kafka：healthy。
- 后端 Readiness：UP（最终 JAR 重启后复核）。

## 10. 结论与当前边界

M2 Transactional Outbox、Kafka 幂等消费、Check Suite/Run 和当前 head Merge 门禁已完成并通过。

当前可以真实声明：

- 使用 Transactional Outbox 解决 MySQL 业务提交与 Kafka 发送的一致性窗口。
- Kafka 至少一次投递下，通过数据库唯一键实现消费业务幂等。
- 新 head 使旧 Suite/Run 失效，只有当前 head 的阻塞 Check 全部 SUCCESS 才允许 Merge。
- Kafka 故障可积压恢复，坏消息有限重试后进入 DLQ。

仍未完成：

- CodeCurator 真实 AI 评审执行器。
- CodeAssay 声明式测试引擎与真实测试结果生产者。
- Review/Approval。
- Check rerun API 和人工 DLQ 重放工具。
- 多 Broker Kafka、高可用部署和跨实例压测。

因此简历可以写“事件可靠性、幂等消费、Check 状态机与 Merge 门禁”，不能写“AI 评审已完成”或“声明式测试引擎已完成”。
