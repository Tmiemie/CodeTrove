# CodeTrove 范围与里程碑

## 1. 文档目的

本文把总设计中的目标拆解为可验收的实施阶段。项目不设固定工期，但禁止跨过当前阶段的必要验收项直接堆叠后续技术。

## 2. 产品目标

CodeTrove 是一个面向 Java 后端项目的代码协作与质量保障平台，提供以下闭环：

1. 开发者通过 Git Smart HTTP 推送代码。
2. 在平台创建 Merge Request（MR）。
3. CodeCurator 对变更做 AI 静态评审。
4. CodeAssay 执行声明式集成测试。
5. Check Aggregator 汇总结果并执行 Merge 门禁。
6. 结果以行级评论、测试报告和 Check 状态反馈给开发者。

## 3. 项目边界

### 3.1 当前承诺

- Java 17、Spring Boot 3.x。
- Vue 3、Element Plus、Vite。
- Windows 本机 MySQL 作为关系数据库；项目使用独立数据库和独立低权限账号，不覆盖已有业务库。
- Redis、Kafka 等当前缺失且已使用的中间件通过 Docker Compose 提供；WireMock 作为 M4 进程内测试依赖运行。Qdrant、Elasticsearch、Canal 和 Docker 测试沙箱仅在后续里程碑真实需要时引入。
- 单节点 Git 裸仓库存储。
- Git Smart HTTP：clone、fetch、push。
- 用户名密码登录与 JWT 鉴权。
- MR、Diff、行级评论、Check 和 Merge 门禁。
- 前端支持 en-US 与 zh-CN，默认英文，用户选择保存在浏览器本地。
- 提供英文 README.md 与中文 README.zh-CN.md。
- Java 仓库的 AI 评审与声明式测试。
- Windows 本机直接运行后端和前端；Docker Compose 复现除 MySQL 外的缺失中间件。

### 3.2 当前不承诺

- Git SSH 协议。
- GitHub/GitLab 全量兼容。
- 多语言代码分析。
- 多节点 Git 仓库高可用。
- 任意进程、静态方法或非受控客户端的透明 Mock。
- 未经人工确认的 AI 用例自动进入正式用例库。

## 4. 核心非功能目标

| 维度 | 基线目标 |
| --- | --- |
| 正确性 | Merge 前重新校验 head commit、权限和阻塞型 Check |
| 可靠性 | 领域事务与 Kafka 发布通过 Transactional Outbox 解耦 |
| 幂等性 | 所有异步消费者按 `event_id` 幂等 |
| 可观测性 | HTTP、事件和异步任务统一携带 `trace_id` |
| 安全性 | 最小权限、敏感字段脱敏与受控 target/path/header；Docker 沙箱资源限制属于 M5 |
| 可复现性 | 本机 MySQL 接入说明、Docker Compose 中间件、数据库迁移、种子数据、自动化测试 |
| 可解释性 | 复杂组件必须对应真实问题和可测指标 |

## 5. 阶段与出口条件

### M0：工程与契约基线

**范围**

- 模块结构、依赖治理、配置分层。
- 数据库迁移、统一响应、异常处理、日志和 Trace ID。
- Docker Compose、CI、测试目录。
- 本目录全部设计文档评审通过。

**出口条件**

- 按 README 启动 Windows 本机 MySQL、Docker 中间件、后端和前端。
- 健康检查成功。
- 数据库从空库完成迁移。
- 单元测试和静态检查在 CI 中运行。

### M1：Codebase 主链路

> 当前进度：M1.1 认证、M1.2 仓库/权限、M1.3 Git Smart HTTP、M1.4 分支/文件浏览、M1.5 MR/Diff/评论与 M1.6 head 同步/冲突检测/幂等 merge commit 均已通过自动化和真实环境验收。Codebase 的基础协作与合并链路已完成；随后完成的 M2 已在此基础上接入当前 head 的阻塞型 Check 门禁。

**范围**

- 注册、登录、仓库和成员权限。
- Git Smart HTTP clone/fetch/push。
- 分支浏览、文件浏览、MR、Diff、评论。
  - M1.4 浏览 API：分支列表；按分支名或 40 位 commit ID 浏览当前目录；读取文件元数据与受限文本内容。
  - `ref` 不接受 tag、缩写 SHA、reflog 或 `^{}` 等 revision 表达式；路径禁止绝对路径、反斜杠、`.`/`..` 段与空段。
  - tree 使用不透明游标分页；blob 仅内联 UTF-8 文本，默认最大 1 MiB，二进制和超大文本只返回元数据与未内联原因。
- 无冲突 merge commit 合并。
- M1.5 首版实现 MR 创建/列表/详情/编辑/关闭、基于创建时 `base_commit/head_commit` 快照的 Diff、普通评论与行级评论。
- M1.6 实现成功 push 后同步受影响 OPEN MR 的 `head_commit` 并记录 head 历史；源分支删除时保留最后快照，Merge 明确失败。
- M1.6 首版只支持无冲突 `MERGE_COMMIT`：在单节点仓库级 JVM 锁内重新读取源/目标引用、校验 `expectedHeadCommit`、执行 JGit 三方合并并以引用 CAS 更新目标分支。
- Merge 使用持久化 `Idempotency-Key` 操作记录；相同请求重放返回同一结果，Git 引用已更新但数据库尚未完成时可从 `PENDING` 操作恢复。
- M1.6 阶段不包含 Review/Approval 或 Check 门禁；随后完成的 M2 已在 Merge 临界区接入当前 head 的阻塞型 Check，Review/Approval 仍未实现。

**出口条件**

- 外部 Git 客户端可完成 clone、commit、push。
- 可创建 MR、查看 Diff、发表行级评论并合并。
- 无权限操作、分支变化和合并冲突均有确定错误响应。

### M2：事件与 Check 门禁

> M2 阶段历史口径：当时不执行真实 Curator 或 Assay，当前 Suite 使用非阻塞 `curator.review=SKIPPED/NOT_IMPLEMENTED` 与阻塞 `assay.integration=PENDING` 表达执行器边界。随后 M3/M4 已分别接入真实确定性静态评审和受控声明式 HTTP 执行器；本段保留用于说明 M2 的阶段验收范围。

**范围**

- Transactional Outbox、Kafka、幂等消费。
- Check Suite、Check Run 和 Merge 门禁。
- 新 commit 使旧 Check 失效。

**出口条件**

- 数据库提交成功但 Kafka 暂时不可用时，事件最终仍可发布。
- 重复事件不产生重复 Check 或评论。
- 阻塞型 Check 未成功时不能合并。

### M3：CodeCurator 基线

> 实施口径：先完成真实可复现的 Curator 编排与确定性静态评审闭环，不把规则扫描器冒充 LLM。逻辑、安全两个 Skill 对受限 Diff 的新增行进行结构化分析，并行执行后由 Judge 校验定位、聚合去重；结果持久化为 ReviewTask/ReviewFinding、写入系统行级评论，并通过 Outbox/Kafka 更新非阻塞 Curator Check。外部 LLM Provider、RAG 和 Memory 属于后续增强。

**范围**

- 逻辑、安全两个确定性 Review Skill。
- 受限 Diff 输入、并行执行、结构化输出、Judge 聚合去重。
- ReviewTask/ReviewFinding、幂等系统行级评论和非阻塞 Curator Check。
- Resilience4j 超时、有限重试、隔离和熔断；过期 head 取消，Skill 故障降级为 `SKIPPED`。

**出口条件**

- 固定缺陷样例可以稳定产出定位准确的评论。
- 重复结果和重复事件不产生重复 Finding/评论。
- 旧 head 结果不能覆盖当前评审。
- Skill 超时、熔断或依赖不可用时 Check 为 `SKIPPED`，不伪造成功结论。
- 文档与简历明确写“确定性静态评审基线”，在真实 LLM Provider 接入前不写“LLM/AI 评审已实现”。

### M4：CodeAssay 声明式 HTTP 测试基线（已完成）

> 已验收：受控测试环境 HTTP 执行器从 MR 当前 head 的 `testcases/**/*.json` 读取用例，经严格 JSON Schema 和安全策略校验后执行 `data_pre → WireMock Stub → 主请求 → 响应断言 → 报告/Check`。测试 target 仅来自服务端配置或进程内 WireMock，用例不能指定任意 host。82 项自动化测试与最终 JAR 八阶段 Windows 实机验收均通过。当前不自动构建、部署或运行任意 MR 分支代码，因此不能写成“通用代码沙箱/任意项目集成测试”。

**范围**

- 当前 head 用例发现、版本化 JSON Schema、未知字段拒绝和执行前安全校验。
- 受限表达式、顺序 `data_pre`、受控 HTTP 请求与 WireMock HTTP Stub。
- 响应断言、CaseResult/ExecutionReport、幂等 TEST_REPORT 系统评论。
- `assay.execution-requested/started/completed` command/result 事件和 blocking Check 更新。
- 旧 head 取消、重复 command 幂等、无用例/Schema 非法/断言失败的确定状态。

**出口条件**

- 成功用例可重复通过并把 blocking `assay.integration` 更新为 `SUCCESS`。
- 失败用例报告包含 assertion path、operator、expected 与 actual，Check 为 `FAILED`。
- 非法用例在任何 HTTP 请求或 Stub 执行前被 Schema/安全策略拒绝。
- 相同 command 重放不重复 Execution、CaseResult、报告评论或结果事件。
- 旧 head 任务为 `CANCELLED/MR_STALE`，不能覆盖当前 Check。
- 文档明确区分“受控 HTTP 执行器”和尚未实现的 MR 分支构建部署、DB/Bean Mock、Docker 沙箱。

### M4.5：前后端真实联调与可交互产品 MVP（已完成）

> 已验收：核心页面已从 `mock.ts` 切换到真实 REST API，浏览器完成登录 → 仓库 → MR → Diff/评论 → Curator/Assay/Check → Merge 主链路。Access Token 与当前仓库只保存在 `sessionStorage`；无后端 API 的设置项只读展示或明确标注边界。验证证据见 [docs/23-m45-frontend-backend-integration-verification-record.md](23-m45-frontend-backend-integration-verification-record.md)。

**范围**

- 注册、登录、退出、当前用户恢复和 401 自动清理会话。
- 仓库列表、仓库创建、分支/tree/blob 浏览与仓库上下文选择。
- MR 列表/详情、Diff、评论、当前 Check、Curator Findings、Assay Test Report 和真实 Merge。
- 加载、空状态和后端 400/401/403/404/409/503 错误展示。
- Settings、成员管理、Check rerun、Review disposition、归档删除等无后端 API 的入口改为只读边界或隐藏，不伪造保存成功。

**出口条件**

- 浏览器可以注册或登录真实账号，刷新后在当前标签页恢复用户与仓库上下文，退出后清理 Token。
- 无仓库用户可以创建仓库；已有仓库用户可浏览真实分支、tree 和 UTF-8 blob。
- 可查看真实 MR、Diff、评论、Check、Curator Finding 与 Assay Report，并可提交普通评论。
- 满足后端门禁时可通过 UI 发起真实 `MERGE_COMMIT`；失败时展示后端结构化错误，不伪装成功。
- 前端不再以 `mock.ts` 作为上述核心链路事实源；演示专用或尚无后端能力的内容必须明确标注。
- Prettier、Vue TypeScript、Vite Build、后端回归和浏览器真实链路验收通过。

### M5：智能与测试增强

**范围**

- PR Compression、增量评审、模型路由。
- RAG、短期去重、误报 Memory。
- DB 断言、Spring Bean Mock、Mock 入参断言。
- 用例裂变和 Docker 隔离。

**出口条件**

- 有可复现的压缩率、耗时、Token、误报反馈和隔离验证数据。
- Docker 执行有 CPU、内存、网络和超时限制。

### M6：流量录制与 AI 用例生成

**范围**

- HTTP 与受控下游调用录制。
- 进程内脱敏、采样、去重和调用链关联。
- LLM 生成候选用例、Schema 校验和人工确认。

**出口条件**

- 原始凭据不会进入 Kafka 或持久化层。
- 一条脱敏调用链能生成、审核并执行为稳定用例。

### M7：容量与可用性治理

**范围**

- 基于压测结果决定缓存、ES/Canal、分片和资源治理。
- 故障演练、一致性验证和容量报告。

**出口条件**

- 每个新增组件都有引入前后的指标对比。
- 分片、缓存与搜索同步均有一致性和故障恢复测试。

### M8：交付与简历证据

**范围**

- README、架构图、ADR、运行手册、演示数据和 Demo 视频。
- 根据实际验收结果生成简历描述。

**出口条件**

- 陌生开发者可以按文档独立启动。
- 2 分钟主链路 Demo 稳定复现。
- 简历每项声明都能关联代码、测试或指标证据。

## 6. MVP 与 GitHub 发布边界

- 可交互产品 MVP 定义为 M0～M4.5：工程基线、Codebase、事件与门禁、CodeCurator 基线、CodeAssay 基线，以及浏览器真实前后端联调。
- M0～M4 表示后端主链路 MVP；M4.5 已通过浏览器真实联调，因此当前可描述为完整可交互产品 MVP。
- MVP 必须在 Windows 本机完成构建、启动和端到端验收；Docker 只承载本机缺失的中间件。
- 首次上传 GitHub 前必须通过 Secret、隐私、许可证、生成物和大文件扫描；.env、数据库密码、JWT/LLM 密钥、运行数据、裸 Git 仓库和 IDE 私有配置不得提交。
- GitHub 建仓、首次 push 和任何后续发布属于外部不可逆操作，必须在本地提交准备完成后由用户单独确认执行。
- M5～M8 作为 MVP 后续增强，按阶段继续交付。

## 7. 变更管理

- 新功能先归入某一里程碑，并补充验收条件。
- 每个可独立验收的功能完成后，必须同步更新 `docs/13-resume-feature-ledger.md`，记录新增能力、技术栈、实现细节、验证证据、可用简历表述和未完成边界。
- 修改公共状态、API 或事件字段时，必须同步更新相关契约文档。
- 不兼容变更必须升级 API、事件或 Schema 版本。
- 阶段未验收的问题进入问题清单，不得用“后续优化”掩盖主链路缺陷。
