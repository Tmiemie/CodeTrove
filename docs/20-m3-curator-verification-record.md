# CodeTrove M3 CodeCurator 确定性静态评审验证记录

## 1. 实施范围与命名边界

M3 完成 CodeCurator 的可运行评审编排基线：

- Check 在创建当前 head 的 `curator.review` Run 后，同一事务写 `curator.review-requested` Outbox。
- Curator 消费命令并登记 `(consumer_name,event_id)` 幂等记录。
- 通过受控 MR 门面读取服务端固定的 base/head Diff，只分析受限 UTF-8 patch 的新增行。
- 逻辑、安全两个确定性 Review Skill 并行执行。
- Judge 校验 Skill/severity/path/line，使用 SHA-256 fingerprint 聚合去重。
- ReviewTask、ReviewFinding、系统行级评论和结果 Outbox 在同一事务完成。
- Check 消费 `started/completed/skipped` 事件并更新非阻塞 Curator Run。
- 新 head 建立新任务；查询 API 只返回当前 head 的 Task/Findings。
- Resilience4j Retry、CircuitBreaker、SemaphoreBulkhead、有界线程池和每 Skill 独立超时用于隔离与降级。

本阶段没有接入外部 LLM、RAG 或 Memory。逻辑/安全 Skill 是可复现的确定性静态规则评审，不把规则扫描器命名为或冒充 LLM/AI 评审。

## 2. 已实现规则

### Security Skill

- `SEC001_HARDCODED_CREDENTIAL`：新增行出现疑似硬编码 password/passwd/api key/secret/access token。
- `SEC002_SQL_CONCATENATION`：数据库调用中疑似使用字符串拼接构造 SQL。

疑似凭据的原始值不会写入 Finding evidence、系统评论或日志。

### Logic Skill

- `LOGIC001_DANGLING_IF`：`if (condition) ;` 导致条件体为空。
- `LOGIC002_EMPTY_CATCH`：单行空 catch 静默吞掉异常。

规则只对 Diff 新增行生效，不扫描未变更的历史代码。

## 3. 数据模型

Flyway V7 创建：

- `codetrove_review_task`
  - `(merge_request_id, head_commit)` 唯一。
  - `check_run_id` 唯一并关联 Curator Check Run。
  - 状态：`PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED`。
- `codetrove_review_finding`
  - `(review_task_id, fingerprint)` 唯一。
  - 保存 skill、severity、rule、path、NEW line、受控 evidence/suggestion 与 disposition。
- 扩展 MR 评论定位约束，使 `AI_REVIEW` 和用户 `DIFF` 评论一样必须绑定 path/side/line/commit。

## 4. 事件与事务闭环

1. Check 消费 `mr.created/mr.head-updated` 并创建当前 Suite/Run。
2. M3 开启时，Curator Run 初始化为 `PENDING`；Check 在同一事务追加 `curator.review-requested` Outbox。
3. Curator 消费命令，消费记录与业务处理处于同一事务。
4. Curator 创建 RUNNING ReviewTask，并追加 `curator.review-started` Outbox。
5. 评审成功后原子写 Finding、系统评论、Task 终态与 `curator.review-completed` Outbox。
6. Diff 截断、无可评审新增行、Skill 超时/熔断时，写 `SKIPPED` 与明确 reason；旧 head 写 `CANCELLED/MR_STALE`。
7. Check 消费结果事件，只允许当前 head/run 更新 Curator Check。

Curator 命令与结果都使用 Transactional Outbox，Kafka 仍是至少一次投递；消费幂等和数据库唯一约束负责重复消息安全。

## 5. 受控 Diff 与定位安全

- Curator 不直接查询 MR 内部持久化实现或拼接仓库路径，使用 `MergeRequestReviewAccessService` 与 `RepositoryAccessService.requireSystem`。
- Repository 模块仍会重新计算受控路径、校验数据库 storage_path 与真实路径，并拒绝符号链接逃逸。
- Curator 输入固定为 MR 当前 base/head，不接受客户端任意 commit 对。
- 二进制、超限或 truncated Diff 不交给 Skill；truncated 结果为 `SKIPPED/DIFF_TRUNCATED`。
- Judge 先验证候选位置属于解析出的新增行；写系统评论前 MR 门面再次通过 JGit `EditList` 校验 current head/path/NEW/line。
- 系统评论 fingerprint 为 `head_commit:finding_fingerprint`，同一 head 重放不重复，新 head 可重新报告仍存在的问题。

## 6. 并行、重试、熔断与降级

- 两个 Skill 通过固定大小线程池并行运行。
- 每个 Future 使用独立 `orTimeout`；整批执行还有总超时上限。
- 每个 Skill 使用独立 Resilience4j Retry、CircuitBreaker 和 SemaphoreBulkhead。
- 短暂异常只做有限重试；参数/输出校验异常不重试。
- 任一 Skill 超时、Bulkhead 拒绝或熔断导致整次评审降级为 `SKIPPED/SKILL_UNAVAILABLE`，不会写 SUCCESS。
- ReviewTask/Finding/评论/结果 Outbox 处于同一事务；未捕获业务异常会整体回滚，让 Kafka 后续有限重试。

## 7. API

`GET /api/v1/repositories/{repoId}/merge-requests/{iid}/review-findings`

- 需要仓库 READ 权限，私有仓库继续遵守 404 隔离。
- 只返回 MR 当前 head 对应的 Task 和 Findings。
- 支持精确筛选 `skill=LOGIC|SECURITY`、`severity=INFO|WARNING|ERROR|CRITICAL`、`disposition=OPEN|ACCEPTED|FALSE_POSITIVE|FIXED`。
- 非法筛选返回统一校验错误。
- M3 尚未实现 disposition PATCH/误报 Memory，API 契约中的更新接口仍属后续。

## 8. 自动化验证

最终执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- 后端合计 74 项测试（bootstrap 67 + curator 7）。
- 0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 打包成功。
- H2 MySQL 模式从空库成功执行 V1～V7。

Curator 核心 7 项覆盖：

- unified diff 新增行与真实 NEW 行号解析。
- 疑似 Credential evidence 脱敏。
- Judge 去重和伪造位置拒绝。
- Resilience4j 短暂异常有限重试。
- 每 Skill 超时不返回成功。
- 处理器把 Skill 超时映射为 `curator.review-skipped/SKILL_UNAVAILABLE`。
- 并行栅栏要求两个 Skill 在 500ms 内同时进入执行区，串行实现会确定失败。

Spring 跨模块 3 项覆盖：

- 真实 JGit Diff 产生两个 Finding、两个 AI_REVIEW 行级系统评论和 SUCCESS Curator Check。
- 相同 command 两次不重复创建 Task/Finding/评论/结果事件。
- 旧 head command 只生成 CANCELLED Task，不产生 Finding/评论。
- Review Findings API 与 skill 筛选。
- Finding/评论/API 均不泄露测试中的 credential-like 原值。

## 9. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m3-curator.ps1
```

环境：

- Java 17 / Spring Boot 3.5.7 最终 JAR
- Windows MySQL 8.0.43 / Flyway V7
- Docker Redis 7.4.2
- Docker Apache Kafka 3.9.1 单节点 KRaft
- Git for Windows 2.55.0

八阶段验收：

1. 检查最终 JAR、Readiness、Curator command/result Topic 和 Flyway V7。
2. 真实 HTTP 注册/登录/建库，通过 Git Smart HTTP push 固定安全与逻辑缺陷并创建 MR。
3. 等待 Kafka 链路完成 ReviewTask、两个 Finding、两个系统行级评论和 SUCCESS 非阻塞 Curator Check。
4. 通过 API 验证当前 head、Skill 筛选、规则 ID、定位和 Credential 原值未进入 API/数据库评论/Finding。
5. 向真实 Kafka 重放同一 command 两次，Task/Finding/评论仍保持唯一。
6. 真实第二次 push 清理缺陷，新 head 生成 `NO_FINDINGS` Task；API 只返回当前 head，历史 Finding 不混入。
7. 验证结果 Outbox 已发布、当前 Curator Run 为 non-blocking SUCCESS、Assay 阻塞占位仍为 PENDING。
8. 输出 `Real M3 Curator static review verification passed`，清理临时数据/仓库并恢复后端。

## 10. 发现并修复的 Outbox 锁问题

M3 增加更多事件后，M2 故障回归暴露：旧 Publisher 在 `FOR UPDATE SKIP LOCKED` 的数据库事务内同步等待 Kafka；Kafka 停机时，网络等待和重试长期持有 Outbox 行锁/事务，间接阻塞包含新 Outbox 写入的 Git push 数据库事务。

修复为短租约：

1. 短事务内 `SKIP LOCKED` 领取记录，递增 attempts，并把 `available_at` 推迟到租约截止时间后立即提交。
2. 事务外等待 Kafka send。
3. 成功或失败使用独立短事务按 `(id,status,attempts)` 条件回写。
4. 进程在发送前/后崩溃时，租约到期后可再次领取，保持至少一次语义。

修复后重新执行完整 M2 十阶段：Kafka 停机时 Git push 正常提交、Outbox PENDING、Readiness DOWN；Kafka 恢复后最终发布、后续门禁/DLQ/清理全部通过。

## 11. 当前边界

可以真实声明：

- CodeCurator 确定性静态评审编排基线。
- 逻辑/安全两个 Review Skill、并行执行、Judge 校验去重。
- ReviewTask/Finding、幂等行级系统评论、当前 head 查询和非阻塞 Curator Check。
- Resilience4j 有限重试、熔断、Bulkhead、独立超时与 SKIPPED 降级。

不能声明：

- 已接入 LLM 或完成 AI 代码评审。
- 已实现 RAG、Memory、PR Compression、增量 LLM 评审或模型路由。
- 已实现 Finding disposition PATCH、误报反馈闭环或真实 LLM 限流验证。
- CodeAssay 已实现。
