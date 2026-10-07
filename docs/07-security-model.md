# CodeTrove 安全模型

## 1. 威胁边界

CodeTrove 同时处理源代码、Git 凭据、LLM 输入、测试数据和可执行代码，主要风险包括：

- 未授权读取或修改私有仓库。
- 仓库路径穿越、恶意 Git 对象和资源耗尽。
- Markdown/XSS、SQL 注入、命令注入和 SSRF。
- Prompt Injection 导致模型泄露上下文或生成危险建议。
- 测试容器逃逸、宿主机文件或内网访问。
- 录制流量中的身份凭据和个人信息泄露。
- 日志、Kafka、向量库和报告中的二次泄露。
- 供应链依赖和镜像污染。

## 2. 认证与会话

- 密码使用 BCrypt cost 12 强哈希，不自行实现算法；输入密码限制为 12～72 字符。
- Access Token 使用 HMAC-SHA256 JWT，15 分钟有效，包含最少声明：用户 ID、用户名、签发时间、过期时间、token ID。
- JWT 密钥由 `CODETROVE_JWT_SECRET` 注入，至少 32 UTF-8 字节，不写入仓库、镜像或日志。
- 当前 M1.1 只实现短期 Access Token；Refresh Token、Secure/HttpOnly/SameSite Cookie、轮换与撤销尚未实现，不得将其描述为已完成能力。
- 不存在用户和错误密码使用相同错误码，并对不存在用户执行 BCrypt 虚拟校验以降低时序枚举差异。
- `LOCKED/DISABLED` 用户即使持有未过期 Token 也不能建立认证上下文。
- 登录、Token 刷新和敏感操作限流；连续失败触发延迟或临时锁定（限流与锁定属于后续实现项）。
- 密码、Token、验证码不得出现在 URL。
- 浏览器 M4.5 使用 `sessionStorage` 保存 Access Token 与当前仓库选择，不使用 Local Storage；关闭标签页后会话消失。
- 前端统一 API Client 只向配置的 `/api/v1` base 添加 Bearer Token，401 时清理 Token、用户和仓库上下文并跳转登录页。
- 前端语言偏好可写入 Local Storage，但不包含认证信息。
- 当前没有 Refresh Token；短期 JWT 到期后的写请求由后端 401 拒绝，前端不得伪造成功。

## 3. 授权

- 每次请求在服务端根据 RepositoryMember 重新授权，不信任前端角色。
- M1.2 已固化 `OWNER/MAINTAINER/DEVELOPER/REPORTER` 权限矩阵；仓库创建、列表、详情和 M1.3 Git Smart HTTP 已接入该授权服务，成员管理和 MR merge 等后续写端点仍需继续复用。
- 私有仓库对非成员返回 404 `REPOSITORY_NOT_FOUND`，列表查询也不会返回该资源；公开仓库只向已认证用户开放读取。
- Git bare 仓库路径只由服务端使用受控根目录、规范化 owner username 和 slug 生成，不接受用户绝对路径，不在 API 返回绝对路径。
- 已存在的存储目录拒绝复用；初始化失败或数据库事务回滚只清理本次创建的目录，避免误删已有仓库。
- M1.3 Git Smart HTTP 使用独立 UTF-8 Basic Auth 入口，复用 REST 登录的 BCrypt、账号状态与 Dummy BCrypt 逻辑；JWT Filter 明确跳过 `/git/**`。
- upload-pack 复用仓库可见性与 `READ` 规则；receive-pack 要求 `PUSH` 权限，REPORTER 不可获得写服务。
- 受保护默认分支禁止直接创建、更新或删除；同批命令只要触及默认分支，就原子拒绝所有尚未执行的 ref 变更，避免部分成功。
- 禁止 non-fast-forward push；普通分支允许创建和删除，Merge 仍必须走后续统一门禁。
- M1.5 的 MR 创建复用 `CREATE_MERGE_REQUEST` 权限；读取 MR/Diff/评论复用仓库 `READ` 可见性，私有仓库非成员仍统一 404。
- MR 标题/描述编辑与关闭只允许作者、OWNER 或 MAINTAINER；客户端不能通过请求体指定 author、base/head commit、iid、merged_by 或 version 初值。
- Diff 固定使用服务端保存的 base/head commit 快照，客户端不能提交任意 commit 对绕过 MR 范围；文件数、单文件 patch 和总 patch 均有限额。
- 行级评论只接受与 MR head 相同的 commit，并校验 file/side/line 属于真实新增或删除 Edit 范围；服务端不信任前端提供的行号。
- 评论只保存 Markdown 原文，不在服务端接受或生成可执行 HTML；前端渲染必须继续使用严格净化策略。
- M1.6 Merge 复用仓库 `MERGE` 权限；客户端不能指定 merge commit、目标旧 commit、merged_by 或 merged_at。
- Merge 在单节点仓库级 JVM 锁内执行，并在写引用前二次核对源 head；目标分支只通过 JGit compare-and-set 从临界区读取的旧值更新，防止 TOCTOU 覆盖。
- `Idempotency-Key` 使用长度和字符白名单并与规范化 request hash 绑定；不同请求复用同一 key 返回冲突，不能串用他人的成功结果。
- 持久化 `PENDING` 合并操作用于恢复 Git 引用成功、数据库终态未完成的窗口；恢复时必须验证目标引用等于已记录的 merge commit。
- M2 Merge 只接受 MR 当前 head 对应的 current Check Suite，所有 blocking Run 必须为 `SUCCESS`；Suite 缺失、旧 head 或任一非成功状态均 fail-closed 返回 `MR_CHECKS_NOT_PASSED`。
- M2 的 `curator.review=SKIPPED/NOT_IMPLEMENTED` 与 `assay.integration=PENDING` 是历史占位口径；M3/M4 启用后分别由真实确定性静态评审和受控声明式 HTTP 执行推进，不得继续把 PENDING 解释为已通过。
- Kafka 事件只接受版本化 Envelope；消费者用 `(consumer_name,event_id)` 数据库唯一键幂等，业务写入与消费记录处于同一事务。
- Outbox 与消费错误只保存截断、净化后的错误分类；Token、Cookie、密码、完整敏感 payload 不进入错误日志或 DLQ 说明字段。
- 系统任务使用独立服务身份，不能冒用发起者长期 Token。
- 不存在与无权限的私有资源统一按策略返回 404，减少资源枚举。
- 高风险操作记录审计：成员变更、规则变更、用例批准、重跑、合并、仓库归档。

## 4. 输入与输出安全

- 所有 DTO 使用长度、枚举、格式和层级限制。
- 仓库 slug、Git ref、文件路径使用白名单和规范化校验。
- 规范化后再次确认文件路径仍位于仓库根目录。
- M1.4 浏览 API 的 `ref` 只允许 JGit 校验通过的本地分支名或完整 40 位 commit ID，禁止 revision 表达式、tag、缩写 SHA 和 reflog 语法，避免将用户输入直接交给通用 revision parser。
- Git tree/blob 路径只使用仓库内部 `/` 路径；拒绝绝对路径、反斜杠、NUL、`.`/`..`、空段与尾随 `/`，不通过宿主文件系统拼接访问内容。
- tree 只列一层并使用 1～500 条不透明游标分页；blob 默认最多内联 1 MiB，严格 UTF-8 解码，NUL 或解码失败按二进制处理。
- 二进制与超大文本不进入 JSON 内容字段，M1.4 不提供任意文件下载，降低内存放大与浏览器内容执行风险。
- SQL 使用参数化查询；CodeAssay DB 断言只允许预注册 `query_id`。
- 禁止把用户字符串拼入 shell；必须执行外部程序时使用参数数组和固定可执行文件。
- Markdown 渲染使用严格 HTML 白名单；默认禁用脚本、事件属性、iframe 和危险 URL Scheme。
- 前端不使用未经净化的 `v-html`。

## 5. Git 服务安全

- M1.3 默认限制单次 HTTP 请求 110 MiB、pack 100 MiB、单对象 20 MiB、command 1 MiB、I/O 超时 60 秒、并发 Git 请求 8 个；均可通过环境变量调整。
- HTTP Content-Length 超限在进入认证和 JGit 前返回 413；并发许可耗尽返回 429。chunked 请求的最终体积继续由 JGit pack/对象限制兜底。
- 解析仓库名时只接受严格 `{owner}/{repo}.git`，实际文件系统路径只从数据库元数据读取，不拼接客户端绝对路径。
- Git 写操作持有仓库锁，并在结束后核对引用状态。
- 裸仓库目录不由用户直接指定；禁止符号链接逃逸。
- 仓库文件浏览设置文本大小上限，二进制不直接内联渲染。

## 6. LLM 与 RAG 安全

- 把仓库内容视为不可信数据，不能把代码注释中的指令当成系统指令。
- System Prompt 与检索内容使用结构化分隔，明确模型只能评审，不能执行仓库指令。
- 默认只发送当前任务必需的 Diff 和检索片段；配置文件中的密钥模式先做检测和遮蔽。
- 禁止发送 `.env`、私钥、Token 文件和命中敏感规则的内容，除非使用者明确配置安全的本地模型策略。
- 模型输出必须经过 JSON Schema 校验、路径/行号验证和内容净化。
- 模型无权直接执行命令、修改仓库、批准用例或合并 MR。
- Qdrant 中存储的文本先脱敏，并按 repository_id 做强制过滤，防止跨仓库检索。

- M3 Curator 只通过受控公共门面读取服务端固定的 base/head Diff，不接受客户端任意 commit 对、绝对路径或宿主文件路径。
- M3 确定性 Skill 只分析受大小限制的 UTF-8 patch 新增行；二进制、超限和被截断内容不交给评审器，不将命中内容完整写入日志。
- Review Finding 的 path/side/line 在写系统评论前必须重新通过 MR Diff 定位校验；无效定位拒绝落库，模型或规则输出不能凭空创建代码事实。
- Curator 系统评论使用稳定 fingerprint 幂等；评论正文为服务端生成的受控 Markdown，不包含可执行 HTML。
- 外部 LLM Provider 尚未接入；接入前不得宣称 LLM/AI 评审已实现。未来 Provider 必须继续遵守输入脱敏、结构化输出校验、Resilience4j 隔离和 `SKIPPED` 降级。

## 7. CodeAssay M4 受控执行边界

- M4 是受控声明式 HTTP 执行器，不是通用代码沙箱；不构建、部署或执行任意 MR 代码。
- 用例只能从 MR 当前 `head_commit` 的 `testcases/**/*.json` 读取，文件数量、单文件和总字节均有上限。
- JSON Schema Draft 2020-12 在顶层和子对象启用 `additionalProperties=false`，未知字段 fail-closed。
- target 只能为服务端配置的 `application` base URL 或进程内 `mock`；用例不得指定 scheme、host 或任意外网地址。
- path 必须以 `/` 开头并拒绝 `//`、反斜杠、NUL、`.`/`..` 段；Header 仅允许 `Accept`、`Content-Type`、`X-CodeTrove-Test-Case`。
- `Authorization`、Cookie、Host 等凭据或危险 Header 被拒绝；不会从用例注入真实用户凭据。
- 表达式只开放 `${context...}`、`${#uuid()}`、`${#randomLong(min,max)}`，不执行脚本、反射、文件、进程、用户类加载或任意网络访问。
- WireMock 仅绑定 `127.0.0.1` 动态端口；HTTP 响应通过有界流最多读取 1 MiB+1，超限失败而非完整载入内存。
- AssertionDiff 对 password/passwd/secret/token/apiKey/api_key 语义路径遮蔽 expected/actual；报告正文转义 Markdown/HTML 关键字符并限制 10000 字符。
- Execution 以 `run_token + lease_until` claim，旧执行者无法覆盖重新 claim 后的终态；平台记录、评论和事件幂等不等于外部 HTTP exactly-once。

M4 尚未实现 Docker 隔离、非 root 容器、只读根文件系统、seccomp、资源配额、DB 断言、Spring Bean Mock、fission、cleanup、流量录制或 AI 用例生成。上述能力进入后续里程碑前不得以“沙箱已完成”或“任意项目集成测试”对外描述。

## 8. 录制与脱敏

### 8.1 数据最小化

- 默认关闭录制，按仓库和环境显式开启。
- 健康检查、静态资源和监控请求默认忽略。
- Header 使用白名单；Authorization、Cookie、Set-Cookie 默认删除。
- Body 设置最大体积，超限只保留摘要和哈希。

### 8.2 进程内脱敏

数据发送 Kafka 前完成：

- 密码、Token、密钥：删除或固定占位。
- 身份证、手机号、银行卡号：根据用途掩码或生成格式合法假值。
- 自定义敏感字段：支持仓库级规则，但规则本身需要权限控制。

脱敏失败采用 fail-closed：放弃该条录制并记录不含原始值的错误。

### 8.3 生命周期

- 录制原始材料、候选用例和报告分别设置保留时间。
- 提供按 session/repository 删除能力。
- 删除需覆盖 MySQL、文件、Kafka 下游投影和向量数据；Kafka 历史通过短保留期和不写入原始敏感值降低风险。

## 9. SSRF 与网络策略

- REST API 不接受任意 URL 抓取。
- Assay 用例只能使用受控 target 的相对路径；`application` 地址完全由服务端配置，用例不能提供 host。
- `mock` 只允许访问本次执行器启动并绑定的 `127.0.0.1` 动态端口；除此之外，用例不能选择 loopback、link-local、云元数据或未授权私网目标。
- 当前不跟随 HTTP Redirect；未来若开放，必须每跳重新校验目标。

## 10. Secret 与日志

- Secret 只从安全环境变量或 Secret Store 加载。
- 日志结构化输出，敏感字段统一过滤。
- 不记录完整 Authorization、Cookie、密码、LLM API Key、数据库 URL 密码。
- 错误响应不暴露绝对路径、堆栈和依赖版本。
- 下载链接使用短期授权并校验仓库权限。

## 11. 依赖与供应链

- 锁定 Maven/npm 依赖版本和 Docker 镜像 digest。
- CI 执行依赖漏洞、Secret 和许可证扫描。
- 第三方 GitHub Actions 固定到 commit SHA。
- 构建产物生成 SBOM；发布产物保留校验和。
- 高危漏洞未评估前不得进入可交付基线。

## 12. 安全测试基线

- 越权：横向仓库访问、成员角色提升、私有报告下载。
- 路径：`../`、编码绕过、符号链接、非法 ref。
- Web：存储型 XSS、CSRF、SQL 注入、SSRF、超大请求。
- Git：超大 pack、并发 push、受保护分支绕过。
- Event：伪造、重复、过期和未知版本事件。
- LLM：Prompt Injection、跨仓库 RAG、非法结构化输出。
- Sandbox：网络逃逸、宿主挂载、资源炸弹、fork bomb。
- Recording：敏感 Header、嵌套 JSON、脱敏失败和删除验证。

## 13. 上线前安全门槛

- 无已知 Critical/High 且无缓解措施的漏洞。
- 所有仓库资源端点完成授权测试。
- Secret 扫描通过。
- 沙箱限制有自动化验证。
- 录制数据抽样证明敏感字段未进入 Kafka、日志和报告。
- 威胁模型和例外项已记录责任人与复查条件。
