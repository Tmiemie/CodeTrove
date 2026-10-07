# CodeTrove 架构设计

## 1. 架构原则

1. 先模块化单体，后按部署需求拆服务。
2. 同步调用用于用户请求内必须立即完成的操作；耗时评审和测试走异步任务。
3. 领域事务只保证 MySQL 内原子性，跨 Kafka 使用 Transactional Outbox。
4. Git 仓库是代码事实源，MySQL 保存协作元数据，Redis 只保存可重建数据。
5. 所有合并决策以当前 MR `head_commit` 对应的最新 Check 为准。

## 2. 逻辑模块

| 模块 | 主要职责 | 禁止承担 |
| --- | --- | --- |
| Auth | 注册、登录、JWT、当前用户 | 仓库授权决策 |
| Repository | 仓库、成员、分支、文件、Git Smart HTTP | AI 评审和测试执行 |
| Merge Request | MR、Diff、评论、审批、合并 | 直接调用 LLM |
| Check | Check Suite/Run、状态聚合、门禁判断 | 执行具体评审或测试 |
| Curator | 当前：确定性 Diff 静态评审、并行 Skill、Judge 与结果回写；后续：LLM/RAG/Memory | 修改代码仓库 |
| Assay | 当前：受控声明式 HTTP 用例、表达式、WireMock、响应断言和报告；后续：沙箱/DB/Bean Mock | 构建或执行任意 MR 代码、访问未授权目标 |
| Eventing | Outbox、事件发布、消费幂等、重试 | 保存业务最终状态 |
| Search | 代码/MR/评论索引和查询 | 作为强一致事实源 |

## 3. 运行时拓扑

```text
Browser / Git Client
        |
        | REST / Git Smart HTTP
        v
Spring Boot Application
  ├─ Auth
  ├─ Repository + JGit
  ├─ Merge Request
  ├─ Check Aggregator
  ├─ Curator Orchestrator
  ├─ Assay Orchestrator
  └─ Outbox Publisher
        |
        ├─ Windows MySQL  强一致元数据、Outbox、执行记录
        ├─ Docker Redis  Diff 缓存、短期去重、锁、限流
        ├─ Local FS      bare Git 仓库
        └─ Docker Kafka  MR、评审、测试事件

M4 的 WireMock 在 Assay 执行进程内绑定 `127.0.0.1` 动态端口，不是 Docker 测试沙箱。Qdrant、Elasticsearch、Canal 和 Docker 隔离均为后续按需求引入的增强项。
```

首版允许 Curator/Assay Worker 与 API 在同一进程中运行，但必须通过端口接口和任务模型隔离，使后续可拆为独立部署单元。

## 4. 建议工程结构

```text
backend/
├─ codetrove-bootstrap       # 启动、装配、配置
├─ codetrove-common          # 值对象、错误码、通用基础设施接口
├─ codetrove-auth
├─ codetrove-repository
├─ codetrove-merge-request
├─ codetrove-check
├─ codetrove-eventing
├─ codetrove-curator
├─ codetrove-assay
└─ codetrove-search
frontend/
deploy/
examples/
testcases/
docs/
```

约束：

- 业务模块不得直接依赖其他模块的持久化实现。
- 跨模块写操作通过应用服务完成。
- `common` 不允许成为无边界工具垃圾桶。
- 领域对象不依赖 Controller、ORM 或第三方 SDK 类型。

## 5. 关键数据流

### 5.1 Push 与 MR 更新

1. Git 客户端请求 `receive-pack`，通过 UTF-8 Basic Auth 复用平台账号认证。
2. RepositoryResolver 校验仓库可见性，ReceivePackFactory 检查 `PUSH` 权限并持有单节点仓库级 JVM 写锁。
3. PreReceiveHook 拒绝直接修改默认分支；普通分支写入受 command/object/pack、超时和并发上限保护。
4. ReceivePack 完成成功后，PostReceiveHook 将非删除的普通分支新 commit 交给 MR Head 同步服务。
5. 同步服务更新所有匹配 `repository_id + source_branch + OPEN` 的 MR head/version，并写入去重的 MergeRequestCommit 历史；删除分支保留最后快照。
6. M1.6 同步调用在 push 请求内完成；M2 再将 head 更新、旧 Check 失效和 Outbox/Kafka 事件纳入同一可靠异步链路。

### 5.2 分支与文件浏览

1. REST 请求先通过 JWT 认证，并按 repository ID 执行可见性过滤。
2. Repository 模块重新计算 `storage-root/owner/slug.git`，校验与数据库路径一致且实际路径位于真实存储根下。
3. ref 仅解析本地分支或完整 commit ID；TreeWalk 从 Git Object Database 读取当前目录或目标 blob。
4. tree 结果按名称排序并使用不透明游标分页；blob 在读取完整内容前按对象大小分流。
5. 小型严格 UTF-8 文本可内联；二进制和超大文本只返回元数据，不进入 API 正文。

### 5.3 MR、Diff 与评论

1. REST 请求通过 JWT 认证，并由 Repository 公共门面校验仓库可见性与 `CREATE_MERGE_REQUEST/COMMENT/READ` 权限。
2. 创建 MR 时只解析精确本地分支，在仓库行锁内分配 iid、检查重复 OPEN 组合并保存 base/head commit 快照。
3. Diff 固定比较保存的 base/head commit，JGit `DiffFormatter` 输出文件状态、增删统计和受限 patch；二进制不内联，文件数、单文件和总 patch 均受限制。
4. 行级评论与 Diff 输出共用 `FileHeader.toEditList()`：commit 必须等于 MR head，路径、OLD/NEW side 和行号必须命中真实变更区间。
5. MR 更新使用 version 乐观锁，只允许作者、OWNER 或 MAINTAINER编辑/关闭；MR 创建、关闭与 head 更新在同一业务事务内写入 Outbox，由 M2 Kafka 消费者维护当前 Check Suite。

### 5.4 Curator 确定性静态评审

1. Check 消费 MR 事件并创建当前 head 的 Curator Run，在同一事务写 `curator.review-requested` Outbox。
2. Curator 消费 command 并登记 `(consumer_name,event_id)` 幂等记录，校验 `head_commit` 仍是 MR 当前版本。
3. 通过 MR/Repository 公共门面读取受限 base/head Diff，只解析非二进制、未截断 patch 的新增行。
4. 逻辑与安全两个确定性 Skill 在有界线程池并行执行；每个 Skill 使用独立 Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead 和独立 timeout。
5. Judge 校验 skill、severity、path/line，并用 SHA-256 fingerprint 聚合去重；写系统评论前再次由 JGit EditList 校验 current head/path/NEW/line。
6. 事务内写 ReviewTask、ReviewFinding、幂等系统行级评论和结果 Outbox。
7. Check 消费 `started/completed/skipped` 结果更新非阻塞 Curator Run；Skill 不可用时为 `SKIPPED`，旧 head 为 `CANCELLED`。
8. 外部 LLM、RAG、Memory、PR Compression 与模型路由尚未接入，不能把本阶段描述为 LLM/AI 评审。

### 5.5 CodeAssay 声明式 HTTP 测试

1. Check 创建当前 head 的 blocking `assay.integration` Run，并在同一事务写 `assay.execution-requested` Outbox。
2. Assay 消费 command、登记数据库幂等记录并重新确认 MR head；从该 commit 读取最多 50 个 `testcases/**/*.json`。
3. JSON Schema Draft 2020-12、相对 path、target/header 白名单和受限表达式在发起 HTTP 前 fail-closed 校验。
4. Execution 通过 `run_token + lease_until` 在短事务 claim；WireMock/HTTP 执行在事务外，避免网络等待占用数据库事务。
5. `data_pre` 串行准备上下文；`application` 只访问服务端配置 base URL，`mock` 只访问绑定 `127.0.0.1` 动态端口的进程内 WireMock。
6. 响应断言产生结构化 path/operator/expected/actual 差异；CaseResult、Execution 终态、幂等 TEST_REPORT 和 completed Outbox 在 finish 短事务原子提交。
7. Check 消费 started/completed，只有当前 head/run 可更新 blocking Run；旧 head 为 `CANCELLED/MR_STALE`。
8. 当前未构建、部署或执行任意 MR 代码，也没有 Docker 沙箱、DB 断言、Bean Mock、fission、cleanup、流量录制或 AI 用例生成。

### 5.6 Merge

1. 读取仓库和 MR，校验 `MERGE` 权限、MR 为 OPEN、策略和幂等键。
2. 获取单节点仓库级 JVM 写锁，重新读取源/目标分支；expected head 必须等于 MR head 与源引用。
3. 使用 JGit 三方合并检查冲突；首版只支持无冲突 MERGE_COMMIT。
4. 创建两父 merge commit 对象并持久化 PENDING MergeOperation，随后再次核对源引用。
5. 以进入临界区时的目标 commit 为 expected old object，CAS 更新目标引用；目标变化则失败且不覆盖。
6. Git 引用成功后更新 MR 为 MERGED、记录 merged_by/merged_at，并完成 MergeOperation。
7. 相同幂等键重试时：成功操作直接返回原结果；PENDING 且目标引用等于已记录 merge commit 时恢复数据库终态。
8. M2 已接入当前 head 的 Check 门禁：current Suite 必须匹配 MR head，且所有 blocking Run 为 SUCCESS；Review/Approval 仍未实现。

## 6. 一致性策略

| 场景 | 策略 |
| --- | --- |
| MySQL 与 Kafka | Transactional Outbox + 至少一次投递 + 消费幂等；Publisher 短事务领取租约、事务外发送、短事务回写，避免 Kafka 网络等待占用数据库行锁 |
| Git 与 MySQL | 仓库锁 + 操作日志 + 可补偿状态；合并前后核对 commit |
| MySQL 与 ES | 最终一致；精确读取仍走 MySQL/Git |
| Redis 与事实源 | Cache Aside；删除或版本化 key；允许重建 |
| MR 与 Check | Check Suite 绑定 `head_commit`，旧版本不可参与门禁 |
| Assay claim | `(mr_id,head_commit)` 唯一 + `run_token/lease_until`；旧执行者不能覆盖重新 claim 后的新终态 |
| Assay HTTP 副作用 | 外部 HTTP 仍是至少一次语义；数据库幂等只保证平台记录和评论不重复，不宣称远端调用 exactly-once |

## 7. 并发与锁

- 锁粒度默认仓库级：`repo:{repoId}:write`。
- Push、修改引用和 Merge 必须串行化。
- 锁仅保护临界区，不替代数据库唯一约束和乐观锁。
- MR、Check 等记录使用 `version` 字段做乐观锁。
- 所有锁设置明确等待时间；失败返回可重试错误，不无限阻塞。

## 8. 可观测性

- HTTP：`trace_id`、用户、路由、状态、耗时。
- Kafka：`event_id`、`trace_id`、topic、partition、offset、重试次数。
- LLM：模型、请求 Token、响应 Token、耗时、错误类型，不记录密钥和完整敏感 Prompt。
- Assay：发现/排队耗时、HTTP 执行耗时、用例结果与失败分类；M4 尚无容器资源指标。
- 核心指标：MR 到首个结果耗时、评审成功率、测试成功率、Outbox 积压、消费延迟。

## 9. 演进触发条件

- 拆分 Curator/Assay：资源隔离或扩缩容需求已被指标证明。
- 引入 ES：数据库与 Git 搜索无法满足目标延迟或查询能力。
- 引入分片：单表容量、写入吞吐或维护窗口出现明确瓶颈。
- Git 多节点：单节点可用性或容量成为真实限制，且共享存储/节点路由方案已确定。
