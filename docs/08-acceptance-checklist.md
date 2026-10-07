# CodeTrove 验收清单

## 1. 使用方式

- 每个里程碑完成时复制本清单对应章节并附证据链接。
- `[ ]` 只能在实际验证通过后改为 `[x]`。
- 证据至少包含：测试命令/CI、关键日志或截图、输入与预期结果。
- “文件存在”“接口返回 200”不能替代业务正确性验证。

## 2. M0 工程基线

> 当前执行证据见 [docs/10-m0-verification-record.md](10-m0-verification-record.md)。M0 本机工程基线已通过；GitHub 发布门槛单独管理。

- [x] Java 17 与 Spring Boot 3.x 版本锁定。
- [x] Windows 本机 MySQL 的版本、字符集、时区和连接参数已验证兼容。
- [x] 项目使用独立数据库和低权限账号，不覆盖或修改其他数据库。
- [x] 后端模块边界与依赖方向可由构建验证。
- [x] 前端可以独立构建。
- [x] Docker Compose 只启动当前阶段缺失的中间件，不重复部署本机 MySQL。
- [x] 空项目数据库可以完成全部迁移。
- [x] 健康检查区分应用存活与依赖就绪。
- [x] 统一错误响应包含 trace ID，不含堆栈或密钥。
- [x] 单元测试、格式检查和静态分析已纳入 CI 配置；远端运行证据待首次 GitHub push。
- [x] 配置示例不包含真实 Secret。
- [x] `docs/` 中契约无已知冲突。

## 3. M1 Codebase

### Auth 与权限

- [x] 注册、登录、错误密码、重复用户名、未认证访问、无效 Token 和 Token 过期路径通过；Refresh Token/撤销尚未实现。
- [x] 密码只保存 BCrypt cost 12 强哈希，API 不返回哈希。
- [x] 四种仓库角色的允许/拒绝矩阵有自动化测试；成员管理与各写端点接入将在后续功能继续验证。
- [x] 私有仓库在列表和详情中对非成员不可见，详情统一返回 404，不泄露资源信息。

### Repository 元数据与存储

- [x] 创建仓库后 bare Git 仓库路径位于受控根目录，绝对路径不出现在 API 响应。
- [x] 创建者在同一事务中自动成为 OWNER。
- [x] 可选 README 初始提交正确写入 `main`，HEAD 指向 `refs/heads/main`。
- [x] 同一所有者下重复 slug 返回 409，且不会删除原有 bare 仓库。
- [x] 非法 slug 在创建存储目录前被拒绝；已存在目录拒绝复用或删除。
- [x] 仓库列表支持 1～100 条限制和不透明游标分页，无效参数返回统一 400。

### Repository 与 Git

- [x] 创建仓库后裸仓库路径位于受控根目录。
- [x] 外部 Git 客户端 clone 成功。
- [x] 普通分支 push 成功并可再次 fetch。
- [x] 无写权限用户 push 被拒绝，且远端未产生目标分支。
- [x] 受保护分支直接 push 被拒绝；同批 ref 命令原子拒绝。
- [ ] 并发 push 不损坏仓库；当前只实现并发请求上限，尚未完成同仓库并发写压力验证。
- [x] 仓库名使用严格 `{owner}/{repo}.git` 白名单，超大 HTTP 请求返回 413，command/object/pack 均有独立上限；非法 ref 的完整协议测试待分支 API 阶段继续补充。

### 分支与文件浏览

- [x] 分支列表只返回本地分支，并给出 40 位 commit、最后提交信息和默认分支标记。
- [x] tree 可按分支名和完整 commit ID 浏览根目录/子目录，并使用不透明游标稳定分页。
- [x] blob 对小型 UTF-8 文本返回内容；二进制与超过 1 MiB 的文本只返回元数据和未内联原因。
- [x] tag、缩写 SHA、revision 表达式、非法分支名与不存在 ref 返回确定的 400/404 错误。
- [x] 绝对路径、反斜杠、`.`/`..`、空段、尾随 `/`、目录当文件等场景被拒绝。
- [x] 私有仓库非成员不能通过 branches/tree/blob 推断仓库或内容存在性；公开仓库已认证非成员可读。

### MR

- [x] 成员可从两个不同且存在的本地分支创建 MR，并保存 base/head commit 快照。
- [x] 同仓库 MR iid 在并发安全的仓库行锁内递增；同源/目标最多一个 OPEN MR。
- [x] MR 列表支持状态、目标分支与不透明游标筛选；详情不泄露私有仓库。
- [x] 标题、描述和关闭操作必须携带当前 version，陈旧版本返回 409。
- [x] Diff 只使用 MR 保存的 base/head 快照，并受文件数、单文件 patch 与总 patch 体积限制。
- [x] 普通评论可创建/分页查询；行级评论只允许定位到当前快照 Diff 的真实新增/删除行。
- [x] 成功 push 普通源分支后，所有匹配 OPEN MR 的 head/version 更新并记录去重的 head 历史；删除分支保留最后快照。
- [x] Merge 临界区重新验证 `MERGE` 权限、MR OPEN、expected head、源/目标引用和源 head 二次读取。
- [x] 无冲突时生成两个父提交的 merge commit，并通过目标引用 CAS 更新目标分支。
- [x] 有冲突时返回 `MR_MERGE_CONFLICT`，目标引用与 MR 状态均不变化。
- [x] 相同 Idempotency-Key 重放返回同一 merge commit；不同请求复用 key 返回确定冲突。
- [x] Git 引用已成功但数据库终态未完成的 PENDING 操作可在重试时恢复。
- [x] M1.6 阶段不伪造 Check 已通过；随后完成的 M2 已独立验收当前 head 的阻塞型 Check 门禁。

## 4. M2 事件与门禁

- [x] 业务事务与 Outbox 在同一数据库事务。
- [x] Kafka 暂停后恢复，Outbox 最终发布成功。
- [x] Publisher 崩溃造成重复消息时业务不重复。
- [x] 不可恢复消息进入 DLQ 并保留可追踪信息。
- [x] 同一 MR/head 只产生一个当前 Check Suite。
- [x] 新 push 后旧 Suite 不参与门禁。
- [x] `PENDING/RUNNING/FAILED` 阻塞型 Check 阻止合并。
- [x] 非阻塞 AI Check 失败不自动阻塞。
- [x] 合并临界区再次验证权限、head 和 Check。

## 5. M3 CodeCurator

- [x] 逻辑 Skill 能识别至少一类固定逻辑缺陷。
- [x] 安全 Skill 能识别至少一类固定安全缺陷。
- [x] 两个 Skill 确实并行且有独立超时。
- [ ] 外部 LLM 结构化输出符合 Schema，非法输出不会写成评论（LLM Provider 尚未接入）。
- [x] Finding 的文件和行号存在于目标 Diff，写评论前再次由 JGit 校验。
- [x] Judge 可以聚合重复 Finding，并拒绝伪造定位。
- [x] 相同 `event_id` 不产生重复 Task/Finding/评论。
- [x] 过期 head 的任务标记 CANCELLED，历史 Finding 不进入当前查询。
- [ ] 真实 LLM 限流与 Provider 异常分类（LLM Provider 尚未接入）。
- [x] Skill 超时、熔断或隔离拒绝时 Check 为 SKIPPED，不伪装 SUCCESS。
- [ ] Prompt 日志脱敏（尚无真实 Prompt）；当前规则 Finding/评论/API 已验证不泄露 credential-like 原值。

## 6. M4 CodeAssay

> 当前执行证据见 [docs/21-m4-assay-verification-record.md](21-m4-assay-verification-record.md)。M4 完成的是受控声明式 HTTP 测试基线，不是通用代码沙箱。

- [x] JSON 用例按 Draft 2020-12 v1 Schema 校验，顶层和子对象未知字段被拒绝。
- [x] 非法表达式、绝对 URL/host、危险 Header 和非法相对路径在 HTTP 执行前被拒绝。
- [x] `data_pre` 按顺序执行并通过受限 `$.status`/`$.body.*` 提取保存上下文。
- [x] `${context...}`、`${#uuid()}`、`${#randomLong(min,max)}` 受限渲染；脚本/反射/文件/进程/用户类加载不可用。
- [x] 进程内 WireMock 在 `127.0.0.1` 动态端口按 method/path 返回固定 JSON 响应。
- [x] HTTP Mock 的 `expect_calls.min/max` 参与用例结果判断。
- [x] 响应断言成功路径稳定通过，覆盖 7 种受支持运算符的执行基线。
- [x] 失败路径持久化并通过 API 返回结构化 path/operator/expected/actual/message。
- [x] 单 case timeout 限制为 100～30000 ms；HTTP/WireMock 执行不占用数据库长事务。
- [x] 响应正文有界读取 1 MiB；敏感断言路径遮蔽 expected/actual，报告进行转义和长度限制。
- [x] 断言失败写 Execution/CaseResult 与幂等 TEST_REPORT，并使当前 blocking Assay Check 为 FAILED。
- [x] 重复 command 由 ConsumedEvent 与 Execution 唯一约束幂等；RUNNING 可通过租约重新 claim，旧 run token 不可覆盖新终态。
- [x] 旧 head command 只产生 `CANCELLED/MR_STALE`，不写 CaseResult/TEST_REPORT；查询 API 只返回当前 head。
- [ ] 任意外部 application target 的真实部署集成（M4 自动化与实机验收使用进程内 WireMock；服务端配置 target 的代码路径已实现）。
- [ ] Docker 沙箱、DB 断言、Spring Bean Mock、fission、cleanup、流量录制和 AI 用例生成（均属后续阶段）。

## 7. M4.5 前后端真实联调

> 验收证据见 [docs/23-m45-frontend-backend-integration-verification-record.md](23-m45-frontend-backend-integration-verification-record.md)。

- [x] 登录/注册调用真实 Auth API；Access Token 只写 `sessionStorage`，401 自动清理并跳转登录页。
- [x] 工作台显示真实用户、仓库、可见性与角色，支持真实仓库切换和退出。
- [x] 无仓库用户可创建真实仓库；分支、tree、UTF-8 blob 与 Git URL 来自后端。
- [x] MR 列表、创建、详情和受限 Diff 使用真实 API；没有第二个分支时明确提示先通过 Git Smart HTTP 推送。
- [x] 普通评论写入后端并在刷新后保留。
- [x] 当前或最近历史 Check Suite、Curator Findings 和 Assay Report 使用真实后端结果。
- [x] blocking Run 以 `status=SUCCESS` 判定通过，`conclusion` 保留 `NO_FINDINGS/ALL_CASES_PASSED` 等业务原因。
- [x] 无启用 Assay 用例时返回 `SKIPPED/NO_ENABLED_CASES` 并阻止 Merge。
- [x] Curator 与 Assay 成功时通过 UI 完成真实 `MERGE_COMMIT`，MR 状态刷新为 `MERGED`。
- [x] Actions 对已合并 MR 回退展示最近 history Suite，不错误显示 `NOT_STARTED`。
- [x] Settings 对无 REST API 的成员、规则、重跑、处置和归档能力只读展示，不伪造成功。
- [x] 新增页面支持 `en-US/zh-CN`，登录页在未认证状态也可切换语言。
- [x] Prettier、Vue TypeScript、Vite Build 和浏览器渲染通过。
- [x] 后端 82 项测试、Flyway V1～V8 与全模块 Checkstyle 通过；最终 JAR 打包成功并恢复 Readiness。

## 8. M5 增强能力

### Curator

- [ ] PR Compression 不改变变更代码语义和行映射。
- [ ] 记录压缩前后 Token 或字符量。
- [ ] 增量评审只处理上次已评审 commit 之后的变更。
- [ ] RAG 强制按 repository_id 过滤。
- [ ] 误报反馈可命中相似场景且可撤销。
- [ ] 模型路由有可解释规则和成本/质量对比。

### Assay

- [ ] DB 断言只使用预注册 query_id 和只读账号。
- [ ] Spring Bean Mock 只能访问白名单目标。
- [ ] 用例裂变在执行前限制总实例数。
- [ ] 每个裂变实例在报告中显示参数。
- [ ] Docker 容器以非 root、资源受限方式运行。
- [ ] 容器默认不能访问宿主机、Docker Socket 和未授权网络。
- [ ] 超时或崩溃后容器与临时资源被清理。

## 9. M6 流量录制与 AI 用例

- [ ] 录制默认关闭，只能按授权配置开启。
- [ ] Authorization、Cookie、Set-Cookie 不进入 Kafka。
- [ ] 嵌套 JSON、表单和 Header 脱敏测试通过。
- [ ] 脱敏失败时丢弃原记录而不是降级明文发送。
- [ ] 大正文受到体积限制。
- [ ] 调用链关联结果可追溯但不含真实凭据。
- [ ] AI 生成内容先进入 DRAFT。
- [ ] 候选用例通过 Schema 与安全校验。
- [ ] 未经人工批准的候选用例不参与阻塞回归。
- [ ] 删除操作覆盖元数据、文件和向量投影。

## 10. M7 容量与可用性

- [ ] 建立基线压测数据与目标 SLO。
- [ ] 每个新增中间件有引入前后对比。
- [ ] Diff 缓存失效不返回错误版本。
- [ ] Redis 故障时主数据仍可恢复或明确降级。
- [ ] ES 延迟时精确结果仍以 MySQL/Git 为准。
- [ ] Canal 重建索引流程经过验证。
- [ ] 分片路由覆盖全部逻辑分片且分布可解释。
- [ ] 非分片键查询策略有正确性和性能测试。
- [ ] 时钟回拨下 Snowflake 不产生重复 ID。
- [ ] Kafka、MySQL、Redis、LLM 故障演练有记录。

## 11. M8 交付

- [ ] README 从空环境开始可执行。
- [ ] 本机 MySQL 接入说明、Docker Compose 中间件、迁移和种子数据版本一致。
- [ ] OpenAPI、事件 Schema、Assay Schema 与实现一致。
- [ ] 架构图反映实际部署，不包含未实现组件。
- [ ] 2 分钟 Demo：push → MR → AI → Assay → Check → Merge。
- [ ] Demo 同时准备一个失败场景。
- [ ] 核心指标由脚本或日志可复现计算。
- [ ] 简历声明逐条关联实现和证据。
- [ ] 未实现能力明确列为 Roadmap。
- [x] 依赖版本、Secret 和许可证检查完成；MIT License 已加入，暂存区未发现真实密钥。完整漏洞/SBOM 扫描仍待后续增强。
- [x] `.env`、数据库密码、JWT/LLM 密钥、运行数据、裸 Git 仓库和 IDE 私有配置均未进入 Git 索引；`.env.example` 仅含占位值。
- [x] Git 历史从经过审计的首次本地提交开始，不存在先提交再删除的真实 Secret。
- [x] 用户已确认 GitHub 目标 `Tmiemie/CodeTrove`、公开可见性与 MIT License；首次 push、218 个远端文件和首轮 GitHub Actions 成功均已验证。

## 12. 每次交付的最小验证记录模板

```markdown
### 验收项

- 功能/风险：
- 环境与版本：
- 前置条件：
- 操作步骤：
- 输入：
- 预期：
- 实际：
- 自动化测试或命令：
- 证据位置：
- 结论：通过 / 未通过
- 遗留问题：
```

## 13. 阶段放行规则

只有满足以下条件才能宣布阶段完成：

1. 本阶段所有阻塞项通过。
2. 自动化测试在干净环境通过。
3. 用户真正消费的页面、API、Git 操作或报告经过实际检查。
4. 安全与故障场景至少覆盖本阶段新增边界。
5. 文档、实现和演示口径一致。
6. 遗留问题明确影响、责任和后续里程碑，不隐藏失败。
