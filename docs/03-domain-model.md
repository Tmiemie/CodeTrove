# CodeTrove 领域模型

## 1. 统一约定

- 业务主键使用 64 位 Snowflake ID；事件 `event_id` 使用 UUID。
- 所有时间使用 UTC 存储，API 输出 ISO 8601。
- 核心表包含 `created_at`、`updated_at`；可并发修改的聚合包含 `version`。
- 软删除仅用于确有恢复需求的资源；安全凭据和执行临时数据按保留策略物理删除。

## 2. 聚合关系

```text
User
  └─ RepositoryMember ─ Repository
                          ├─ Branch (Git 派生视图)
                          ├─ MergeRequest
                          │    ├─ MergeRequestCommit
                          │    ├─ Comment
                          │    ├─ Review
                          │    └─ CheckSuite
                          │          └─ CheckRun
                          ├─ AssayExecution
                          │    └─ AssayCaseResult
                          └─ KnowledgeDocument（规划）

MergeRequest ─ ReviewTask ─ ReviewFinding
OutboxEvent / ConsumedEvent 支撑异步一致性
```

## 3. 用户与权限

### User

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| id | bigint | 主键 |
| username | varchar(64) | 全局唯一，规范化后比较 |
| password_hash | varchar | 只保存强哈希 |
| display_name | varchar(128) | 非空 |
| status | enum | `ACTIVE/LOCKED/DISABLED` |

### RepositoryMember

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| repository_id | bigint | 联合唯一键之一 |
| user_id | bigint | 联合唯一键之一 |
| role | enum | `OWNER/MAINTAINER/DEVELOPER/REPORTER` |

权限基线：

| 操作 | OWNER | MAINTAINER | DEVELOPER | REPORTER |
| --- | --- | --- | --- | --- |
| 管理成员/仓库 | 是 | 否 | 否 | 否 |
| 修改仓库规则 | 是 | 是 | 否 | 否 |
| Push 普通分支 | 是 | 是 | 是 | 否 |
| 创建 MR/评论 | 是 | 是 | 是 | 是 |
| Merge | 是 | 是 | 受规则控制 | 否 |
| 只读 | 是 | 是 | 是 | 是 |

## 4. Repository 聚合

### Repository

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| owner_id | bigint | 创建者 |
| name | varchar(100) | 所有者范围内唯一 |
| slug | varchar(100) | URL 和目录安全名称 |
| visibility | enum | `PRIVATE/PUBLIC` |
| default_branch | varchar(255) | 默认 `main` |
| storage_path | varchar | 由服务端生成，不接受用户绝对路径 |
| status | enum | `INITIALIZING/ACTIVE/ARCHIVED/ERROR` |

不变量：

- `slug` 必须通过严格白名单，禁止路径穿越。
- `storage_path` 由服务端根据受控根目录、规范化后的 owner username 与 slug 生成，API 不接受也不返回绝对路径。
- 创建者在同一数据库事务中自动写入 `RepositoryMember(OWNER)`。
- 仓库创建先写入 `INITIALIZING` 元数据，再创建 bare Git 仓库，成功后转为 `ACTIVE`；事务回滚时补偿清理本次新建目录。
- 已存在的仓库目录拒绝复用且不得被补偿逻辑删除。
- 删除或归档仓库前不得有运行中的写操作。

Branch 和文件树以 Git 对象为事实源，MySQL 可保存只读索引但不作为引用真相。

## 5. Merge Request 聚合

### MergeRequest

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| repository_id | bigint | 所属仓库 |
| iid | int | 仓库内递增编号 |
| title | varchar(255) | 非空 |
| description | text | Markdown，输出时净化 |
| source_branch | varchar(255) | 源分支 |
| target_branch | varchar(255) | 目标分支 |
| base_commit | char(40/64) | 当前比较基线 |
| head_commit | char(40/64) | 当前源分支版本 |
| status | enum | `OPEN/MERGED/CLOSED` |
| author_id | bigint | 作者 |
| merged_by | bigint? | 合并人 |
| merged_at | datetime? | 合并时间 |
| version | bigint | 乐观锁 |

不变量：

- 同仓库、同源分支、同目标分支最多一个 OPEN MR；创建流程在数据库事务中锁定仓库行后分配 iid 并检查重复 OPEN 组合。
- OPEN MR 的源、目标分支必须是不同的本地分支，创建时两端 commit 必须不同。
- M1.5 创建时保存 `base_commit/head_commit` 快照；M1.6 在成功更新普通源分支后同步所有匹配 OPEN MR 的 `head_commit` 并递增 `version`，`base_commit` 在本阶段保持创建时基线。
- 更新标题、描述或关闭 MR 必须提交当前 `version`；版本不匹配返回冲突。
- Merge 必须重新读取源/目标引用并要求源引用等于请求的 `expected_head_commit` 和 MR 当前 `head_commit`；计算完成后再次核对源引用，目标引用使用 compare-and-set 更新。
- MR 状态迁移：`OPEN -> CLOSED` 或 `OPEN -> MERGED`；终态不可恢复，重开创建新 MR。

### MergeRequestCommit

记录 MR 初始 head 与每次成功 push 后观察到的 head：`mr_id`、`commit_id`、`sequence`、`observed_at`。`(mr_id, commit_id)` 唯一；sequence 在 MR 内递增。源分支删除不写空 commit，保留最后快照，后续 Merge 因源引用不存在而失败。

### MergeOperation

| 字段 | 说明 |
| --- | --- |
| repository_id / mr_id | 所属仓库与 MR |
| idempotency_key | 仓库范围内唯一，8～128 个 URL-safe 字符 |
| request_hash | MR、expected head 与策略的规范化哈希 |
| expected_head_commit | 调用者期望合并的源版本 |
| target_before_commit | 进入合并临界区时的目标版本 |
| merge_commit | 已生成的两父 merge commit，可空 |
| status | `PENDING/SUCCEEDED` |
| merged_by / created_at / completed_at | 操作者与审计时间 |

不变量：

- 相同 `(repository_id, idempotency_key)` 只允许同一 request hash；不同请求复用返回幂等冲突。
- 创建 merge commit 对象后先保存 `PENDING` 操作，再以 target old commit 做引用 CAS。
- 若进程在 Git 引用更新后、MR 数据库终态提交前失败，重试相同 key 时通过 `target ref == merge_commit` 恢复 MR 与操作为成功。
- 冲突、head 变化或目标 CAS 失败不能把 MR 标记为 `MERGED`。

### Comment

| 字段 | 说明 |
| --- | --- |
| type | `GENERAL/DIFF/AI_REVIEW/TEST_REPORT` |
| file_path | Diff 评论时必填 |
| side | `OLD/NEW` |
| line_number | Diff 评论时必填 |
| commit_id | 评论定位版本 |
| author_type | `USER/SYSTEM` |
| body | Markdown，渲染前净化 |
| fingerprint | 系统评论去重键，可空 |

行级评论必须绑定 MR 当前快照的 `head_commit`，并验证文件路径及 OLD/NEW 行号确实属于该 Diff 的删除/新增范围。普通评论不包含定位字段。M1.5 只保存用户 Markdown 原文，不在服务端渲染 HTML；前端渲染时必须净化。

## 6. Check 聚合

### CheckSuite

每个 MR 的每个 `head_commit` 对应一个 Check Suite。

| 字段 | 说明 |
| --- | --- |
| mr_id | 所属 MR |
| head_commit | 检查版本 |
| status | 聚合状态 |
| is_current | 是否为当前版本 |

唯一约束：`(mr_id, head_commit)`。

### CheckRun

| 字段 | 说明 |
| --- | --- |
| suite_id | 所属 Suite |
| check_type | `CURATOR/ASSAY`，后续可扩展 |
| name | 稳定机器名 |
| blocking | 是否阻塞合并 |
| status | `PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED` |
| conclusion | 结构化结论码 |
| details_url | 报告地址或前端路由 |
| started_at/finished_at | 运行时间 |
| attempt | 尝试次数 |

不变量：

- 终态不可回到运行态；重试创建新 attempt 或新 Run。
- 非当前 Suite 不参与门禁。
- 默认 `ASSAY` 阻塞，`CURATOR` 非阻塞。
- M2 在 Curator 未接入时使用 `curator.review=SKIPPED/NOT_IMPLEMENTED` 占位；M3 启用后，新 Suite 将 Curator Run 初始化为 `PENDING`，并由 `curator.review-requested` 命令驱动真实静态评审。M4 启用后，`assay.integration` 初始化为 blocking `PENDING`，由真实声明式 HTTP 执行推进到终态。
- 门禁只接受 `is_current=true`、`head_commit` 等于 MR 当前 head 的 Suite，且所有 blocking Run 必须为 `SUCCESS`；Suite 缺失、旧 head、`PENDING/RUNNING/FAILED/SKIPPED/CANCELLED` 均阻塞 Merge。
- `mr.created/mr.head-updated` 消费创建或激活 Suite；相同事件由 ConsumedEvent 去重，相同 `(mr_id, head_commit)` 不重复创建 Suite/Run。

## 7. Curator 模型

### ReviewTask

绑定 `mr_id + head_commit`，状态为 `PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED`。M3 使用 `(mr_id, head_commit)` 唯一约束和 `check_run_id` 关联当前 Curator Run；任务、Finding、系统评论、ConsumedEvent 与结果 Outbox 在同一事务完成，失败整体回滚并由 Kafka 有限重试。

### ReviewFinding

| 字段 | 说明 |
| --- | --- |
| task_id | 评审任务 |
| skill | `logic/security/style/performance` |
| severity | `INFO/WARNING/ERROR/CRITICAL` |
| rule_id | 稳定规则标识 |
| file_path/line | 定位 |
| title/message | 展示内容 |
| evidence | 精简证据，不包含密钥 |
| suggestion | 修改建议 |
| fingerprint | `repo + rule + file + normalized-code` 哈希 |
| disposition | `OPEN/ACCEPTED/FALSE_POSITIVE/FIXED` |

Judge 只聚合结果，不允许凭空改变源代码事实或创建不存在的行号。

## 8. Assay 模型

M4 不建立独立 TestCase 目录表；事实源是 MR 当前 `head_commit` 中的 `testcases/**/*.json`。未来录制/AI 候选用例与审批模型属于后续里程碑。

### AssayExecution

| 字段 | 说明 |
| --- | --- |
| repository_id / merge_request_id | 所属仓库与 MR |
| head_commit | 绑定的 40 位当前 head |
| check_run_id | 唯一关联 blocking Assay Run |
| status | `PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED` |
| conclusion | `ALL_CASES_PASSED/ASSERTION_MISMATCH/CASE_DEFINITION_INVALID/NO_ENABLED_CASES/MR_STALE/INFRASTRUCTURE_ERROR` |
| attempt | claim 次数 |
| run_token / lease_until | 执行所有权与租约；终态条件回写防止旧执行者覆盖 |
| total/passed/failed/skipped_count | 汇总，数据库约束要求总数等于三类之和 |
| started_at / finished_at | 执行时间 |

唯一约束：`(merge_request_id,head_commit)` 与 `check_run_id`。prepare/start 使用短事务；HTTP/WireMock 在事务外执行；finish 短事务原子写结果、Execution 终态、幂等 TEST_REPORT 和结果 Outbox。

### AssayCaseResult

| 字段 | 说明 |
| --- | --- |
| execution_id / case_key | 所属执行与用例稳定键，组合唯一 |
| source_path | 当前 Git commit 中的 JSON 文件路径 |
| status | `PASSED/FAILED/ERROR/SKIPPED` |
| failure_code | 失败分类 |
| duration_ms | 单用例耗时 |
| assertion_diff | 结构化 JSON 差异，查询 API 返回数组而非二次编码字符串 |

M4 对响应正文使用 1 MiB 有界读取，断言敏感路径的 expected/actual 保存为 `[REDACTED]`。当前不保存完整大响应、环境镜像或 Docker 容器信息。

## 9. 事件支撑模型

### OutboxEvent

- `event_id`、`event_type`、`aggregate_type`、`aggregate_id`。
- `schema_version`、`payload`、`trace_id`。
- `status`：`PENDING/PUBLISHED/FAILED`。
- `available_at`、`attempts`、`last_error`。

### ConsumedEvent

`consumer_name + event_id` 唯一，用于消费幂等；业务结果和消费记录必须处于同一事务。

## 10. 需在实现期细化的索引

- Repository：`(owner_id, slug)` 唯一。
- MR：`(repository_id, iid)` 唯一；OPEN 源/目标组合使用业务约束或生成列实现。
- Comment：`(mr_id, created_at)`；系统评论 `fingerprint` 去重。
- CheckSuite：`(mr_id, head_commit)` 唯一。
- CheckRun：`(suite_id, name, attempt)` 唯一。
- Outbox：`(status, available_at)`。
- ConsumedEvent：`(consumer_name, event_id)` 唯一。
- AssayExecution：`(merge_request_id,head_commit)` 唯一；`check_run_id` 唯一。
- AssayCaseResult：`(execution_id,case_key)` 唯一。
