# CodeTrove — 智能代码协作与质量保障平台・项目设计文档

> **Where code is kept, curated, and proven.**
>
>  —— 代码在此珍藏、策展、验证
> 命名体系：CodeTrove（代码宝库）＝ Codebase（宝库本体：托管）＋ CodeCurator（策展人：AI 评审）＋ CodeAssay（试金师：声明式测试）



***

## 一、项目定位

### 一句话概括

一个类 GitHub 的代码托管与质量保障平台。当前已运行主链路为 **Git push → MR → CodeCurator 确定性静态评审 → CodeAssay 受控声明式 HTTP 测试 → Check 汇总 → 合入门禁**；外部 LLM/RAG/Memory、流量录制、AI 生成用例和 Docker 沙箱作为后续增强。项目面向通用 Java 后端能力建设及秋招展示，只用真实、可运行、可验证的功能形成简历证据，不为堆砌技术引入与主链路无关的组件。

### 项目成功标准



1. **闭环真实可运行**：稳定演示从 push、创建 MR、确定性静态评审、受控声明式 HTTP 测试到门禁合并的当前完整流程；外部 LLM 和录制/AI 用例进入后续阶段。

2. **实现与简历一致**：只有已经完成、测试并留有可复现证据的能力，才能写成 “已实现”；规划能力必须明确标为演进方向。

3. **模块边界清晰**：Codebase、CodeCurator、CodeAssay、Check Aggregator 通过明确的领域模型和事件契约协作。

4. **复杂度可解释**：分片、缓存、消息可靠性、RAG、Memory、沙箱等设计必须有真实问题、指标或故障场景支撑。

5. **工程可复现**：提供 Docker Compose、初始化脚本、演示数据、自动化测试和操作文档，使他人能独立启动和验收。

### 开发前置设计基线

总设计负责说明项目愿景与主要技术方向；开发实现以 `docs/` 下的细化契约为准：

1. [`docs/01-scope-and-milestones.md`](docs/01-scope-and-milestones.md)：范围、阶段和出口条件。
2. [`docs/02-architecture.md`](docs/02-architecture.md)：模块边界、运行拓扑和关键数据流。
3. [`docs/03-domain-model.md`](docs/03-domain-model.md)：领域对象、状态和不变量。
4. [`docs/04-api-contract.md`](docs/04-api-contract.md)：REST 与 Git Smart HTTP 接口语义。
5. [`docs/05-event-contract.md`](docs/05-event-contract.md)：Kafka Envelope、事件类型和幂等规则。
6. [`docs/06-assay-spec.md`](docs/06-assay-spec.md)：声明式用例格式、表达式、Mock 和断言。
7. [`docs/07-security-model.md`](docs/07-security-model.md)：认证授权、LLM、录制与沙箱安全。
8. [`docs/08-acceptance-checklist.md`](docs/08-acceptance-checklist.md)：各阶段可执行验收清单。
9. [docs/09-frontend-design.md](docs/09-frontend-design.md)：GitHub 工程感与猫猫萌系融合的前端视觉规范。
10. [docs/10-m0-verification-record.md](docs/10-m0-verification-record.md)：M0 已执行验证、证据和剩余事项。
11. [docs/11-i18n.md](docs/11-i18n.md)：前端中英文切换、语言持久化和 README 双版本规范。
12. [docs/12-m1-auth-verification-record.md](docs/12-m1-auth-verification-record.md)：M1.1 认证实现、自动化测试和真实环境验收记录。
13. [docs/13-resume-feature-ledger.md](docs/13-resume-feature-ledger.md)：按功能沉淀已实现能力、技术栈、实现细节、验证证据和可用简历表述。
14. [docs/14-m1-repository-verification-record.md](docs/14-m1-repository-verification-record.md)：M1.2 仓库元数据、权限矩阵、JGit bare 存储与真实环境验收记录。
15. [docs/15-m1-git-smart-http-verification-record.md](docs/15-m1-git-smart-http-verification-record.md)：M1.3 Git Smart HTTP、Basic Auth、权限门禁、保护分支、资源限制与真实 Git CLI 验收记录。
16. [docs/16-m1-repository-browse-verification-record.md](docs/16-m1-repository-browse-verification-record.md)：M1.4 分支列表、Git tree/blob 浏览、安全边界、自动化与真实环境验收记录。
17. [docs/17-m1-merge-request-verification-record.md](docs/17-m1-merge-request-verification-record.md)：M1.5 MR、受限 Diff、普通/行级评论、并发控制与真实环境验收记录。
18. [docs/18-m1-merge-verification-record.md](docs/18-m1-merge-verification-record.md)：M1.6 push 后 MR head 历史同步、仓库级写锁、冲突检测、幂等 Merge 与失败窗口恢复验收记录。
19. [docs/19-m2-event-check-verification-record.md](docs/19-m2-event-check-verification-record.md)：M2 Transactional Outbox、Kafka 至少一次投递、幂等消费、DLQ、Check Suite/Run 与当前 head Merge 门禁验收记录。
20. [docs/20-m3-curator-verification-record.md](docs/20-m3-curator-verification-record.md)：M3 CodeCurator 确定性静态评审、并行/Judge/幂等评论、Resilience4j 降级与真实环境验收记录。
21. [docs/21-m4-assay-verification-record.md](docs/21-m4-assay-verification-record.md)：M4 CodeAssay 严格 Schema、受控 HTTP 执行、Kafka/Check 闭环与 Windows 八阶段验收记录。

若总设计与细化契约冲突，应先修正文档并形成一致结论，不允许在实现中静默选择其中一种口径。

### 实施原则



* 不设固定开发天数，以 “阶段验收通过” 而不是日期驱动推进。

* 先采用**模块化单体 + 独立异步 Worker**，保持模块边界；当部署、隔离或扩缩容需求出现后再拆微服务。

* 优先完成主链路，再逐步加入搜索、分片、RAG、Memory、录制、沙箱等增强能力。

* 每个阶段必须包含：设计说明、实现、自动化测试、故障场景验证、演示材料。

* 默认只支持 Java 仓库和 Git Smart HTTP；SSH、多语言和大规模高可用作为后续演进方向。

### 三个模块的关系



```
开发者 push 代码

&#x20;   ↓

[Codebase] 更新 MR head，在业务事务内写 Outbox

&#x20;   ↓

&#x20;   ├──→ [Check Aggregator] 创建当前 head Suite/Run，并写 `curator.review-requested`

&#x20;   │       [CodeCurator 当前 M3] 消费 command，逻辑/安全确定性 Skill 并行执行

&#x20;   │       输出：ReviewTask/Finding + 系统行级评论 + Curator 结果 Outbox

&#x20;   │

&#x20;   └──→ [CodeAssay 当前 M4] 消费 `assay.execution-requested`

&#x20;           从当前 head 加载严格 JSON 用例 → data_pre → 回环 WireMock → 响应断言

&#x20;           输出：Execution/CaseResult + TEST_REPORT + Assay 结果 Outbox

&#x20;   ↓

Check 汇总；只有当前 head 的 blocking `assay.integration=SUCCESS` 才允许 Merge

&#x20;   ↓

当前未实现：外部 LLM/RAG/Memory、任意 MR 构建部署、Docker 沙箱、DB/Bean Mock、流量录制与 AI 用例生成
```

### 为什么这三个模块组合在一起



| 模块          | 看的是什么     | 手段                     |
| ----------- | --------- | ---------------------- |
| CodeCurator | 新代码写得对不对  | 当前：确定性静态规则；后续：LLM 语义评审 |
| CodeAssay   | 老功能有没有被改坏 | 声明式用例 + mock 依赖 + 多级断言 |
| Codebase    | 协作基础设施    | Git 托管 + MR 流程         |

一个静态、一个动态、一个底座，三者通过事件驱动串联，不是堆砌。



***

## 二、整体架构

### 架构图



```
┌──────────────────────────────────────────────────────────┐

│          前端（Vue 3 + TypeScript + Vite）               │

│  仓库 / MR / Diff / 评论 / AI 评审 / 测试报告 / Check    │

└────────────────────────┬─────────────────────────────────┘

&#x20;                        │ HTTP / Git Smart HTTP

┌────────────────────────┴─────────────────────────────────┐

│       Spring Boot 模块化单体（REST API + JWT 鉴权）       │

│  Codebase │ Curator │ Assay │ Check Aggregator │ Auth   │

└──────┬──────────┬──────────┬──────────┬─────────────────┘

&#x20;      │          │          │          │ Transactional Outbox

&#x20;      │          │          │          ▼

&#x20;      │          │          │       Kafka

&#x20;      │          │          │   MR / Review / Test 事件

&#x20;      │          │          │          │

&#x20;      │          └──────────┴──────────┘

&#x20;      │                    异步 Worker

┌──────┴───────────────────────────────────────────────────┐

│ 当前基础设施：Windows MySQL / Docker Redis + Kafka / bare Git │

│ 进程内测试：WireMock Jetty 12（127.0.0.1 动态端口）          │

│ 后续增强：Qdrant、Docker 沙箱、Elasticsearch + Canal、分片  │

└──────────────────────────────────────────────────────────┘
```

### 部署边界



* 首个可运行版本采用单个 Spring Boot 应用承载 API 与领域模块，AI 评审和测试执行通过独立线程池或 Worker 异步执行。

* 裸 Git 仓库存放于单机本地文件系统，因此首版明确为单节点部署；多实例部署前必须先迁移到共享存储或实现仓库节点路由。

* Kafka 事件通过 Transactional Outbox 可靠发布；消费者按 `event_id` 幂等处理。

* 模块内部可以直接调用，跨异步边界只能通过版本化事件契约通信；不得共享未定义的内部表结构。

### 技术选型



| 层次        | 技术                                               | 选择理由                                  |
| --------- | ------------------------------------------------ | ------------------------------------- |
| 语言 / 核心框架 | Java 17 LTS + Spring Boot 3.x | 与本机现有 JDK 和主流企业 Java 基线一致，避免额外安装与环境切换 |
| Git 操作    | JGit `GitServlet` / `UploadPack` / `ReceivePack` | 服务端实现 Git Smart HTTP；首版不支持 SSH        |
| 数据库       | Windows 本机 MySQL；容量阶段再评估 ShardingSphere | 使用项目独立数据库和低权限账号；不修改已有业务库；实际版本在 M0 验证 |
| 搜索引擎      | 当前未引入；后续评估 Elasticsearch + Canal | 只有搜索延迟和查询需求证明价值后再接入 |
| 缓存        | Docker Redis 7.4.2 | 当前用于依赖基线与健康检查；Diff 缓存、短期记忆和限流仅在指标证明需要时引入；合并不使用分布式锁 |
| 消息队列      | Docker Kafka 3.9.1 KRaft + Transactional Outbox | MR/Curator/Assay 事件可靠发布、至少一次投递与幂等消费 |
| 文件存储      | 本地文件系统 | 存 bare Git repo，当前单节点 |
| 向量数据库     | 当前未引入；后续评估 Qdrant | RAG/Memory 接入后再使用 |
| 测试执行      | 当前：进程内 WireMock；后续：Docker 沙箱 | M4 是受控 HTTP 执行器，不运行任意 MR 代码 |
| LLM       | 当前未接入；后续调用 API | 未来扩展语义评审和候选用例生成 |
| 前端        | Vue 3 + TypeScript + Vite + Pinia + Vue Router + vue-i18n | GitHub 工程风、猫咪策展员视觉与中英文切换 |

### 与开源竞品的关系



| 开源项目              | 它做了什么                         | 本项目的差异                        |
| ----------------- | ----------------------------- | ----------------------------- |
| PR-Agent / Qodo   | AI PR 评审插件，跑在 GitHub/GitLab 上 | 本项目不依赖外部平台，Agent 内置在代码托管系统中   |
| Gitea / GitBucket | 纯代码托管，无 AI 能力                 | 本项目在托管基础上内置评审和测试闭环            |
| Keploy / GoReplay | 独立的流量录制回放工具                   | 本项目的测试引擎嵌入 MR 流程，不是独立工具       |
| 灯塔（内部）            | 声明式 JSON 用例 + mock + 多级断言     | 本项目参考其设计，用 Java 重新实现并接入 MR 门禁 |



***

## 三、模块一：Codebase（代码托管基础）

### 核心功能（MVP）



| 功能 | 说明 | 实施阶段 |
| --- | --- | --- |
| 用户注册 / 登录 | 用户名密码 + JWT | 阶段 1 |
| 仓库创建 / 列表 | bare repo 存本地文件系统 | 阶段 1 |
| Git clone/fetch/push | JGit GitServlet，支持 Smart HTTP；服务端实现 upload-pack / receive-pack | 阶段 1 |
| 分支列表 / 文件浏览 | 读取 git tree | 阶段 1 |
| MR 创建 / 列表 / 详情 | 源分支 → 目标分支 | 阶段 1 |
| Diff 计算 | JGit diff；Redis 缓存按容量治理阶段引入 | 阶段 1 / 7 |
| 行级评论 | Diff 行级评论 | 阶段 1 |
| MR 状态机 | `OPEN → MERGED / CLOSED` | 阶段 1 |
| Kafka 事件与 Outbox | MR 创建 / 更新事务内写 Outbox，可靠投递并幂等消费 | 阶段 2 |
| 代码 / MR 全文搜索 | Elasticsearch + Canal，指标证明需要后引入 | 阶段 7 |

当前实现状态：M1.5 已完成 MR 创建/列表/详情/更新/关闭、固定快照 Diff 和普通/行级评论；M1.6 已完成成功 push 后 OPEN MR head/version 与提交历史同步，以及带冲突检测、目标引用 CAS、持久化幂等和 PENDING 恢复的 `OPEN → MERGED` 基础合并；M2 已完成 Transactional Outbox、Kafka 幂等消费/DLQ、按 head 的 Check Suite/Run 与阻塞型 Merge 门禁；M3 已完成 CodeCurator 确定性静态评审基线；M4 已完成 CodeAssay 受控声明式 HTTP 测试基线，包括严格 Schema、受限表达式、`data_pre`、回环 WireMock、响应断言、Execution/CaseResult、Kafka command/result、幂等 TEST_REPORT 与 blocking Check。Review/Approval、外部 LLM、任意 MR 构建部署、Docker 沙箱、DB/Bean Mock、流量录制和 AI 用例生成仍未实现。

### 3.1 存储分层



* bare git repo 存文件系统（`/data/repos/{owner}/{repo}.git`）

* 元数据（用户、仓库、MR 基本信息）存 MySQL

* 高频写入表（通知、评论）做水平分表（见 3.2）

* 搜索数据同步到 Elasticsearch（见 3.4）

### 3.2 分库分表设计

**为什么分**：通知表（`mr_notification`：@我评审、我的 MR 被评论、CI 失败）和评论表（`mr_comment`）随每个 MR 动作持续写入，单表数据量大、写入高频。垂直分表只优化字段结构，解决不了数据量过大问题，因此采用水平分表。

**分片方案（ShardingSphere-JDBC）**：



| 表                 | 分片键       | 分片策略                                                   | 理由                         |
| ----------------- | --------- | ------------------------------------------------------ | -------------------------- |
| `mr_notification` | `user_id` | 2 库 4 表：库 = `user_id % 2`，表 = `floor(user_id / 2) % 4` | 避免库索引与表索引完全相关；用户高频查 “我的通知” |
| `mr_comment`      | `repo_id` | 按 repo\_id 取模分表                                        | MR 评论按仓库聚合查询               |

**说明**：分库分表属于容量治理阶段，不是首个闭环版本的前置依赖。只有在完成压测、明确单表容量与访问热点后才启用；启用前必须通过路由分布测试证明 8 个逻辑分片均能被覆盖。

**分片键选择理由**：



* user\_id /repo\_id 数据分布均匀，无热点

* 覆盖 90% 查询场景（用户查自己通知、仓库查自己评论）

* 避免跨片 JOIN

**扩容演进（一致性哈希）**：



* MVP 用取模分片，实现简单

* 取模扩容时映射全乱、要全量搬数据，因此演进为一致性哈希：构建闭合哈希环（0 \~ 2^32-1），分片键和节点都映射到环上，顺时针找第一个节点归属；扩容只影响相邻节点间数据

* 虚拟节点：节点少时容易数据倾斜，每个真实节点映射 100+ 个虚拟节点（如 `db_0#1`、`db_0#2`）打散在环上，再映射回真实节点，保证数据均匀

### 3.3 非分片键查询

当查询条件不是分片键时（比如管理员查 "某时间段所有评论"），不能全库全表扫描：



| 方案          | 说明                                      | 本项目      |
| ----------- | --------------------------------------- | -------- |
| Redis 映射表   | 维护查询字段→分片键映射，先查路由再定位分片                  | 补充       |
| 基因法         | ID 编码时嵌入分片信息（如通知 ID 中间几位就是 user\_id）    | 通知 ID 设计 |
| Canal 同步 ES | Canal 监听 MySQL binlog，异步同步到 ES，全局搜索走 ES | 主方案      |

### 3.4 Elasticsearch 搜索 + 分布式 ID

**ES 搜索**：



* 代码搜索：索引仓库文件内容，支持按文件名、文件内容搜索

* MR / 评论搜索：Canal 监听 binlog，MySQL 写入后异步同步到 ES

* 解决非分片键的全局搜索问题，避免全库扫描

* ES 与 MySQL 最终一致，短暂延迟可接受

**分布式 ID**：



* 分库分表后不能用数据库自增 ID（各表从 1 开始会重复）

* 采用雪花算法（Snowflake）：时间戳 + 工作机器 ID + 序列号，趋势递增

* 需要处理时钟回拨问题

### 3.5 其他技术点

**Diff 缓存**：以 `{baseCommit}:{headCommit}` 为 key 缓存 diff 结果到 Redis。

**并发控制（当前实现）**：单节点仓库写入使用公平 `Semaphore + 原子幂等 Lease` 串行 ReceivePack 与 REST Merge；目标引用再通过 JGit expected-old-object CAS 防止并发覆盖。该方案不是 Redisson/分布式锁，多实例前必须先解决仓库节点路由或共享存储一致性。

**Redis + Lua 令牌桶限流**：



* Webhook / API 入口用 Lua 脚本实现令牌桶，保证原子性

* Lua 脚本：先拿当前桶里的令牌数，再加补令牌（按时间差），最后判断够不够取一个

* 为什么不用 Redis-Cell / 滑动窗口：令牌桶允许突发流量，适合代码评审这种异步场景

* 为什么要用 Lua：GET + 计算 + SET 三步必须原子，否则并发会超卖



```
\-- key: rate\_limiter:{userId}

\-- ARGV: capacity(桶容量), rate(每秒补多少), now(当前时间)

local key = KEYS\[1]

local capacity = tonumber(ARGV\[1])

local rate = tonumber(ARGV\[2])

local now = tonumber(ARGV\[3])

local last = tonumber(redis.call('HGET', key, 'last\_time') or now)

local tokens = tonumber(redis.call('HGET', key, 'tokens') or capacity)

\-- 按时间差补令牌

local delta = math.max(0, now - last) \* rate

tokens = math.min(capacity, tokens + delta)

local allowed = 0

if tokens >= 1 then

&#x20;   tokens = tokens - 1

&#x20;   allowed = 1

end

redis.call('HMSET', key, 'tokens', tokens, 'last\_time', now)

redis.call('EXPIRE', key, 60)

return allowed
```

**降级熔断（当前 M3 已实现确定性 Skill 隔离，外部 LLM 为后续）**：



* 当前逻辑/安全 Skill 使用独立 Retry、CircuitBreaker、SemaphoreBulkhead 和 `CompletableFuture.orTimeout`；短暂异常有限重试，参数/输出校验异常不重试

* 任一 Skill 超时、隔离拒绝或熔断时，Curator Check 标记 `SKIPPED/SKILL_UNAVAILABLE`，不会伪装为 SUCCESS

* 外部 LLM Provider 接入后再补充 Provider 限流/错误分类、Prompt 脱敏和基于调用指标的熔断阈值；当前没有真实 LLM 调用证据

* Codebase 依赖的 ES / Canal 异常回退策略仍属容量治理规划，当前尚未接入 ES/Canal

* 首版熔断状态由各实例维护，不把运行状态写入 Redis；如果后续需要多实例全局流控，再单独设计协调机制

### Merge Check 状态与门禁规则



* Check 统一状态：`PENDING → RUNNING → SUCCESS / FAILED / SKIPPED / CANCELLED`。

* 声明式测试失败或执行异常：阻塞合并；AI 评审发现问题默认只警告，由后续仓库规则决定是否升级为阻塞。

* 确定性 Curator 评审不可用：非阻塞 Curator Check 标记 `SKIPPED`，不会伪装为成功；外部 LLM 尚未接入。

* 任一阻塞型 Check 仍在运行时禁止合并。

* MR 新增 commit 后，以旧 `head_commit` 为基准的 Check 全部标记过期或取消，不能用于当前合并判断。

* 合并操作在事务和仓库锁保护下再次校验目标分支、源分支 head、审批与 Check 状态，避免 TOCTOU 竞态。

### 八股映射



| 简历写法                           | 引导的八股                           |
| ------------------------------ | ------------------------------- |
| 通知表按 user\_id 水平分表（2 库 4 表）    | 垂直 vs 水平分表、分片键选择                |
| user\_id % 2 选库、% 4 选表         | 取模分片算法、路由计算                     |
| 一致性哈希 + 虚拟节点解决扩容和倾斜            | 哈希环、虚拟节点、数据迁移                   |
| Canal 监听 binlog 同步 ES          | Canal 原理、binlog、ES 与 MySQL 一致性  |
| 非分片键查询走 ES / 基因法               | 跨片查询、基因法、映射表                    |
| 雪花算法生成分布式 ID                   | Snowflake 结构、时钟回拨、号段模式          |
| ES 全文搜索代码和 MR                  | 倒排索引、分词器、ES vs MySQL like       |
| Redis 缓存 diff，基于 commit 对做 key | 缓存穿透 / 击穿 / 雪崩、缓存一致性            |
| 公平 Semaphore Lease + Git 引用 CAS 控制并发合并 | JVM 并发控制、幂等释放、CAS、单节点边界         |
| Kafka 异步分发 MR 事件               | 消息可靠性、幂等消费、消息积压                 |
| Redis + Lua 令牌桶限流              | Lua 原子性、令牌桶 vs 漏桶 vs 滑动窗口       |
| Resilience4j 降级熔断              | 熔断三态（关闭 / 打开 / 半开）、重试边界、隔离与降级策略 |



***

## 四、模块二：CodeCurator（确定性静态评审基线，外部 LLM 为后续）

当前已验收 M3 基线：Check 在当前 head Run 创建事务内写 `curator.review-requested`，Curator 以逻辑/安全两个确定性 Skill 并行分析受控 Diff 新增行，Judge 校验定位并按 SHA-256 fingerprint 去重，原子写 ReviewTask/Finding、系统行级评论和结果 Outbox；Resilience4j 与每 Skill 独立 timeout 提供 `SKIPPED/SKILL_UNAVAILABLE` 降级。外部 LLM、RAG、Memory、PR Compression 与模型路由均未实现。

以下内容中，确定性 Skill/并行/Judge/结果回写属于当前实现；LLM、RAG、Memory、PR Compression、大小模型路由和更多 Skill 属于阶段 5 演进设计。

核心目标模块最终将承载 **Skill 架构 / Memory / RAG**，但当前简历只能使用已经验收的确定性静态评审能力。

### 架构设计



```
MR 事件进入（Kafka 消费）

&#x20;   ↓

\[PR Compression 压缩层]

&#x20;   提取变更文件列表、函数签名，压缩 diff 到可控 token 数

&#x20;   ↓

\[编排器 Orchestrator]

&#x20;   │

&#x20;   ├── 判断变更文件类型，决定调度哪些 Skill

&#x20;   │

&#x20;   ├── 并行调度（CompletableFuture）：

&#x20;   │   ├── security-skill      → SQL注入、硬编码密钥、权限问题

&#x20;   │   ├── style-skill          → 命名规范、代码格式、注释完整性

&#x20;   │   ├── logic-skill          → 边界条件、空指针、异常处理、状态机

&#x20;   │   └── performance-skill   → N+1查询、循环RPC、大对象拷贝

&#x20;   │

&#x20;   ├── 每个 Skill 内部：

&#x20;   │   ├── RAG 检索相关上下文（编码规范、设计文档）

&#x20;   │   ├── 检查 Memory：有没有团队对这类代码的历史反馈

&#x20;   │   ├── 构建 prompt（压缩后的 diff + 检索到的上下文）

&#x20;   │   ├── 调用 LLM（大小模型分工，见 4.2）

&#x20;   │   ├── 失败重试 + 降级（见 4.4）

&#x20;   │   └── 输出结构化评论

&#x20;   │

&#x20;   ├── Memory 层：

&#x20;   │   ├── 短期：Redis 记录本次 MR 已报 (file,line,rule)，多 Skill 去重

&#x20;   │   └── 长期：Qdrant 存储开发者标记的"误报"，后续主动规避

&#x20;   │

&#x20;   └── \[Judge 聚合器]

&#x20;       汇总所有 Skill 结果 → 去重 → 按严重级别排序 → 输出最终评审报告
```

### 4.1 设计亮点

**PR Compression（diff 压缩）**：大 PR 几千行 diff 直接塞 LLM 会爆 context window。压缩策略：



* 只保留变更的函数签名和方法体

* 删除未变更的 import 和空行

* 相似变更合并描述

* 目标：把 diff 压缩到 LLM 上下文的 60% 以内

**多 Skill 并行 + Judge 聚合**：参考 Qodo 的多 Agent 架构，不是一个 prompt 干所有事：



* 每个 Skill 只关注一个维度，prompt 更聚焦

* CompletableFuture 并行调用，比串行快

* Judge 组件负责跨 Skill 去重、解决冲突、分级

**增量评审**：MR 更新了新 commit，不重新评审整个 diff，记录上次评审到哪个 commit，只评审新增变更。

**大小模型分工**：



* **大模型（GPT-4o / Claude）**：负责复杂逻辑判断 —— 安全漏洞、逻辑错误、架构问题

* **小模型（GPT-4o-mini / Qwen-Turbo）**：负责简单规则 —— 命名规范、格式问题、import 检查

* 路由策略：编排器先判断 diff 复杂度，简单 CRUD 用小模型（成本低、速度快），涉及核心逻辑用大模型

* 省钱：小模型成本是大模型的 1/10，80% 的评论可以小模型出

### 4.2 Memory 设计



| 记忆类型 | 存什么                              | 存哪里        | TTL     |
| ---- | -------------------------------- | ---------- | ------- |
| 短期记忆 | 本次 MR 已报的 (file, line, rule) 三元组 | Redis      | MR 生命周期 |
| 长期记忆 | 开发者标记的 "误报" 反馈、团队编码偏好            | Qdrant 向量库 | 持久      |

长期记忆工作流：



1. 开发者对评论点 "不是问题" → 记录：规则 + 文件 + 反馈

2. 下次评审类似代码时，向量检索命中该记忆

3. Prompt 中注入："注意：团队认为此处不需要 XXX"

### 4.3 RAG 设计

**知识库**：



* 仓库 README、设计文档、编码规范

* 历史 MR 的重要评审结论

* 团队约定的特殊写法

**流程**：



```
文档 → chunk 切分 → embedding → 存入 Qdrant

&#x20;                                   ↑

MR 变更文件 → 生成查询向量 → 检索 top-K → 拼入 LLM prompt
```

### 八股映射



| 简历写法                            | 引导的八股                              |
| ------------------------------- | ---------------------------------- |
| 多 Skill 并行评审 + Judge 聚合         | 多 Agent 编排、CompletableFuture、上下文管理 |
| PR Compression diff 压缩策略        | LLM 上下文窗口、token 优化                 |
| 双层 Memory（短期 Redis + 长期 Qdrant） | 短期 / 长期记忆、记忆淘汰、个性化                 |
| RAG 代码知识库（Qdrant）               | chunk 策略、embedding、HNSW 索引、混合检索    |
| 增量评审机制                          | 版本对比、增量处理                          |



***

## 五、模块三：CodeAssay（当前 M4：受控声明式 HTTP 基线；录制/AI/沙箱为后续）

当前已验收 M4 基线：Check 在当前 head 创建 blocking `assay.integration` Run 并写 `assay.execution-requested`；Assay 从该 Git commit 读取 `testcases/**/*.json`，执行 Draft 2020-12 严格 Schema、安全白名单、受限表达式、顺序 `data_pre`、回环 WireMock 和响应断言，持久化 Execution/CaseResult、幂等 TEST_REPORT 与结果 Outbox。Execution 使用 run-token 租约防止旧执行者覆盖新终态，报告 API 只返回当前 head。

当前不是通用沙箱，不自动构建或部署任意 MR 代码；Docker 隔离、DB 断言、Spring Bean Mock、fission、cleanup、流量录制和 AI 用例生成保留为阶段 5～6 演进方向。

以下完整链路描述最终目标，其中只有本节明确标注为 M4 的子集已经实现：

### 整体链路



```
线上/测试环境真实请求

&#x20;   ↓

\[5.1 流量录制] Spring Filter / AOP 拦截请求和响应

&#x20;   ↓

\[5.2 清洗脱敏] 过滤噪音、脱敏敏感字段、采样

&#x20;   ↓

\[5.3 AI 转用例] LLM 把录制的流量转成 JSON 用例

&#x20;   ↓

\[5.4 执行引擎] 加载 JSON 用例 → 数据准备 → mock → 执行 → 断言

&#x20;   ↓

MR 触发自动跑，作为合入门禁
```

### 5.1 流量录制（类似 FTF）

在被测系统里埋点，录制真实请求和下游调用：



| 录制对象           | 怎么录                                | 存什么                     |
| -------------- | ---------------------------------- | ----------------------- |
| HTTP 入口请求      | Spring OncePerRequestFilter        | URL、method、headers、body |
| HTTP 入口响应      | 同一 Filter 包装 response              | status、body             |
| 下游 RPC/HTTP 调用 | AOP 切面切 FeignClient / RestTemplate | 接口名、入参、返回值              |
| DB 操作          | MyBatis Interceptor                | SQL、参数、影响行数             |

**技术实现**：



* 写一个 `RecordFilter`，继承 `OncePerRequestFilter`，包装 request/response 获取 body；设置最大采集体积，超限内容只记录摘要

* 写一个 `@Aspect` 拦截受支持的 FeignClient / RestTemplate 调用，记录必要的入参与返回值

* 数据离开被测进程前先执行字段白名单和内存脱敏，默认丢弃 `Authorization`、Cookie、Token 等凭据；脱敏后的数据再异步发送到 Kafka

* 消费者将录制元数据存 MySQL，大体积请求 / 响应正文存受控文件目录；首版不引入 MongoDB

**关键点**：录制必须异步，不能因为录数据拖慢主链路；但 “异步” 不能成为原始敏感数据未经处理进入 Kafka 的理由。仓库级录制开关、数据保留时间、删除机制和访问权限必须可配置。

### 5.2 清洗脱敏

原始录制数据不能直接当测试用例：



1. **过滤噪音**：去掉健康检查、静态资源、监控请求

2. **进程内脱敏**：按字段名和数据类型识别身份证、手机号、银行卡号、密码、Token 等敏感信息；在发送 Kafka 前替换或删除。展示型掩码（如 `138****1234`）不能用于仍需执行的测试字段，后者应生成格式合法但无真实含义的替代值

3. **采样**：同一个接口 100 个请求只留 10 个有代表性的（不同入参分支）

4. **关联**：把 "支付请求 → 支付响应 → 调渠道 → 渠道响应 → DB 写入" 串成一条完整调用链

### 5.3 AI 转用例（LLM 把流量转成 JSON）

把清洗后的一条调用链丢给 LLM，让它输出灯塔格式的 JSON 用例：



```
输入给 LLM：

&#x20; \- HTTP 入口请求：POST /pay, body={channel:"alipay", amount:100}

&#x20; \- 入口响应：{success:true, tradeNo:"T123"}

&#x20; \- 下游调用：ChannelPay.pay({channel:"alipay", amount:100}) → {channelTradeNo:"CP456"}

&#x20; \- DB：insert into trade\_order(trade\_no, amount, status) values("T123", 100, "SUCCESS")

LLM 输出：

{

&#x20; "desc": "支付宝支付成功",

&#x20; "params": { "channel": "alipay", "amount": 100 },

&#x20; "mocks": {

&#x20;   "ChannelPay": {

&#x20;     "return": { "channelTradeNo": "\${#randomId()}" },

&#x20;     "check": { "amount": 100 }

&#x20;   }

&#x20; },

&#x20; "checkers": {

&#x20;   "response": { "success": true },

&#x20;   "db": { "table": "trade\_order", "expect": { "amount": 100, "status": "SUCCESS" } }

&#x20; }

}
```

**AI 做了什么人工要做的事**：



* 从真实响应里推断期望结果（`success: true`）

* 识别哪些是外部依赖需要 mock（ChannelPay）

* 识别 DB 落库了什么

* 把写死的 ID 换成 `${#randomId()}` 动态生成

### 5.4 用例配置格式（JSON Schema 管理，参考灯塔）

一个测试用例是一个 JSON 文件。正式实现前必须定义并版本化 JSON Schema，至少包含 `schema_version`、字段类型、必填项、表达式允许位置、未知字段处理策略和向后兼容规则。下例为设计示意：



```
{

&#x20; "desc": "支付宝渠道全额退款成功",

&#x20; "data\_pre": \[

&#x20;   {

&#x20;     "key": "paySuccess",

&#x20;     "call": "Pay",

&#x20;     "with": { "channel": "alipay", "amount": 100 },

&#x20;     "save\_as": "tradeOrder"

&#x20;   }

&#x20; ],

&#x20; "params": {

&#x20;   "refundReq": {

&#x20;     "tradeNo": "\${tradeOrder.tradeNo}",

&#x20;     "refundAmount": "\${tradeOrder.amount}",

&#x20;     "outRefundNo": "\${#randomId()}",

&#x20;     "payerId": 888888

&#x20;   }

&#x20; },

&#x20; "mocks": {

&#x20;   "ChannelRefund": {

&#x20;     "return": {

&#x20;       "channelRefundNo": "\${#randomId()}",

&#x20;       "refundAmount": "\${tradeOrder.amount}"

&#x20;     },

&#x20;     "check": {

&#x20;       "refundNo": "\${refundReq.outRefundNo}",

&#x20;       "refundAmount": "\${tradeOrder.amount}"

&#x20;     }

&#x20;   }

&#x20; },

&#x20; "checkers": {

&#x20;   "response": {

&#x20;     "success": true,

&#x20;     "errCode": "0",

&#x20;     "refundAmount": "\${refundReq.refundAmount}"

&#x20;   },

&#x20;   "db": {

&#x20;     "table": "refund\_order",

&#x20;     "expect": {

&#x20;       "payerId": 888888,

&#x20;       "refundAmount": 100,

&#x20;       "status": "SUCCESS"

&#x20;     }

&#x20;   }

&#x20; },

&#x20; "fission": {

&#x20;   "channel": \["alipay", "wechat", "bank"],

&#x20;   "refundAmount": \[100, 50, 1]

&#x20; }

}
```

### 5.5 Mock 能力边界

首版 CodeAssay 作为测试 SDK 接入示例被测服务，并明确区分两类 Mock：



* **Spring Bean 依赖**：仅拦截由 Spring 容器管理、实现明确接口或代理点的依赖，通过测试配置替换 Bean 或动态代理实现返回值配置和入参校验。

* **HTTP 下游依赖**：使用 WireMock 提供独立 Stub Server，由测试启动参数将下游地址切换到 Stub Server。

* **不承诺的能力**：不宣称可直接拦截任意第三方容器、静态方法、本地私有方法或未经过受控客户端的网络调用；这些能力需要 Java Agent 或字节码增强，列入后续演进。

### 5.6 执行引擎流程



```
读取 JSON 用例

&#x20;   ↓

表达式解析器：渲染 \${var.path} 变量引用、\${#func()} 函数调用

&#x20;   ↓

数据准备（data\_pre）：按顺序调前置接口，结果存入上下文

&#x20;   ↓

注册 mock：动态代理拦截 ChannelRefund 等外部调用

&#x20;   ↓

执行被测接口：传入渲染后的 params

&#x20;   ↓

多级断言：

&#x20; ├── 响应断言：检查 response 字段

&#x20; ├── DB 断言：查数据库验证落库状态

&#x20; └── Mock 断言：检查下游调用传了什么参数

&#x20;   ↓

输出结果：通过/失败 + 失败字段差异
```

### 5.7 用例裂变

写一份配置，按参数笛卡尔积自动展开：



* channel: 3 个渠道 × amount: 3 个金额 = 9 个 case

* 不用手写 9 个文件，引擎自动展开

### 5.8 MR 集成（当前 M4 与后续增强边界）



1. 当前：Check 创建 Run 后通过 command 触发，读取 MR 当前 head 的 `testcases/**/*.json`。

2. 当前：在应用进程中执行受控 HTTP 用例；`mock` target 使用回环 WireMock，`application` target 由服务端配置。

3. 当前：汇总结果、回写 TEST_REPORT，全部启用用例通过才使 blocking Check 绿色。

4. 后续：Docker 容器构建/运行 MR 代码、DB/Bean Mock、fission 与 cleanup。

### 5.9 技术设计要点（当前与规划分层）



| 状态 | 设计 | 说明 |
| --- | --- | --- |
| 已实现 | 严格 JSON Schema | Draft 2020-12、未知字段拒绝、文件/请求资源上限 |
| 已实现 | 受限表达式 | `${context...}`、`${#uuid()}`、`${#randomLong()}`，禁止脚本/反射/文件/进程 |
| 已实现 | HTTP Mock | WireMock 回环动态端口、固定 JSON 响应与调用次数断言 |
| 已实现 | 响应断言 | 7 类运算符、结构化 expected/actual 差异与敏感路径遮蔽 |
| 已实现 | 可靠执行 | Kafka command/result、消费幂等、run-token 租约与 blocking Check |
| 规划 | 流量录制/清洗/AI 转用例 | 阶段 6；当前无实现证据 |
| 规划 | Spring Bean/DB/Mock 入参断言 | 阶段 5 |
| 规划 | Docker 沙箱 | 阶段 5；当前不构建或运行任意 MR 代码 |

### 面试映射（只使用已实现项）

| 当前可写 | 可展开问题 |
| --- | --- |
| Draft 2020-12 严格声明式用例协议 | 配置驱动、Schema 演进、fail-closed 校验 |
| 自定义受限表达式与顺序 data_pre | 解释器设计、上下文依赖、为什么不用 SpEL/Groovy |
| WireMock Jetty 12 回环动态 Stub | HTTP 隔离、依赖版本冲突与选型 |
| 响应体有界读取与结构化差异 | 内存安全、报告设计、敏感字段脱敏 |
| run-token 租约与 Kafka 幂等 | 崩溃恢复、旧执行者覆盖、至少一次语义 |



***

## 六、可行性总结

### 6.1 整体评估



| 模块           | 难度    | 可行性 | 建议                            |
| ------------ | ----- | --- | ----------------------------- |
| Codebase 基础  | ★★★☆☆ | 高   | JGit 成熟，主要写业务逻辑               |
| CodeCurator  | ★★★★☆ | 高   | 调 LLM API，核心是编排和 prompt       |
| CodeAssay 引擎 | ★★★★☆ | 中高  | 解析器和 mock 是核心，参考灯塔设计          |
| 前端           | ★★★☆☆ | 中   | Vue3 + Element Plus，AI 辅助生成组件 |

### 6.2 阶段验收路线（不设固定开发期限）

项目时间不设上限，但仍按依赖顺序和阶段验收推进，避免多个未闭环模块同时扩张。每一阶段只有在设计、实现、测试、故障验证和演示材料全部完成后，才进入下一阶段。

#### 阶段 0：工程基线与契约



* 明确模块边界、领域模型、REST API、错误码、Kafka 事件和 CodeAssay JSON Schema

* 建立模块化单体工程、前端工程、Windows 本机 MySQL 接入、缺失中间件的 Docker Compose、数据库迁移和 CI

* 定义统一日志、Trace ID、异常响应、配置管理与测试分层

* **验收**：一条命令启动依赖和应用；健康检查、数据库迁移、基础单元测试与 CI 全部通过

#### 阶段 1：Codebase 协作主链路

* 前端同步提供 en-US 与 zh-CN，英文为默认语言；顶栏可切换并持久化用户选择
* README.md 与 README.zh-CN.md 保持章节、命令和运行口径一致



* 完成用户、仓库、Git Smart HTTP、分支浏览、MR、Diff、评论和基础合并

* 明确 merge commit /fast-forward 策略及冲突处理；首版只允许无冲突合并

* **验收**：外部 Git 客户端能 clone、push；网页能创建 MR、查看 Diff、评论和合并

#### 阶段 2：事件与 Check 门禁



* 引入 Transactional Outbox、Kafka、幂等消费、重试与死信状态

* 建立统一 Check 状态机：`PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED`

* 新 push 后旧 Check 自动失效；运行中或失败的阻塞型 Check 禁止合并

* **验收**：模拟重复消息、发送失败、消费者重启和新提交覆盖，状态仍保持正确

#### 阶段 3：CodeCurator 确定性静态评审最小闭环（已完成）



* 已完成逻辑与安全两个确定性 Skill、受控结构化输出、真实并行执行、Judge 去重/定位校验和系统行级评论

* 已接入 Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead 与每 Skill 独立 timeout，不可用时非阻塞 Check 标记 `SKIPPED`

* 已通过固定缺陷样例、重复 command、新 head 隔离、脱敏和最终 JAR 八阶段验收；外部 LLM Schema/限流/Prompt 脱敏留待阶段 5

#### 阶段 4：CodeAssay 最小闭环（已完成）



* 已完成版本化 JSON Schema、受限表达式、顺序 `data_pre`、WireMock、响应断言、结构化报告和 TEST_REPORT 回写

* 已完成 Kafka command/result、数据库消费幂等、run-token 租约恢复、当前 head 报告 API 与 blocking Check

* 已通过 82 项自动化测试与最终 JAR 八阶段实机验收；当前测试 target 使用进程内 WireMock，未自动构建/部署任意 MR 代码

* 未完成的 Docker 沙箱、DB/Bean Mock、fission、cleanup、流量录制和 AI 用例生成进入后续阶段

#### 阶段 5：AI 与测试能力增强



* Curator：PR Compression、增量评审、大小模型路由、RAG、短期去重、误报反馈 Memory

* Assay：DB 断言、Spring Bean Mock、Mock 入参断言、用例裂变、Docker 隔离

* **验收**：记录压缩率、评审耗时、Token 消耗、误报反馈命中率、用例通过率和隔离效果

#### 阶段 6：流量录制与 AI 用例生成



* 完成请求与下游调用采集、进程内脱敏、采样去重、调用链关联和数据生命周期管理

* LLM 生成的用例必须经过 JSON Schema 校验、安全校验和人工确认后才能进入正式用例库

* **验收**：从一条真实但无敏感信息的调用链生成可执行用例，并能重复回归

#### 阶段 7：容量与可用性治理



* 基于压测结果按需加入 Redis Diff 缓存、限流、ShardingSphere、ES + Canal、Docker 资源限制等

* 分库分表和搜索同步不是默认必选项，必须用数据量、延迟或故障指标证明引入价值

* **验收**：提供压测报告、容量基线、降级演练和一致性验证结果

#### 阶段 8：交付与简历证据



* 完善 README、架构图、ADR、接口示例、自动化测试、Demo 视频和可复现实验数据

* 根据实际完成状态生成简历描述，不使用未落地的规划项

* **验收**：陌生开发者按文档可独立启动；2 分钟 Demo 可稳定完成主闭环

* M0～M4 验收通过后形成 MVP；首次发布前完成 Secret、隐私、许可证、生成物与大文件扫描，推送后验证远端内容与 CI。

* GitHub 发布目标：`https://github.com/Tmiemie/CodeTrove`，公开仓库，MIT License。

### 6.3 分阶段范围表



| 能力                           | 首次进入阶段 | 说明                   |
| ---------------------------- | ------ | -------------------- |
| Git Smart HTTP、MR、Diff、评论、合并 | 阶段 1   | 协作底座，必须真实可用          |
| Outbox、Kafka、Check 状态机与门禁    | 阶段 2   | 主闭环的可靠性基础            |
| 两个确定性 Review Skill + Judge | 阶段 3 | 已完成可复现静态评审基线；外部 LLM 后续接入 |
| JSON 用例 + HTTP Mock + 响应断言   | 阶段 4   | 声明式测试的最小可执行核心        |
| RAG、Memory、DB/Mock 断言、Docker | 阶段 5   | 在基线数据可测后增强           |
| 流量录制、脱敏、AI 生成用例              | 阶段 6   | 在执行引擎稳定后接入           |
| ES/Canal、分片、容量治理             | 阶段 7   | 由压测数据触发，不为技术堆砌提前引入   |
| SSH、多语言、多节点 Git 存储           | 后续演进   | 不属于当前默认承诺            |



***

## 七、简历核心职责（完成后按证据生成，以下为目标模板）

> 本节描述最终目标，不代表当前已经实现。每一条必须在对应阶段验收通过，并附测试、指标或 Demo 证据后，才能进入正式简历。

**CodeTrove — 智能代码协作与质量保障平台** | 个人项目 | 2026.XX - 2026.XX

**项目简介**：基于 Spring Boot 实现类 GitHub 代码托管平台，内置 AI 代码评审 Agent 和流量录制 + AI 生成的声明式集成测试引擎，通过 Kafka 事件驱动实现 MR → 智能评审 → 自动测试 → 合入门禁的全链路质量闭环。

**核心职责**：



1. **代码托管、事件与 MR 门禁（当前已验收部分）**：基于 JGit 实现仓库托管、Git Smart HTTP、Merge Request、受限 Diff/评论和冲突安全的幂等 Merge；使用公平 Semaphore Lease 串行单节点仓库写入并以 Git 引用 CAS 防止覆盖；通过 Transactional Outbox 与 Kafka 至少一次投递分发 MR 事件，以数据库唯一键实现消费幂等、有限重试和 DLQ；按 MR/head 建立 Check Suite/Run，旧 head 自动失效，当前阻塞型 Check 未成功时拒绝合并。ShardingSphere、Elasticsearch/Canal、Redis Diff 缓存与多实例协调仅在容量指标证明需要后进入后续治理。

2. **CodeCurator（AI 代码评审 Agent）**：

* 采用多 Skill 并行架构，将评审拆分为安全、规范、逻辑、性能等独立 Skill，编排器按变更类型调度，CompletableFuture 并行执行，Judge 组件聚合去重

* 设计 PR Compression 策略压缩 diff，控制 LLM token 输入；支持增量评审，只分析新增 commit

* 双层 Memory 机制：短期记忆（Redis）避免重复报告，长期记忆（Qdrant）沉淀团队误报反馈

* 构建 RAG 代码知识库，将设计文档和编码规范向量化，评审时检索相关上下文

1. **CodeAssay 流量录制 + AI 生成 + 声明式测试引擎**：

* 参考灯塔 + FTF 设计，通过 Spring Filter 和 AOP 切面录制真实请求与下游调用链，异步发 Kafka；清洗脱敏后用 LLM 自动将流量转成 JSON 测试用例

* 实现 JSON 声明式用例引擎：表达式渲染（变量引用 + 随机函数）、动态代理 Mock 外部依赖并校验入参、三级断言（响应 + DB + Mock 入参）

* 用例裂变支持参数笛卡尔积批量生成；Docker 沙箱隔离执行，MR 触发自动回归，失败用例回写 MR 评论

**技术栈目标**：Java 17、Spring Boot 3.x、JGit、Windows 本机 MySQL、Redis、Kafka、Qdrant、Docker、Vue 3、Element Plus、LLM API；ShardingSphere、Elasticsearch、Canal 与多实例协调方案按容量治理阶段引入。具体版本在工程基线阶段锁定，并通过依赖清单统一管理。



***

## 八、面试引导路径



```
面试官问项目

&#x20; → 讲整体架构（Kafka 事件驱动，MR → 评审 → 测试 → 门禁）

&#x20;   │

&#x20;   ├─ Codebase 线：

&#x20;   │   → "通知表数据量怎么办？" → 引出分库分表（水平分表→一致性哈希→虚拟节点→非分片键查询→雪花ID）

&#x20;   │   → "全局搜索怎么搞？" → 引出 ES + Canal 同步 binlog

&#x20;   │   → "diff 缓存怎么做的？" → 引出 Redis（穿透/击穿/雪崩）

&#x20;   │   → "怎么防刷？" → 引出 Redis+Lua 令牌桶、限流算法

&#x20;   │   → "下游挂了怎么办？" → 引出 Resilience4j 超时、重试、隔离、熔断与降级

&#x20;   │

&#x20;   ├─ CodeCurator 线：

&#x20;   │   → "评审怎么拆的？" → 引出 Skill 多 Agent 编排 + CompletableFuture

&#x20;   │   → "LLM 成本怎么控？" → 引出大小模型分工、prompt 压缩

&#x20;   │   → "上下文从哪来？" → 引出 RAG（chunk/embedding/向量检索）

&#x20;   │   → "误报怎么处理？" → 引出双层 Memory（Redis短期 + Qdrant长期）

&#x20;   │   → "LLM 超时怎么办？" → 引出重试（指数退避）+ 降级 + 熔断

&#x20;   │

&#x20;   └─ CodeAssay 线：

&#x20;       → "用例怎么来的？" → 引出流量录制（Filter/AOP）→ 清洗脱敏 → LLM 转 JSON

&#x20;       → "测试怎么跑的？" → 引出声明式引擎（表达式解析、data\_pre）

&#x20;       → "外部依赖怎么隔离？" → 引出动态代理 Mock + 入参校验

&#x20;       → "断言怎么做？" → 引出三级断言（响应+DB+Mock入参）
```

## 九、风险和注意事项



1. **不要自己实现 Git 协议**—— 用 JGit，简历写 "基于 JGit 实现"

2. **LLM 幻觉**—— 准备几个实际评审例子，说明怎么控制误报

3. **测试用例真实性**——MVP 手动写 5-10 个有代表性的用例，诚实说 "设计上支持 AI 生成和流量录制"

4. **前端用 Vue3 + Element Plus，AI 辅助生成**—— 参考 Gitea/GitLab 都是 Vue，组件库现成，不自己写样式

5. **录 Demo 视频**：创建 MR → AI 自动评审出评论 → 测试自动跑 → 全绿可合并，2 分钟

6. **了解竞品**：PR-Agent（多 Agent 评审）、Qodo（Context Engine）、灯塔（声明式用例），面试能说清楚差异