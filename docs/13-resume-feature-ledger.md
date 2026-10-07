# CodeTrove 简历功能台账

> 用途：为秋招简历、项目介绍和面试复盘持续沉淀真实、可验证的项目能力。
>
> 更新规则：每完成一个可独立验收的功能，必须新增一条记录，至少包含“新增能力、技术栈、实现细节、难点与取舍、验证证据、简历表述、未完成边界”。只有已有代码和验证证据的内容，才能写为“已实现”。

## 1. 项目概述

CodeTrove 是一个面向 Java 后端秋招展示的智能代码协作与质量保障平台，目标链路为：

```text
Git push → Merge Request → CodeCurator 确定性静态评审
         → CodeAssay 声明式测试 → Check 汇总 → Merge 门禁
```

当前完成范围：

- M0 工程与契约基线。
- M0 延伸交付：GitHub 风格前端工作台、猫咪策展员视觉和中英文切换。
- M1.1 用户认证基线。
- M1.2 仓库与成员权限基线。
- M1.3 Git Smart HTTP、协议权限与保护分支基线。
- M1.4 分支与文件浏览 API。
- M1.5 Merge Request、受限 Diff 与普通/行级评论基础。
- M1.6 push 后 MR head 历史同步、冲突检测、幂等 merge commit 与失败窗口恢复。
- M2 Transactional Outbox、Kafka 至少一次投递/幂等消费/DLQ、Check Suite/Run 与当前 head Merge 门禁。
- M3 CodeCurator 确定性静态评审：逻辑/安全 Skill、并行编排、Judge、ReviewTask/Finding、幂等系统行级评论、当前 head 查询与 Resilience4j 降级。
- M4 CodeAssay 声明式 HTTP 测试基线：严格 Schema、受限表达式与 `data_pre`、WireMock、响应断言、Execution/CaseResult、Kafka command/result、幂等 TEST_REPORT 与 blocking Check。

尚未完成：成员管理 API、Review/Approval、外部 LLM/RAG/Memory、任意 MR 自动构建部署、Docker 测试沙箱、DB/Bean Mock 断言、流量录制与 AI 生成用例。因此简历中只能写“CodeCurator 确定性静态评审编排基线”和“CodeAssay 受控声明式 HTTP 测试基线”，不能写“已接入 LLM/已完成 AI 代码评审”“任意项目集成测试平台”或“Docker 沙箱已完成”。

---

# 2. M0 工程与契约基线

## 功能 M0-F01：模块化单体工程骨架

### 新增能力

- 建立 Java 17、Spring Boot 3.5.7 的 Maven 多模块后端工程。
- 按领域拆分 `common`、`auth`、`repository`、`merge-request`、`check`、`eventing`、`curator`、`assay`、`search`、`bootstrap` 共 10 个模块。
- 业务模块保持独立，`bootstrap` 作为统一装配与启动入口。

### 技术栈

- Java 17
- Spring Boot 3.5.7
- Maven Wrapper 3.9.11
- Maven Enforcer Plugin
- Maven Checkstyle Plugin

### 实现细节

- 父 POM 集中管理 Java 版本、编码、插件和模块构建顺序。
- Maven Enforcer 将运行环境限定为 Java `[17,18)` 和 Maven 3.9+，避免开发机和 CI 使用不同版本。
- Checkstyle 在 `verify` 阶段执行，使静态规范成为构建门禁，而不是依赖开发者手动检查。
- 模块化单体优先于微服务：先保持领域边界，避免在业务主链路尚未完成时引入部署与分布式复杂度。

### 难点与取舍

- 没有为了简历技术点直接拆微服务，而是选择更适合 MVP 的模块化单体。
- 通过 Maven Reactor 验证模块依赖方向，为后续按需要拆分 Worker 或服务保留边界。

### 验证证据

- `backend/pom.xml`
- `backend/config/checkstyle/checkstyle.xml`
- `backend/mvnw.cmd`
- `docs/10-m0-verification-record.md`
- M0 验收时 10 个模块构建成功，Java/Maven Enforcer 规则通过。

### 可用于简历的表述

> 基于 Java 17、Spring Boot 3.5 和 Maven 搭建 10 模块的模块化单体后端，使用 Enforcer 固化构建环境、Checkstyle 接入 `verify` 阶段，为认证、仓库、MR、Check、AI 评审和测试引擎建立清晰模块边界。

### 当前边界

- 该功能验收时除认证外多数业务模块仍处于骨架阶段；当前 repository 与 merge-request 已进入真实实现，Check、事件、Curator、Assay 和 Search 仍待后续完成。
- 不能描述为“已完成微服务架构”或“已完成全链路平台”。

---

## 功能 M0-F02：MySQL 最小权限与 Flyway 数据库迁移

### 新增能力

- 接入 Windows 本机 MySQL 8.0.43。
- 创建项目独立数据库 `codetrove` 和低权限应用账号。
- 使用 Flyway 管理数据库结构版本，支持空库迁移和重复启动幂等。
- 使用 H2 MySQL 模式执行测试迁移。

### 技术栈

- MySQL Community Server 8.0.43
- Spring JDBC / HikariCP
- Flyway Core / Flyway MySQL
- H2 Database MySQL compatibility mode

### 实现细节

- 应用账号权限限制在 `codetrove.*`，不能读取 `mysql.user`，避免应用使用 root 账号。
- 数据库统一使用 `utf8mb4`，排序规则为 `utf8mb4_0900_ai_ci`。
- V1 迁移创建 `codetrove_platform_metadata` 和 `flyway_schema_history`，写入 `schema_baseline=M0`。
- 生产配置从 Windows 用户环境变量读取 URL、用户名和密码，真实 Secret 不写入仓库。
- 测试环境使用 H2 MySQL 模式复用同一套迁移文件，减少测试与真实数据库结构漂移。

### 难点与取舍

- 本机已经安装 MySQL，因此默认 Compose 不重复部署 MySQL；为其他贡献者保留可选 Docker MySQL 配置。
- 将结构变更交给 Flyway，而不是依赖手工执行 SQL，保证环境可复现和迁移顺序可追踪。

### 验证证据

- `backend/codetrove-bootstrap/src/main/resources/application.yml`
- `backend/codetrove-bootstrap/src/main/resources/db/migration/V1__create_platform_metadata.sql`
- `deploy/compose.with-mysql.yml`
- `.env.example`
- 真实 MySQL 首次迁移成功，重启后无重复迁移；H2 测试迁移通过。

### 可用于简历的表述

> 使用 Flyway 管理 MySQL Schema 版本，设计空库初始化与幂等迁移流程；为应用创建仅限项目库的低权限账号，并通过 H2 MySQL 模式在测试阶段复用迁移脚本，降低环境漂移风险。

### 当前边界

- 该功能验收时只完成平台元数据和认证用户表；当前已通过 V3/V4 补齐仓库、成员、MR 和评论表，Check 等后续业务表仍未实现。

---

## 功能 M0-F03：Redis 本地中间件与依赖健康治理

### 新增能力

- 使用 Docker Compose 提供 Redis 7.4.2。
- 启用 Redis 密码鉴权、AOF 持久化、健康检查和命名卷。
- 将 Redis 和 MySQL 纳入 Spring Boot Readiness。
- 验证依赖故障时 Readiness 降级、恢复后自动回到正常状态。

### 技术栈

- Docker Desktop / Docker Compose
- Redis 7.4.2 Alpine
- Spring Data Redis
- Spring Boot Actuator

### 实现细节

- Redis 仅绑定 `127.0.0.1:6379`，避免开发实例暴露到外部网络。
- 密码由 Windows 用户环境变量注入；无密码 `PING` 返回 `NOAUTH`，正确密码返回 `PONG`。
- 开启 AOF，并使用 `codetrove-redis-data` 命名卷保留数据。
- Liveness 只判断应用进程是否存活；Readiness 包含数据库、Redis 和应用状态，用于表达是否可接收业务流量。

### 难点与取舍

- Redis 是当前阶段真实需要的中间件，因此进入默认 Compose；MySQL 已由本机提供，避免重复部署。
- 将“进程活着”和“依赖可用”拆成 Liveness/Readiness，避免依赖暂时故障时错误重启应用。

### 验证证据

- `deploy/compose.yml`
- `backend/codetrove-bootstrap/src/main/resources/application.yml`
- `scripts/verify-m0.ps1`
- 停止 Redis 后 Readiness 返回 HTTP 503 / `DOWN`；恢复且容器 healthy 后返回 HTTP 200 / `UP`。

### 可用于简历的表述

> 使用 Docker Compose 管理带鉴权与 AOF 的 Redis 开发实例，并基于 Spring Boot Actuator 区分 Liveness/Readiness；完成 Redis 故障注入与恢复验证，使依赖不可用时服务就绪状态可准确降级。

### 当前边界

- Redis 尚未用于业务缓存、分布式锁或限流，不能描述为已经完成这些能力。

---

## 功能 M0-F04：统一 API 契约与 Trace ID

### 新增能力

- 建立统一成功、分页和错误响应结构。
- 为每个请求生成或透传 `X-Trace-Id`。
- 实现统一异常映射和未知 API 路径 404。
- 拒绝不安全的客户端 Trace ID。

### 技术栈

- Spring MVC
- Servlet Filter
- `@RestControllerAdvice`
- SLF4J MDC
- MockMvc

### 实现细节

- 成功响应使用 `data + meta.traceId`，错误响应使用 `error.code/message/details/traceId`。
- `TraceIdFilter` 在最高优先级读取 `X-Trace-Id`：合法值透传，缺失时生成 32 位十六进制 ID。
- 包含空格、CR/LF 等不安全字符的 Trace ID 会被替换，防止日志污染或响应头注入。
- Trace ID 同时写入响应头、响应体和 MDC，便于后续串联 HTTP、事件和异步任务日志。
- 专门处理 `NoResourceFoundException`，避免未知 API 被错误映射成 500。
- 生产错误响应不返回堆栈、SQL、绝对路径和 Secret。

### 难点与取舍

- Trace ID 不只写日志，而是形成入口校验、MDC、响应头和响应体的一致契约。
- 对未知路径单独处理，修复 Spring 静态资源异常被通用异常处理器误判为 500 的问题。

### 验证证据

- `backend/codetrove-common/src/main/java/com/codetrove/common/api/ApiResponse.java`
- `backend/codetrove-bootstrap/src/main/java/com/codetrove/bootstrap/web/TraceIdFilter.java`
- `backend/codetrove-bootstrap/src/main/java/com/codetrove/bootstrap/web/GlobalExceptionHandler.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/web/ApiContractTests.java`
- M0 时 4 项 API 契约测试全部通过。

### 可用于简历的表述

> 设计统一 REST 响应与错误码契约，通过 Servlet Filter + MDC 实现 Trace ID 生成、透传和日志关联，并对 CRLF/空格等非法 Trace 值进行替换，统一处理参数错误、业务异常和未知路径 404。

### 当前边界

- Trace ID 已覆盖 HTTP 请求，尚未进入 Kafka 事件和异步 Worker；跨事件链路追踪属于 M2。

---

## 功能 M0-F05：本地启动、停止与自动验收脚本

### 新增能力

- 提供 Windows PowerShell 本地启动、停止和 M0 验收脚本。
- 对 8080/28741 端口进行安全归属检查，避免误杀或覆盖其他服务。
- 将后端、前端、Docker Compose、Redis 和 API 契约串成可重复执行的本机验收流程。

### 技术栈

- PowerShell 5.1
- Maven Wrapper
- npm / Vite
- Docker Compose CLI
- Spring Boot Actuator

### 实现细节

- `start-local.ps1` 从 Windows 用户环境变量读取配置，启动 Redis、后端和前端。
- 如果端口已被占用，先验证对应服务是否为健康的 CodeTrove，不能静默覆盖无关进程。
- `stop-local.ps1` 根据进程命令行确认归属后才停止 CodeTrove 进程，并保留 Redis 数据卷。
- `verify-m0.ps1` 串行检查后端测试与静态规则、前端格式/类型/构建、Compose 语法、Redis 健康、后端健康和 Trace ID 契约。
- 处理 Windows 运行中 JAR 文件锁：运行态验收使用不会清理被占用 JAR 的策略；需要重新打包时先安全停止 CodeTrove 后端。

### 难点与取舍

- Windows 文件锁和端口占用可能导致构建失败或误杀进程，因此脚本先判断进程归属，不采用无差别 `taskkill`。
- 停止脚本默认保留 Redis 命名卷，避免普通停止动作造成数据丢失。

### 验证证据

- `scripts/start-local.ps1`
- `scripts/stop-local.ps1`
- `scripts/verify-m0.ps1`
- 六阶段验收输出 `M0 local verification passed`。

### 可用于简历的表述

> 编写 PowerShell 本地运行与六阶段验收脚本，自动检查 Maven 测试、静态规则、前端构建、Compose、Redis 健康、Actuator 和 API 契约；通过端口进程归属校验避免误杀或覆盖开发机上的其他服务。

### 当前边界

- 脚本主要面向 Windows 本机开发环境，Linux/macOS 运行脚本尚未提供。

---

## 功能 M0-F06：CI 与供应链基础约束

### 新增能力

- 配置 GitHub Actions 后端与前端检查流程。
- 后端运行 Java 17 Maven `verify`。
- 前端运行 `npm ci`、Prettier、Vue TypeScript 和 Vite 构建。
- 第三方 GitHub Actions 固定到不可变 commit SHA。

### 技术栈

- GitHub Actions
- Java 17 / Maven
- Node.js 22 / npm
- Prettier / vue-tsc / Vite

### 实现细节

- CI 命令与本机验收命令保持一致，减少“本地通过、CI 失败”的环境差异。
- `checkout`、`setup-java`、`setup-node` 不使用浮动标签，而是固定 SHA，降低供应链漂移风险。
- `.gitignore` 排除 Secret、运行数据、构建目录、IDE 文件和裸 Git 仓库。
- `.env.example` 只保存变量名和占位值，不包含真实密码或密钥。

### 验证证据

- `.github/workflows/ci.yml`
- `.gitignore`
- `.env.example`
- 本机已验证 CI 对应命令；远端 GitHub Actions 尚无运行记录。

### 可用于简历的表述

> 配置前后端 GitHub Actions 流程，统一执行 Maven Verify、Checkstyle、Prettier、Vue 类型检查和 Vite 构建，并将第三方 Actions 固定到不可变提交 SHA，降低构建漂移与供应链风险。

### 当前边界

- 项目尚未首次推送 GitHub，因此只能描述为“完成 CI 配置并在本机验证对应命令”，不能描述为“远端 CI 已稳定运行”。

---

# 3. M0 延伸交付：前端工作台与国际化

## 功能 M0-F07：GitHub 风格多页面前端工作台

### 新增能力

- 实现仓库浏览、README 展示、Pull Request 列表与详情、Diff、评论、CodeCurator 评审卡片、CodeAssay 报告、Merge 交互和 Settings 页面。
- 形成浅蓝工作区、深蓝侧栏、白色悬浮卡片与紫粉主操作的统一主题。
- 融合猫咪策展员品牌元素，包括猫耳收藏盒、猫咪头像和状态猫爪。

### 技术栈

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Lucide Vue Icons

### 实现细节

- 使用路由组织仓库、PR、Actions 和 Settings 页面。
- 使用 Pinia 保存搜索、Toast、PR Tab、评论、Merge 状态和设置保存时间等跨页面状态。
- 将演示实体集中到 mock 数据文件，避免页面组件中散落重复业务数据。
- 视觉上以工程工具的可读性为主，猫咪元素作为品牌识别而非大面积装饰。

### 验证证据

- `frontend/src/App.vue`
- `frontend/src/router/index.ts`
- `frontend/src/stores/workspace.ts`
- `frontend/src/views/`
- `docs/09-frontend-design.md`
- 前端格式、类型检查、生产构建和浏览器渲染通过，控制台无运行时错误。

### 可用于简历的表述

> 使用 Vue 3、TypeScript、Pinia 和 Vue Router 实现代码仓库、PR 详情、Diff 评审、测试报告和仓库设置等多页面工作台，并建立统一主题与可复用交互状态管理。

### 当前边界

- 当前页面使用演示数据，尚未连接 M1 后端业务 API。
- 不能描述为前后端完整联调或真实 Git 仓库数据展示。

---

## 功能 M0-F08：中英文界面与语言持久化

### 新增能力

- 支持英文 `en-US` 和简体中文 `zh-CN`，默认英文。
- 顶栏提供语言切换，使用 `localStorage` 保存用户选择。
- 全局布局、仓库、PR、Actions、Settings、Toast、空状态和无障碍标签均支持双语。
- 提供互相链接的英文 `README.md` 与中文 `README.zh-CN.md`。

### 技术栈

- vue-i18n 11
- Vue 3 Composition API
- Browser Local Storage
- Intl.DateTimeFormat

### 实现细节

- 翻译资源按 `common`、`navigation`、`repository`、`pullRequests`、`actions`、`settings` 等领域集中组织。
- 持久化键为 `codetrove.locale`；无保存值时固定使用英文，不根据浏览器语言产生不确定结果。
- 切换语言同步更新 vue-i18n locale、`document.documentElement.lang` 和 Local Storage。
- 技术标识不翻译：代码、分支、commit hash、错误码、API 路径、测试键和用户输入保持原文。
- 切换语言不改变路由、筛选条件、评论草稿、Merge 状态等本地交互状态。

### 验证证据

- `frontend/src/i18n/index.ts`
- `docs/11-i18n.md`
- `README.md`
- `README.zh-CN.md`
- Prettier、vue-tsc、Vite Build 均通过；浏览器验证默认英文、中文切换、刷新持久化、路由与状态保持通过。

### 可用于简历的表述

> 基于 vue-i18n 为多页面工程实现 `en-US/zh-CN` 国际化，设计领域化消息资源和语言持久化机制，保证切换语言时路由与业务交互状态不丢失，并完成无障碍标签与双语 README 配套。

### 当前边界

- 目前只支持两种语言，后端错误码的完整前端双语映射将在前后端联调阶段继续完善。

---

# 4. M1.1 用户认证基线

## 功能 M1.1-F01：用户注册与安全密码存储

### 新增能力

- 实现 `POST /api/v1/auth/register`。
- 用户名规范化、格式校验和唯一性控制。
- 使用 BCrypt cost 12 保存密码哈希。
- 注册响应不返回密码哈希。

### 技术栈

- Spring MVC
- Jakarta Bean Validation
- Spring Security Crypto
- BCrypt
- Spring JDBC
- MySQL / Flyway
- Snowflake ID

### 实现细节

- 用户名去除首尾空白并转为小写，长度 3～64，仅允许字母、数字、`_`、`-`，且必须以字母或数字开始和结束。
- 密码长度限制为 12～72，兼容 BCrypt 的有效输入边界。
- V2 迁移创建 `codetrove_user`，包含全局唯一用户名、密码哈希、显示名、用户状态和审计时间。
- 用户 ID 由线程安全 Snowflake 生成器产生，JSON 中以字符串返回，避免 JavaScript 整数精度丢失。
- 注册前查询和数据库唯一约束共同防止重复用户名；并发冲突统一映射为 409 `USERNAME_CONFLICT`。

### 难点与取舍

- 领域模型要求 64 位 Snowflake ID，因此没有使用数据库自增主键。
- 同时保留业务层预检查和数据库唯一约束：前者提供清晰错误，后者保证并发正确性。

### 验证证据

- `backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthController.java`
- `backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthService.java`
- `backend/codetrove-auth/src/main/java/com/codetrove/auth/UserRepository.java`
- `backend/codetrove-common/src/main/java/com/codetrove/common/id/SnowflakeIdGenerator.java`
- `backend/codetrove-bootstrap/src/main/resources/db/migration/V2__create_user.sql`
- 集成测试验证数据库中为 `$2a$12$...` 哈希且响应无 `passwordHash`。

### 可用于简历的表述

> 实现用户注册与账号持久化，使用 Bean Validation 完成用户名/密码约束，采用 BCrypt cost 12 保存密码哈希，并通过 Snowflake 生成 64 位用户 ID、数据库唯一约束处理并发重名冲突。

### 当前边界

- 邮箱注册、验证码、找回密码和账号资料编辑尚未实现。

---

## 功能 M1.1-F02：JWT 登录与无状态认证

### 新增能力

- 实现 `POST /api/v1/auth/login`。
- 签发 15 分钟 HMAC-SHA256 JWT Access Token。
- 实现 Bearer Token 解析、验签、过期判断和 Spring Security 认证上下文。
- 实现 `GET /api/v1/users/me`。

### 技术栈

- Spring Security 6
- JJWT 0.12.6
- HMAC-SHA256
- OncePerRequestFilter
- Stateless SecurityFilterChain

### 实现细节

- JWT 只包含最少声明：用户 ID、用户名、签发时间、过期时间和 token ID。
- JWT Secret 由 Windows 用户环境变量 `CODETROVE_JWT_SECRET` 注入，启动时校验至少 32 UTF-8 字节。
- SecurityFilterChain 使用 `STATELESS`，不创建服务器 Session。
- `JwtAuthenticationFilter` 在每次请求中验签，并重新查询用户状态；只有 `ACTIVE` 用户可建立认证上下文。
- `/api/v1/users/me` 必须携带 `Authorization: Bearer <token>`。
- 无 Token/无效 Token 返回 401 `AUTH_TOKEN_INVALID`，过期 Token 返回 401 `AUTH_TOKEN_EXPIRED`，权限不足返回 403 `ACCESS_DENIED`。
- 禁用 Spring Security 自动生成的默认开发账号密码，避免产生无效认证入口和日志噪音。

### 难点与取舍

- 当前只实现短期 Access Token，不提前引入 Refresh Token 存储、轮换、撤销和 Cookie 安全策略。
- Token 不只验签，还重新校验数据库中的用户状态，防止被禁用账号继续使用未过期 Token。
- SecurityFilterChain 只在 Servlet Web 环境创建，保证非 Web 的 Spring Boot 上下文测试仍能启动。

### 验证证据

- `backend/codetrove-auth/src/main/java/com/codetrove/auth/JwtTokenService.java`
- `backend/codetrove-auth/src/main/java/com/codetrove/auth/JwtAuthenticationFilter.java`
- `backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthSecurityConfiguration.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/auth/AuthApiTests.java`
- 最终真实 JAR 注册、登录、Bearer Token、`/users/me` 闭环全部通过。

### 可用于简历的表述

> 基于 Spring Security 6 和 JJWT 实现无状态认证，签发 15 分钟 HMAC-SHA256 Access Token，通过自定义 Filter 完成验签、过期判断和用户状态复核，并统一 401/403 错误契约。

### 当前边界

- Refresh Token、Token 撤销、密钥轮换、多设备会话管理尚未实现。
- Access Token 不应保存到浏览器 Local Storage；前端登录态尚未接入。

---

## 功能 M1.1-F03：登录防枚举与统一失败语义

### 新增能力

- 用户不存在和密码错误均返回相同错误码与消息。
- 不存在用户也执行 BCrypt 虚拟校验，降低明显的登录时序差异。
- 错误响应继续携带 Trace ID，不泄露账号是否存在。

### 技术栈

- Spring Security Crypto
- BCrypt
- 统一业务异常与错误码
- MockMvc 集成测试

### 实现细节

- 登录时先选择真实密码哈希或启动阶段生成的 Dummy BCrypt Hash，再执行 `matches`。
- 无论用户名不存在、密码错误、账号被锁定或禁用，外部都收到 `AUTH_INVALID_CREDENTIALS`。
- 只有认证成功时才签发 JWT。
- 密码、Token 和真实 JWT Secret 不写日志、不返回响应、不写入项目文件。

### 难点与取舍

- 仅统一错误文案不能阻止时序枚举，因此为不存在用户补一次 BCrypt 运算，使失败路径成本更接近。
- 该措施降低而非完全消除所有侧信道，生产环境仍需配合限流、失败延迟和账号锁定策略。

### 验证证据

- `backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthService.java`
- 自动化测试同时验证“存在用户 + 错误密码”和“不存在用户”均返回同一错误码。
- 项目 114 个非构建文件进行真实 JWT Secret 精确扫描，命中数为 0。

### 可用于简历的表述

> 针对登录账号枚举风险统一错误语义，并为不存在用户执行 Dummy BCrypt 校验以缩小时序差异；配套完成 Secret 环境变量注入与项目精确扫描，确保真实 JWT 密钥未进入源码和文档。

### 当前边界

- 登录限流、失败次数统计、指数退避和临时锁定尚未实现，应在后续安全增强中补充。

---

## 功能 M1.1-F04：认证自动化测试与真实环境验收

### 新增能力

- 建立认证 API 集成测试。
- 同时验证 H2 空库迁移和真实 Windows MySQL 迁移。
- 对最终可执行 JAR 做真实 HTTP 认证闭环。
- 自动清理验收临时账号。

### 技术栈

- Spring Boot Test
- MockMvc
- JUnit 5
- AssertJ / Hamcrest
- H2 MySQL Mode
- MySQL CLI
- PowerShell

### 实现细节

- 6 项认证测试覆盖注册、BCrypt、登录、`/users/me`、重复用户名、错误密码、不存在用户、未认证、无效 Token 和过期 Token。
- 后端完整 `verify` 共执行 11 项测试，0 失败、0 错误、0 跳过，所有模块 Checkstyle 0 违规。
- H2 从空库依次执行 V1/V2，重复启动确认无需重复迁移。
- 真实 MySQL 的 Flyway 版本验证为 2，`codetrove_user` 表存在。
- 使用随机临时账号完成最终 JAR 的注册、登录、Token 访问和 BCrypt 检查，随后删除测试账号；临时账号数量为 0。
- 识别并处理 Windows 运行中 JAR 文件锁，确保最终 repackage 成功后再启动最新产物。

### 验证证据

- `docs/12-m1-auth-verification-record.md`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/auth/AuthApiTests.java`
- 最终真实闭环断言：`RegisterId/Token/Me/Bcrypt/NoPlain/AllPassed` 均为 `true`。

### 可用于简历的表述

> 建立认证端到端测试体系，使用 MockMvc 覆盖 10 类成功/失败路径，并在 H2 空库与真实 MySQL 上验证 Flyway V2；最终通过可执行 JAR 完成注册—登录—Token 鉴权闭环，验收账号自动清理。

### 当前边界

- 尚未建立性能测试、暴力破解压测和多实例密钥一致性测试。

---

# 5. M1.2 仓库与成员权限基线

## 功能 M1.2-F01：仓库元数据与受控存储路径

### 新增能力

- 实现仓库创建、列表和详情 API。
- 使用 MySQL 保存仓库元数据与成员关系。
- 创建者在同一事务中自动成为 OWNER。
- 使用服务端配置的根目录生成仓库路径，API 不暴露绝对路径。

### 技术栈

- Spring MVC
- Spring JDBC
- MySQL 8
- Flyway V3
- Spring Transaction
- Snowflake ID

### 实现细节

- V3 迁移创建 `codetrove_repository` 和 `codetrove_repository_member`，包含外键、角色/状态检查约束及查询索引。
- `(owner_id, slug)` 唯一约束保证同一所有者下仓库 slug 唯一。
- 仓库创建先写入 `INITIALIZING` 与 OWNER 成员，文件初始化成功后更新为 `ACTIVE`。
- 存储路径固定为受控根目录下的 `owner/slug.git`，经过 `normalize` 后再次检查必须以根目录开头。
- 外部响应仅包含逻辑 `gitHttpUrl`，不包含 `storage_path`。

### 难点与取舍

- 数据库事务不能天然覆盖文件系统，因此使用事务同步回调，在最终回滚时补偿删除本次新建目录。
- 已存在目录拒绝复用，并明确禁止补偿逻辑删除，防止元数据冲突或孤儿目录导致误删。

### 验证证据

- `backend/codetrove-bootstrap/src/main/resources/db/migration/V3__create_repository_and_member.sql`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryService.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryMetadataRepository.java`
- `docs/14-m1-repository-verification-record.md`

### 可用于简历的表述

> 设计仓库元数据与成员关系模型，使用 Flyway V3 落地唯一约束、外键和权限索引；通过 `INITIALIZING → ACTIVE` 状态与事务回滚补偿协调 MySQL 和本地 Git 文件系统，并防止绝对路径泄露和已有目录误删。

### 当前边界

- 仓库更新、归档、删除和成员管理 API 尚未实现。
- 文件系统与数据库之间不是分布式事务，当前通过状态机和补偿降低不一致风险，后续需增加恢复扫描任务。

---

## 功能 M1.2-F02：JGit bare 仓库与 README 初始提交

### 新增能力

- 创建真实 bare Git 仓库。
- 可选创建 `main` 分支与 README 初始提交。
- 设置 bare 仓库 HEAD 指向 `refs/heads/main`。

### 技术栈

- Eclipse JGit 7.3
- Git Object Database
- ObjectInserter
- DirCache / CommitBuilder / RefUpdate
- Git for Windows CLI（验收）

### 实现细节

- 使用 `Git.init().setBare(true)` 创建裸仓库，不生成工作区。
- 初始化 README 时不创建临时 clone，而是使用 ObjectInserter 直接写入 blob、tree 和 commit 对象。
- 通过 RefUpdate 创建 `refs/heads/main`，并将 HEAD 链接到默认分支。
- 初始化异常时解除 Windows 只读属性并逆序清理本次产生的目录。
- 同名目标目录在调用前已存在时直接失败且保留原内容。

### 难点与取舍

- 直接写 Git 对象比“创建临时工作区 → commit → file:// push”更轻量，减少磁盘 IO 和额外异常面。
- Windows Git 对象文件可能只读，普通递归删除会出现 AccessDenied，因此补偿清理显式处理只读属性。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryStorage.java`
- 自动化测试使用 JGit 读取 bare 仓库和 README。
- 真实验收使用 Git CLI 验证 bare、HEAD 和 `main:README.md`。

### 可用于简历的表述

> 基于 JGit 实现单节点 bare Git 仓库初始化，直接通过 Git 对象模型写入 README 的 blob/tree/commit 并建立默认分支，避免临时 clone/push 开销；针对 Windows 只读对象文件完善失败补偿清理。

### 当前边界

- Git Smart HTTP 已在 M1.3 开放，标准 Git 客户端可以 clone、fetch 和 push 普通分支。
- 当前仍为单节点本地文件系统，不具备多节点高可用。

---

## 功能 M1.2-F03：四角色权限矩阵与私有仓库隔离

### 新增能力

- 建立 `OWNER/MAINTAINER/DEVELOPER/REPORTER` 四角色权限矩阵。
- 仓库创建、列表与详情要求 JWT 认证。
- 私有仓库对非成员在列表中不可见，详情统一返回 404。
- 公开仓库对已认证非成员可读。

### 技术栈

- Spring Security
- 自定义 Repository Authorization Service
- MySQL RepositoryMember
- MockMvc

### 实现细节

- 权限按 `READ/CREATE_MERGE_REQUEST/COMMENT/PUSH/MERGE/MODIFY_RULES/MANAGE_MEMBERS/MANAGE_REPOSITORY` 建模。
- OWNER 拥有全部权限；MAINTAINER 不管理成员/仓库；DEVELOPER 不改规则；REPORTER 仅有读、创建 MR 和评论权限。
- 列表查询在 SQL 层同时过滤 `PUBLIC` 或当前用户成员关系，避免先查出私有数据再在内存过滤。
- 私有仓库不存在和无权限统一返回 `REPOSITORY_NOT_FOUND`，降低资源枚举风险。
- 公共认证主体下沉至 `common`，仓库模块读取安全上下文而不反向依赖 auth 模块。

### 难点与取舍

- 授权矩阵先作为稳定领域服务建立，后续 Git push、成员管理和 MR merge 端点必须复用，避免每个 Controller 手写角色判断。
- 本阶段只验证矩阵和读路径隔离，没有假装成员管理端点已经实现。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryAuthorizationService.java`
- `backend/codetrove-common/src/main/java/com/codetrove/common/security/AuthenticatedUser.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryApiTests.java`
- 真实 owner/outsider 双用户验证私有 404 与公开可见性。

### 可用于简历的表述

> 建立 OWNER/MAINTAINER/DEVELOPER/REPORTER 四级仓库权限矩阵，将可见性条件下推到 SQL 查询；私有仓库对非成员统一返回 404，避免资源枚举，并通过公共认证主体保持 auth 与 repository 模块单向依赖。

### 当前边界

- 成员邀请、角色修改、成员删除 API 尚未实现。
- PUSH 权限已在 M1.3 Git Smart HTTP 中完成端到端验证，MERGE 权限已在 M1.6 完成；规则修改 API 仍未实现。

---

## 功能 M1.2-F04：仓库游标分页与双层验收

### 新增能力

- 仓库列表支持 1～100 条分页限制和不透明游标。
- 建立 H2 集成测试与真实 MySQL/JGit 双层验收。
- 验收结束后自动清理临时用户、成员、仓库和文件目录。

### 技术栈

- Base64 URL-safe Cursor
- Keyset Pagination
- Spring Boot Test / MockMvc
- H2 MySQL Mode
- MySQL CLI / Git CLI / PowerShell

### 实现细节

- 游标编码最后一条仓库的 Snowflake ID，查询使用 `id < beforeId ORDER BY id DESC`，避免 offset 深分页。
- 返回统一 `meta.nextCursor`，最后一页省略；非法 Base64 或非正 ID 返回 `VALIDATION_FAILED`。
- `limit` 通过方法级校验限制在 1～100，并统一映射为 400。
- 后端完整 `verify` 共 21 项测试，0 失败、0 错误、0 跳过，所有模块 Checkstyle 0 违规。
- 真实 MySQL 已迁移到 V3；最终 JAR 的 11 个仓库闭环断言全部为 true。
- 验收后确认数据库临时用户/仓库与文件系统临时目录均为 0。

### 难点与取舍

- 使用 keyset cursor 而非 offset，保持大列表分页性能和新增记录时的稳定性。
- 真实验收初版清理脚本失败，但业务断言均通过；随后修正 MySQL 默认库和 Windows 只读文件处理，并以独立检查确认零残留。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryController.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryService.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryApiTests.java`
- `docs/14-m1-repository-verification-record.md`

### 可用于简历的表述

> 为仓库列表实现基于 Snowflake ID 的 Keyset Pagination，并使用 Base64 URL-safe 不透明游标统一返回 `nextCursor`；建立 H2 与真实 MySQL/JGit 双层验收，21 项后端测试全通过且测试数据零残留。

### 当前边界

- 当前未实现复杂筛选和全文搜索；搜索能力将在容量与检索需求明确后评估。

---

# 6. M1.3 Git Smart HTTP 基线

## 功能 M1.3-F01：标准 Git Smart HTTP clone/fetch/push

### 新增能力

- 在 `/git/{owner}/{repo}.git` 提供标准 Git Smart HTTP 服务。
- 支持外部 Git 客户端 clone、fetch 和普通分支 push。
- 通过 `GitServlet`、`UploadPack` 和 `ReceivePack` 直接服务平台创建的 bare 仓库。

### 技术栈

- Eclipse JGit 7.3 HTTP Server
- Jakarta Servlet
- Spring Boot Embedded Tomcat
- Git for Windows 2.55

### 实现细节

- `GitServlet` 注册到 `/git/*`，由自定义 RepositoryResolver 根据严格的 `owner/repo.git` 解析数据库元数据。
- Resolver 只打开状态为 `ACTIVE` 且当前用户可见的仓库，文件系统路径始终来自数据库受控字段，不接受客户端绝对路径。
- UploadPack 和 ReceivePack 分别配置 I/O 超时；写入 reflog 使用当前认证用户名和平台内部邮箱域。
- REST API 与 Git 协议保持独立认证入口：REST 用 Bearer JWT，Git 用 Basic Auth。

### 难点与取舍

- 首版只实现 HTTP，不提前加入 SSH 与 Git LFS，保持本地单节点 MVP 可验证。
- JGit 7.3 使用 Jakarta Servlet，需要显式补充 Servlet API 并按源码核对异常与工厂签名。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitSmartHttpConfiguration.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/CodeTroveRepositoryResolver.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitPackFactories.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitCliEndToEndTests.java`
- `scripts/verify-m1-git.ps1`
- `docs/15-m1-git-smart-http-verification-record.md`
- 系统 `git.exe` 实际完成两次 clone、feature push 与第二工作区 fetch。
- Windows MySQL 8.0.43、Docker Redis 7.4.2 与最终 JAR 的 7 阶段真实环境脚本通过，临时用户、元数据和仓库目录均为 0。

### 可用于简历的表述

> 基于 JGit 7.3 与 Spring Boot Embedded Tomcat 实现 Git Smart HTTP 服务，通过自定义 RepositoryResolver、UploadPack/ReceivePack Factory 支持标准 Git 客户端 clone、fetch 和普通分支 push，并将 URL 解析绑定到数据库元数据与受控 bare 仓库路径。

### 当前边界

- 当前仅支持 HTTP，生产部署需要 TLS 终止；尚未支持 SSH、Git LFS 和多节点仓库存储。
- 分支/文件 REST 浏览已在 M1.4 完成，MR/Diff/评论基础已在 M1.5 完成，基础服务端 Merge 已在 M1.6 完成，当前 head Check 门禁已在 M2 完成；Review/Approval 仍未实现。

---

## 功能 M1.3-F02：Git Basic Auth、仓库授权与默认分支保护

### 新增能力

- Git Smart HTTP 使用 UTF-8 Basic Auth，并复用平台账号密码校验。
- clone/fetch 接入 `READ` 可见性，push 接入四角色 `PUSH` 权限。
- 私有仓库非成员不可发现；REPORTER 可读但不能 push。
- 默认分支 `main` 禁止直接创建、更新和删除。

### 技术栈

- Spring `OncePerRequestFilter`
- Spring Security Crypto / BCrypt
- JGit `PreReceiveHook`
- Repository Authorization Service

### 实现细节

- 将密码认证抽象为 `PasswordAuthenticationService` 下沉到 common，REST 登录与 Git Basic Auth 共用用户规范化、BCrypt、状态检查和 Dummy BCrypt 逻辑，避免安全策略分叉。
- JWT Filter 对 `/git/**` 跳过，防止 Basic Header 被误判为 Bearer Token。
- RepositoryResolver 对私有非成员抛出 RepositoryNotFound，避免暴露仓库存在性。
- ReceivePackFactory 只向 OWNER、MAINTAINER、DEVELOPER提供写服务，REPORTER 被拒绝。
- PreReceiveHook 检查整批 ref 命令；只要触及默认分支，就拒绝全部未执行命令，避免批量 push 部分成功。
- 禁止 non-fast-forward push；保护分支错误消息固定为 `protected branch requires merge request`。

### 难点与取舍

- JGit 将 ReceivePack 的 `ServiceNotAuthorizedException` 映射为 HTTP 401，Git 客户端展示 `Authentication failed`；验收不只依赖文案，还使用 OWNER `ls-remote` 确认拒绝后远端没有分支。
- 保护策略按整批命令原子拒绝，而不是只拒绝 `main` 后放行同批其他 ref，防止用户误以为整次 push 成功。

### 验证证据

- `backend/codetrove-common/src/main/java/com/codetrove/common/security/PasswordAuthenticationService.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitBasicAuthenticationFilter.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/ProtectedBranchHook.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/repository/GitPolicyTests.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitCliEndToEndTests.java`

### 可用于简历的表述

> 为 Git Smart HTTP 设计独立 Basic Auth 入口并复用 BCrypt 账号校验，将仓库 READ/PUSH 权限接入 JGit Resolver 与 ReceivePackFactory；通过 PreReceiveHook 原子拒绝所有触及 main 的批量 push，并以真实 Git CLI 验证 Reporter 越权写入失败且远端无残留分支。

### 当前边界

- 成员管理 API 尚未实现，测试中的 REPORTER 关系通过数据库夹具建立。
- 分支保护目前固定保护默认分支，尚未提供仓库级规则配置 API。

---

## 功能 M1.3-F03：Git 协议资源治理与可重复验收

### 新增能力

- 对 Git HTTP 请求、command、单对象、pack、I/O 超时和并发请求设置独立限制。
- 建立 HTTP 专项、策略专项和系统 Git CLI 三层自动化验收。
- 处理 Windows 上 JGit pack 映射文件的确定性测试清理。

### 技术栈

- Servlet Filter / Semaphore
- JGit ReceivePack limits
- Spring Boot Random Port Test
- Java ProcessBuilder
- Git for Windows CLI
- JUnit 5 / AssertJ

### 实现细节

- 默认限制为：60 秒 I/O 超时、110 MiB HTTP 请求、1 MiB command、20 MiB 单对象、100 MiB pack、8 个并发 Git 请求。
- Content-Length 超限在认证和 JGit 前返回 413；并发许可耗尽返回 429 与 `Retry-After: 1`。
- chunked 请求无法只靠 Content-Length 预检，因此由 JGit 的 command/object/pack 上限继续兜底。
- Git CLI 测试关闭凭据助手并设置 `GIT_TERMINAL_PROMPT=0`，避免测试挂起或污染用户凭据存储。
- 测试结束通过 `WindowCacheConfig.install()` 释放 JGit pack 缓存，并保留最多 5 次短暂重试；持续文件句柄泄漏仍会失败。

### 难点与取舍

- 首次 CLI 验收将测试 HTTP 上限设得低于正常 upload-pack 协商体积，导致正常 clone 被 413/406 链路拦截；随后将测试上限调整为 4 KiB，并用 4097 bytes 单独验证 413，生产默认值不变。
- 资源限制不只停留在配置文件，而是通过真实 HTTP 状态、JGit 内部配置和 Git CLI 行为分别验证。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRequestLimitFilter.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitSmartHttpTests.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/repository/GitPolicyTests.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitCliEndToEndTests.java`
- 最终后端 `clean verify`：32 项测试全部通过，0 失败、0 错误、0 跳过，所有模块 Checkstyle 0 违规。

### 可用于简历的表述

> 为 Git Smart HTTP 增加请求体、command、单对象、pack、I/O 超时和并发请求六类资源限制，并建立真实端口与系统 Git CLI 端到端测试；覆盖 clone/fetch/push、保护分支、越权写入和 413 场景，最终 32 项后端测试与全模块 Checkstyle 全部通过。

### 当前边界

- 尚未完成同一仓库并发 push 的完整性压力测试和仓库级写锁。
- 尚未验证生产反向代理的 body/time-out 配置与应用限制保持一致。

---

# 7. M1.4 分支与文件浏览 API

## 功能 M1.4-F01：受权限控制的 Git 分支与目录树浏览

### 新增能力

- 实现分支列表、根目录和子目录浏览。
- 支持按本地分支或完整 40 位 commit ID 固定读取版本。
- tree 结果按名称稳定排序并支持不透明游标分页。
- 复用仓库可见性规则：私有仓库非成员 404，公开仓库已认证非成员可读。

### 技术栈

- Eclipse JGit `RevWalk` / `TreeWalk`
- Spring MVC / Spring Security
- Base64 URL-safe Cursor
- JUnit 5 / MockMvc

### 实现细节

- 分支接口只枚举 `refs/heads/*`，返回 commit、短消息、作者、提交时间和默认分支标记。
- ref 只允许本地分支、`refs/heads/*` 和完整 commit ID，不把用户输入交给通用 revision 表达式解析。
- tree 只列当前目录，区分 `TREE/BLOB/SYMLINK/GITLINK`；文件返回对象大小，目录不虚构大小。
- 分页游标编码最后一个 entry name，下一页按名称继续，避免 offset 扫描。
- 仓库打开时重新计算受控路径并与数据库 `storage_path` 精确比对。

### 难点与取舍

- 为了保证版本确定性，支持完整 commit ID，但主动拒绝 tag、缩写 SHA、reflog 和 `HEAD~1` 等表达式，缩小解析攻击面。
- 不做递归目录树，避免大仓库一次响应导致内存和 JSON 体积失控；客户端按目录逐级浏览。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryBrowser.java`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryController.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryBrowseApiTests.java`
- `scripts/verify-m1-browse.ps1`
- `docs/16-m1-repository-browse-verification-record.md`

### 可用于简历的表述

> 基于 JGit RevWalk/TreeWalk 实现受权限控制的分支与代码目录浏览，仅允许本地分支或完整 commit ID 固定版本；目录按名称稳定排序并采用 Base64 URL-safe 游标分页，私有仓库非成员统一返回 404。

### 当前边界

- 未实现 tag、提交历史、blame、搜索和递归整树返回。
- 前端仓库页面尚未接入真实 API。

---

## 功能 M1.4-F02：安全受限的文本文件读取

### 新增能力

- 实现 Git blob 元数据与 UTF-8 文本内容读取。
- 二进制和超大文本只返回元数据及不内联原因。
- 路径、对象类型和存储根边界均有确定错误语义。

### 技术栈

- JGit ObjectLoader
- Java NIO CharsetDecoder
- Git Object Database
- Spring 配置注入

### 实现细节

- 默认内联阈值 1 MiB，通过 `CODETROVE_REPOSITORY_MAX_INLINE_BLOB_BYTES` 配置。
- 小文件先检测 NUL，再用 `CodingErrorAction.REPORT` 严格解码 UTF-8，避免替换字符掩盖二进制或损坏编码。
- 超大对象不读取全量内容，只采样最多 8192 bytes 判断二进制；分别返回 `BINARY` 或 `TOO_LARGE`。
- 文件内容只从 Git Object Database 读取，仓库中的 symlink 仅作为 blob 文本，不跟随宿主文件系统链接。
- 路径拒绝绝对路径、反斜杠、NUL、`.`/`..`、空段和尾随 `/`。

### 难点与取舍

- 没有为了“能打开所有文件”直接返回任意二进制或提供下载端点，优先控制内存、响应体和浏览器执行风险。
- 对大文本与二进制分别返回明确原因，避免前端把所有未内联文件都误判为二进制。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryBrowser.java`
- `backend/codetrove-common/src/main/java/com/codetrove/common/exception/ErrorCode.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryBrowseApiTests.java`
- 真实环境使用 Git push 构造 UTF-8、二进制和超过 1 MiB 的文件，REST 分类结果全部通过。

### 可用于简历的表述

> 基于 JGit ObjectLoader 实现代码文件读取，对 Git 内部路径进行白名单校验，并用 NUL 检测与严格 UTF-8 解码区分文本/二进制；通过 1 MiB 内联阈值和 8 KiB 采样避免大对象进入 JSON 响应。

### 当前边界

- 不支持任意文件下载、Range 请求、语法高亮和非 UTF-8 文本转码。

---

## 功能 M1.4-F03：分支文件浏览的双层验收

### 新增能力

- 新增 8 组 JGit 真实对象集成测试。
- 新增 Windows MySQL、Docker Redis、最终 JAR 和真实 Git push 的 8 阶段验收脚本。
- 验收结束自动清理随机账号、元数据、Git 仓库和工作区并恢复服务。

### 技术栈

- Spring Boot Test / MockMvc
- H2 MySQL Mode
- Git for Windows CLI
- PowerShell 5.1
- Windows MySQL / Docker Redis

### 实现细节

- 测试夹具直接向 bare Git 写入嵌套目录、小文本、二进制和超大文本，并建立 main/feature 两个分支。
- 真实验收通过 Git Smart HTTP push 构造同类数据，再用 REST API 读取，避免只验证内部 JGit 调用。
- 分支 API 的 commit ID 与 `git rev-parse HEAD` 比对，证明 REST 与 Git 客户端看到同一事实源。
- 清理前停止后端释放 Windows 文件句柄，只删除随机账号对应且位于受控根目录的目标仓库。

### 难点与取舍

- PowerShell 5.1 对单个管道结果的 `.Count` 不稳定，断言统一使用显式数组计数。
- 真实验收第一次在嵌套条目断言处失败，但 `finally` 仍完成零残留清理与服务恢复；修正断言后完整 8 阶段通过。

### 验证证据

- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryBrowseApiTests.java`
- `scripts/verify-m1-browse.ps1`
- 后端 `clean verify`：40 项测试全部通过，0 失败、0 错误、0 跳过，全模块 Checkstyle 0 违规。
- 真实验收后临时用户、仓库元数据和目录均为 0，MySQL Running、Redis healthy、Readiness UP。

### 可用于简历的表述

> 为仓库浏览建立 H2 集成测试与 Windows 真实环境双层验收：使用真实 Git push 构造多类型对象，校验分支 commit、tree 分页、文本/二进制/超大文件和越权路径；最终 40 项后端测试全通过且验收数据零残留。

### 当前边界

- 当前验证以功能正确性和安全失败路径为主，尚未做大仓库 tree 延迟与高并发读取压测。

---

# 8. M1.5 Merge Request、Diff 与评论基础

## 功能 M1.5-F01：MR 聚合、仓库内编号与乐观锁

### 新增能力

- 实现 MR 创建、列表、详情、标题/描述更新和关闭。
- 创建时保存真实 Git 源/目标分支的 head/base commit 快照。
- 为每个仓库分配独立递增 iid，并拒绝重复 OPEN 源/目标组合。
- 使用 version 乐观锁防止并发更新覆盖。

### 技术栈

- Spring MVC / Bean Validation
- Spring JDBC / Spring Transaction
- MySQL 8 / Flyway V4
- JGit RefDatabase
- Snowflake ID / Keyset Pagination

### 实现细节

- V4 创建 `codetrove_merge_request` 和 `codetrove_merge_request_comment`，包含外键、检查约束、查询索引和系统评论 fingerprint 唯一索引。
- Repository 模块暴露最小只读 `RepositoryAccessService`，MR 模块通过公共门面复用可见性、权限和受控路径验证，不直接依赖 repository 的持久化实现。
- 创建 MR 只解析精确 `refs/heads/*`，客户端不能指定 author、iid、base/head commit 或 version 初值。
- 同一事务中 `SELECT ... FOR UPDATE` 锁定仓库行，再查询 MAX(iid)+1 并检查重复 OPEN 组合。
- 更新条件包含 `status='OPEN' AND version=?`；成功后 version 加一，陈旧请求返回 `STATE_CONFLICT`。
- 作者、OWNER、MAINTAINER可编辑/关闭；非作者普通成员不可修改他人 MR。

### 难点与取舍

- MySQL/H2 缺少统一易维护的“仅 OPEN 时组合唯一”部分索引语法，因此首版在仓库行锁临界区内完成检查与插入，以数据库事务保证同仓库串行化。
- M1.5 固定创建时快照，不提前伪造 push 后自动跟随；后续通过 ref 更新事件维护 `MergeRequestCommit`。
- 公开仓库非成员角色为 null，修复权限服务空角色 NPE，确保写请求稳定返回 403 而非 500。

### 验证证据

- `backend/codetrove-bootstrap/src/main/resources/db/migration/V4__create_merge_request_and_comment.sql`
- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryAccessService.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestService.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/mergerequest/MergeRequestApiTests.java`
- `docs/17-m1-merge-request-verification-record.md`

### 可用于简历的表述

> 基于 Spring JDBC、Flyway V4 与 JGit 实现 Merge Request 聚合：服务端解析本地分支并固化 base/head commit，通过仓库行锁分配仓库内 iid、约束重复 OPEN MR，并以 version 条件更新实现乐观锁和确定的并发冲突语义。

### 当前边界

- M1.5 创建时固定 head 快照的阶段边界已由 M1.6 补齐：成功 push 普通源分支会更新 OPEN MR head/version 并追加提交历史。
- 当前 head 的 Check 门禁已由 M2 补齐；Review/Approval 仍未实现，MR 仍不支持重开。

---

## 功能 M1.5-F02：受资源限制的 JGit Diff 与行级评论校验

### 新增能力

- 基于 MR 保存的 base/head 快照生成文件级 Diff、增删统计和 patch。
- 识别二进制文件并限制文件数、单文件 patch 与总 patch 体积。
- 实现普通评论、行级评论和评论游标分页。
- 对行级评论的 commit、路径、OLD/NEW side 和行号做服务端事实校验。

### 技术栈

- JGit `DiffFormatter` / `DiffEntry` / `FileHeader` / `EditList`
- RawTextComparator / Rename Detection
- Spring MVC / Spring JDBC
- Base64 URL-safe Cursor

### 实现细节

- Diff 固定比较数据库中的 base/head commit，客户端不能构造任意提交对绕过 MR 范围。
- 默认最多 200 个文件、单文件 patch 256 KiB、总 patch 2 MiB；使用有界 OutputStream 在 JGit 写入时限制内存，而非生成完整 patch 后再截断。
- 二进制返回统计和路径但不返回 patch；超限文件与整体响应设置 `truncated=true`。
- Diff 展示与评论定位共用 `FileHeader.toEditList()`：NEW 只接受新增区间，OLD 只接受删除区间，新增/删除文件分别使用对应路径。
- commit 必须等于 MR head commit；错误 commit/path/side/line 返回 `MR_DIFF_POSITION_INVALID` 且不写数据库。
- 评论保存 Markdown 原文；前端渲染时继续要求净化，不在后端接受可执行 HTML。

### 难点与取舍

- 未引入 Redis Diff 缓存；当前先保证固定快照、资源限制和定位一致性，后续只有在性能指标证明需要时才缓存。
- 行级评论不相信前端上传的行号，通过 JGit 重新计算 Edit 区间，代价是每次评论需重新读取 Diff，但安全语义明确。

### 验证证据

- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestDiffService.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestCollaborationService.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestCommentRepository.java`
- 集成测试覆盖文本、二进制、超大 patch、普通评论、有效与无效行级评论。

### 可用于简历的表述

> 基于 JGit DiffFormatter 实现固定 base/head 快照 Diff，返回文件状态与增删统计，并通过文件数、单文件/总 patch 字节上限及二进制分流控制响应资源；复用 EditList 校验行级评论的 commit、路径、OLD/NEW side 和真实变更行，避免客户端伪造定位。

### 当前边界

- 尚未缓存 Diff，也未实现跨 push 的增量 Diff 和过期评论迁移。
- 评论编辑、删除、thread/resolved 和 Review 审批尚未实现。

---

## 功能 M1.5-F03：MR 主链路双层验收与零残留清理

### 新增能力

- 新增 11 组 MR/Diff/评论集成测试。
- 新增最终 JAR、真实 MySQL/Redis 和系统 Git CLI 的 8 阶段验收脚本。
- 自动清理临时用户、MR、评论、仓库元数据、工作区与 bare 仓库，并恢复后端。

### 技术栈

- Spring Boot Test / MockMvc / JUnit 5
- H2 MySQL Mode
- Git for Windows / Git Smart HTTP
- PowerShell 5.1 / MySQL CLI
- Windows MySQL / Docker Redis

### 实现细节

- 自动化测试直接构造真实 Git commit/tree/ref，验证 API 与 Git Object Database 使用同一事实源。
- 资源边界测试创建 NUL 二进制与超过 256 KiB 的文本 patch，验证不内联和 truncated 语义。
- 实机脚本真实 clone、commit、push 后创建 MR，将 API base/head 与 `git rev-parse` 比对。
- 真实调用普通/行级评论、错误行、游标分页、私有隔离、陈旧 version、关闭及关闭后评论拒绝。
- 清理阶段先确认 8080 进程属于 CodeTrove，再按外键顺序删除评论、MR、成员、仓库和用户；仓库目录必须位于受控根且名称匹配随机 slug。

### 验证证据

- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/mergerequest/MergeRequestApiTests.java`
- `scripts/verify-m1-merge-request.ps1`
- `docs/17-m1-merge-request-verification-record.md`
- 后端 `clean verify`：51 项测试全通过，0 失败、0 错误、0 跳过，全模块 Checkstyle 0 违规。
- 真实 8 阶段验收通过；临时用户、MR、评论、仓库元数据和目录均为 0，MySQL Running、Redis healthy、Readiness UP。

### 可用于简历的表述

> 为 MR 主链路建立 H2 与 Windows 真实环境双层验收：通过真实 Git Smart HTTP push 构造分支，验证 MR 快照、受限 Diff、普通/行级评论、定位拒绝、乐观锁和关闭状态；最终 51 项后端测试全通过，并实现测试数据与 bare 仓库零残留清理和服务恢复。

### 当前边界

- 尚未覆盖高并发创建 MR 的压力测试、大仓库 Diff 延迟和恶意超大 Diff 性能测试。
- Merge commit、冲突检测和幂等 Merge 已在 M1.6 完成，当前 head Check 门禁已在 M2 完成；Review/Approval 仍未实现。

---

# 9. M1.6 MR Head 同步与基础幂等 Merge

## 功能 M1.6-F01：push 驱动的 MR Head 同步与提交历史

### 新增能力

- 成功 push 普通分支后，自动更新所有匹配 OPEN MR 的 `head_commit` 与 `version`。
- 在创建 MR 时记录初始 head，并为后续 head 变化追加有序、去重的提交历史。
- 删除源分支时保留 MR 最后有效快照，避免写入空 commit。

### 技术栈

- JGit `PreReceiveHook` / `PostReceiveHook`
- Spring 事件式接口边界
- Spring JDBC / Spring Transaction
- MySQL 8 / Flyway V5
- Snowflake ID

### 实现细节

- Repository 模块发布不可变 `RepositoryRefUpdate`，MR 模块通过 `RepositoryRefUpdateListener` 消费，避免仓库模块反向依赖 MR 实现。
- post-receive 只转发 `Result.OK` 且非删除的 `refs/heads/*` 更新；拒绝或失败的命令不会污染 MR。
- 同步查询使用 `repository_id + source_branch + status='OPEN'`，新 head 与旧值不同才更新并递增 version。
- `codetrove_merge_request_commit` 同时约束 `(mr_id, commit_id)` 与 `(mr_id, sequence_number)` 唯一，防止重试写重和顺序冲突。
- 创建 MR 的同一事务写入 sequence 1 初始 head，保证历史从业务起点完整记录。

### 难点与取舍

- JGit pre/post receive 回调不保证同线程，不能用要求同线程释放的 `ReentrantLock`；最终使用公平 `Semaphore` + 原子幂等 `Lease`。
- M1.6 在 push 请求内同步更新数据库，语义直接但会增加 push 尾延迟；M2 再引入 Outbox/Kafka 处理 Check 和异步任务，不提前伪造可靠事件链路。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryReceiveHooks.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestHeadSynchronizer.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestHistoryRepository.java`
- `backend/codetrove-bootstrap/src/main/resources/db/migration/V5__create_merge_history_and_operation.sql`
- `docs/18-m1-merge-verification-record.md`
- 自动化测试验证初始/后续两条历史；真实 Git Smart HTTP 二次 push 后 API head/version 与数据库 sequence 1/2 一致。

### 可用于简历的表述

> 基于 JGit ReceivePack Hook 实现 push 驱动的 MR head 同步，通过模块间 RefUpdate 监听边界解耦仓库与 MR；使用 Flyway V5 建立带双唯一约束的提交历史，保证初始 head 与后续更新有序、去重，并仅消费成功 ref 更新。

### 当前边界

- MR head 与提交历史仍在 push 请求内同步更新；M2 已在同一业务事务写 Outbox，并由 Kafka 异步驱动 Check 投影。
- 删除源分支保留最后快照，但 Merge 会因源引用不存在而失败；未实现自动关闭 MR。

---

## 功能 M1.6-F02：冲突安全的两父 Merge Commit 与引用 CAS

### 新增能力

- 实现 `POST /merge`，首版只支持 `MERGE_COMMIT`。
- 在仓库级写锁中重新校验权限、MR 状态、expected head 和源/目标引用。
- 使用 JGit 三方合并检测内容冲突，成功时生成两个父提交的 merge commit。
- 通过 expected-old-object compare-and-set 更新目标引用，防止覆盖并发变化。

### 技术栈

- Eclipse JGit `ResolveMerger` / `CommitBuilder` / `RefUpdate`
- Spring MVC / Spring JDBC
- Java `Semaphore`
- SHA-1 Git Object ID

### 实现细节

- `RepositoryWriteLockService` 按 repository ID 提供公平 Semaphore Lease，使 ReceivePack 与 REST Merge 在单 JVM 内共享同一写入临界区。
- 进入临界区后重新读取 source/target；请求 expected head 必须等于 MR head 与 source 实际值。
- JGit 从 merge base 对 source/target 做真实三方合并；冲突返回 `MR_MERGE_CONFLICT`，不更新目标引用或 MR。
- merge commit 的 parent 0 固定为 target-before，parent 1 固定为 source head，便于审计合并拓扑。
- 计算完成后再次读取 source，目标引用使用 `setExpectedOldObjectId` CAS；竞争变化返回 `GIT_REF_CHANGED`。
- Git 对象可能先写入但引用未更新，此时仅产生不可达对象，不会改变可见仓库状态，后续可由 Git GC 回收。

### 难点与取舍

- 数据库事务无法原子覆盖 Git 引用，因此将“目标引用 CAS”作为 Git 侧线性化点，并配套持久化操作记录恢复数据库终态。
- 当前锁仅为单节点 JVM 锁，不将其夸大为 Redisson/分布式锁；多实例部署必须先解决仓库节点路由或共享存储一致性。
- 本阶段不读取 Check 数据，不能把“没有 Check”解释成“Checks passed”。

### 验证证据

- `backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryWriteLockService.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestMergeService.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestController.java`
- 集成测试验证两父提交、错误 expected head、REPORTER 拒绝与 README 同行真实冲突。
- 真实环境 fetch 后确认远端 main 指向 merge commit，`git rev-list --parents` 返回目标旧提交与源 head 两个父提交。

### 可用于简历的表述

> 基于 JGit ResolveMerger 实现冲突安全的 `MERGE_COMMIT`：在仓库级 JVM 写锁内二次校验源 head，生成 target/source 两父提交，并使用 RefUpdate expected-old CAS 更新目标分支；冲突与引用竞态均返回确定错误且不改变 MR/目标分支。

### 当前边界

- 不支持 squash、rebase、fast-forward-only 或冲突在线解决。
- 当前 head 的阻塞型 Check 门禁已在 M2 接入；Review/Approval 仍未实现。

---

## 功能 M1.6-F03：持久化幂等 Merge、失败窗口恢复与双层验收

### 新增能力

- 使用仓库范围 `Idempotency-Key` 保证 Merge 重试返回同一结果。
- 绑定规范化请求哈希，拒绝同 key 复用到不同 MR/head/strategy。
- 通过 `PENDING/SUCCEEDED` MergeOperation 恢复“Git 已成功、数据库未完成”窗口。
- 建立 57 项自动化测试与最终 JAR 的 7 阶段真实环境验收。

### 技术栈

- SHA-256 Request Hash
- Spring JDBC / MySQL 事务
- Flyway V5
- JUnit 5 / MockMvc / H2 MySQL Mode
- PowerShell 5.1 / MySQL CLI / Git for Windows

### 实现细节

- `(repository_id, idempotency_key)` 唯一；request hash 由 iid、expected head 和 strategy 规范化后计算。
- 首次 Merge 在更新 Git 引用前保存 PENDING，成功后更新 MR 与操作为 SUCCEEDED。
- 重放 SUCCEEDED 直接返回原 merge commit；重放 PENDING 时只有目标引用等于记录的 merge commit 才恢复数据库终态。
- 集成测试模拟目标引用已更新后将数据库回退到 PENDING/OPEN，再以相同 key 重试，验证恢复为 `MERGED/SUCCEEDED`。
- `verify-m1-merge.ps1` 使用随机账号/仓库和临时 askpass，真实 clone、两次 push、Merge、fetch、幂等重放、数据库核对，并按受控路径安全清理。

### 难点与取舍

- MySQL 与 Git 文件系统没有分布式事务，因此明确建模可恢复窗口，而不是宣称强原子提交。
- 实机脚本初版使用数据库 CHAR 拼接字符串比较产生格式误判，但业务 Merge 与重放已成功；finally 零残留清理有效，随后改为 `RTRIM + 条件计数` 并完整重跑通过。
- Windows 运行中 JAR 会阻止 Maven Clean，验收流程先核对端口进程归属再停止后端，防止误杀其他服务。

### 验证证据

- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeOperationRepository.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestMergeService.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/mergerequest/MergeRequestApiTests.java`
- `scripts/verify-m1-merge.ps1`
- `docs/18-m1-merge-verification-record.md`
- 后端 `clean verify`：57 项测试全通过，0 失败、0 错误、0 跳过，全模块 Checkstyle 0 违规。
- 真实 7 阶段验收通过；临时用户/仓库/合并操作均为 0，Flyway V5，Readiness UP。

### 可用于简历的表述

> 为跨 MySQL 与 Git 引用的 Merge 设计持久化幂等与恢复机制：以仓库级 Idempotency-Key + SHA-256 请求哈希防止串用，PENDING 操作记录覆盖 Git 成功但数据库未完成窗口；通过 57 项测试和 Windows MySQL/Redis/真实 Git CLI 七阶段验收验证重放、恢复与零残留清理。

### 当前边界

- 恢复依赖客户端使用相同 Idempotency-Key 重试，尚未实现后台定时扫描 PENDING 操作。
- 当前没有多节点故障转移、分布式锁或远端对象存储。

---

# 10. M2 事件可靠性与 Check 门禁

## 功能 M2-F01：Transactional Outbox 与 Kafka 故障恢复

### 新增能力

- MR 创建、head 更新、关闭和合并与 Outbox 写入处于同一 MySQL 事务。
- 定时 Publisher 使用 Kafka 至少一次投递，支持发布成功、失败退避和最终 FAILED。
- Kafka 停机时业务事务仍可提交；恢复后积压事件最终发布。
- Kafka 状态纳入 Actuator Readiness。

### 技术栈

- Spring Kafka 3.3 / Apache Kafka 3.9.1
- Transactional Outbox
- Spring JDBC / TransactionTemplate
- MySQL 8 / Flyway V6
- Docker Compose / Kafka KRaft
- Spring Boot Actuator

### 实现细节

- `codetrove_outbox_event` 保存全局 event ID、类型、聚合版本、Topic/Key、Envelope、Trace ID、状态、attempts、available_at 与截断错误。
- `OutboxService` 由 MR 业务事务调用，序列化版本化 DomainEvent；业务表更新失败时 Outbox 同步回滚。
- Publisher 使用 `FOR UPDATE SKIP LOCKED` 在短事务中领取到期 PENDING 批次、递增 attempts 并把 `available_at` 推迟到租约截止时间，随后立即提交。
- Kafka send 在数据库事务外执行；成功或失败再以独立短事务按 `(id,status,attempts)` 条件回写，避免 Kafka 网络等待长期占用 Outbox 行锁。
- 进程在发送前后崩溃时，租约到期后记录可被重新领取；与消费幂等共同维持至少一次语义。
- Producer 配置 `acks=all` 与幂等发送；失败按 `2^attempt` 秒有限退避，达到上限转 FAILED。
- Kafka 使用单节点 KRaft，仅绑定 `127.0.0.1:9092`，本地开发无需 ZooKeeper。
- 自定义 Kafka HealthIndicator 查询 cluster ID；Kafka 不可用时 Readiness 为 DOWN，恢复后自动 UP。

### 难点与取舍

- MySQL 与 Kafka 没有跨系统事务，因此采用 Outbox 消除“业务提交成功但事件未记录”窗口；Kafka 已确认但 Outbox 状态未提交仍可能重复发送，由消费幂等处理。
- 未宣称 exactly-once；Producer 幂等只能减少 Kafka 层重试重复，业务正确性仍依赖 ConsumedEvent。
- M3 故障回归发现旧 Publisher 在数据库事务内等待 Kafka 会让 Kafka 停机时长期持有 Outbox 行锁，并间接阻塞 Git push 的事件写入；改为短租约领取、事务外发送和条件回写后，真实停机 push 与恢复回放均通过。

### 验证证据

- `backend/codetrove-bootstrap/src/main/resources/db/migration/V6__create_outbox_and_checks.sql`
- `backend/codetrove-eventing/src/main/java/com/codetrove/eventing/OutboxService.java`
- `backend/codetrove-eventing/src/main/java/com/codetrove/eventing/OutboxPublisher.java`
- `backend/codetrove-eventing/src/main/java/com/codetrove/eventing/KafkaHealthIndicator.java`
- `deploy/compose.yml`
- `scripts/verify-m2-event-check.ps1`
- `docs/19-m2-event-check-verification-record.md`
- 自动化验证成功发布转 PUBLISHED、失败退避并达到上限转 FAILED；实机停止 Kafka 后 push 成功且 Outbox PENDING，恢复后最终 PUBLISHED。

### 可用于简历的表述

> 使用 Flyway V6 与 Spring JDBC 实现 Transactional Outbox，将 MR 生命周期状态与事件记录纳入同一 MySQL 事务；Publisher 通过 `FOR UPDATE SKIP LOCKED` 短租约领取、事务外 Kafka 发送和短事务条件回写避免故障时持锁，并配置 `acks=all`、有限指数退避与 FAILED 终态；真实 Kafka 停机/恢复演练验证业务提交、事件积压和最终发布。

### 当前边界

- 当前 Kafka 为单 Broker KRaft 开发拓扑，尚未做多 Broker 副本、高可用或吞吐压测。
- 尚未提供 FAILED Outbox 的人工重放 API/管理界面。

---

## 功能 M2-F02：Kafka 消费业务幂等与有限重试 DLQ

### 新增能力

- Kafka 至少一次投递下，相同事件不会重复创建 Suite/Run 或重复更新状态。
- 不合法事件业务事务整体回滚，不留下“已消费但未完成”的记录。
- 不可恢复/超限消息经有限重试进入统一 DLQ。
- MR、Curator、Assay Topic 使用稳定 MR Key，保留分区内顺序基础。

### 技术栈

- Spring Kafka `@KafkaListener`
- Spring Transaction
- MySQL 唯一约束
- `DefaultErrorHandler`
- `DeadLetterPublishingRecoverer`
- Kafka DLQ

### 实现细节

- `codetrove_consumed_event` 以 `(consumer_name,event_id)` 唯一；事件处理先插入消费记录，再在同一事务更新 Check 投影。
- 重复事件命中唯一键后直接返回，Kafka offset 可正常提交。
- `CheckEventConsumer` 只承担传输适配，事务与字段校验集中于 `CheckEventProcessor`，便于无 Broker 集成测试。
- 结果事件必须匹配 event type、check type、run ID、MR ID、current head；未知 Schema 大版本或字段非法抛错并回滚消费标记。
- Consumer 最多重试两次，随后将原 key/value 和异常 headers 投递到 `codetrove.dlq.v1`。

### 难点与取舍

- 仅提交 Kafka offset 不能保证幂等：进程可能在业务提交后、offset 提交前崩溃，因此使用数据库业务幂等记录。
- DLQ 保留原 payload 便于重放，但事件本身必须先遵守不含 Token/密码/Cookie 的安全契约；不把 DLQ 当敏感数据存储。
- 当前只有 DLQ 投递与可追踪证据，尚未实现 dry-run 和人工重放工具。

### 验证证据

- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventProcessor.java`
- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventConsumer.java`
- `backend/codetrove-eventing/src/main/java/com/codetrove/eventing/KafkaEventingConfiguration.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/check/CheckGateApiTests.java`
- 相同事件处理两次，ConsumedEvent/Suite/Run 数量不增加；非法结果事件后消费记录计数不变。
- 实机发送 `schema_version=99` 坏消息，有限重试后从 DLQ 读取并确认原 event ID。

### 可用于简历的表述

> 在 Kafka 至少一次投递语义下，以 `(consumer_name,event_id)` 数据库唯一键实现业务幂等，并将消费记录与 Check 投影更新放入同一事务；对未知 Schema/非法事件执行有限重试后投递 DLQ，实机验证重复 Assay 结果只生效一次、坏消息可在 DLQ 按 event ID 追踪。

### 当前边界

- 未实现 Schema Registry 或 JSON Schema CI 兼容检查。
- 未实现 DLQ 管理 API、权限审计和选择性重放。

---

## 功能 M2-F03：按 MR Head 版本化的 Check Suite 与 Merge 门禁

### 新增能力

- 每个 MR/head 对应唯一 Check Suite，并包含 blocking/non-blocking Check Run。
- 新 push 后旧 Suite 转历史，未完成旧 Run 取消，旧结果不能参与当前门禁。
- 提供 Check 查询 API，返回当前 Suite 与可选历史 Suite。
- Merge 在仓库写锁临界区校验当前 head 的所有 blocking Run 必须 SUCCESS。

### 技术栈

- Spring MVC / Spring Security
- Spring JDBC / MySQL
- Check Suite/Run 状态机
- JGit Merge 临界区
- Kafka 事件投影

### 实现细节

- `(mr_id,head_commit)` Suite 唯一，`(suite_id,name,attempt)` Run 唯一。
- M2 阶段默认创建 `curator.review=SKIPPED/NOT_IMPLEMENTED` 与 blocking `assay.integration=PENDING` 作为当时的执行器占位；M3/M4 后续分别接入真实确定性静态评审和受控声明式 HTTP 执行。
- 新 head 事件把旧 Suite `is_current=false`，旧 PENDING/RUNNING Run 标记 `CANCELLED/STALE_HEAD`。
- 结果事件只有在 run ID、类型、MR、head 与 current Suite 全部匹配时才更新；历史结果静默不覆盖当前状态。
- Merge 在创建 Git 对象前 fail-closed：Suite 缺失、无 blocking Run，或 blocking Run 为 PENDING/RUNNING/FAILED/SKIPPED/CANCELLED 均返回 `MR_CHECKS_NOT_PASSED`。
- 非阻塞 Curator Run 即使 FAILED 也不阻塞；当前 Assay blocking Run SUCCESS 后才放行。

### 难点与取舍

- Check 是事件投影，与 MR 强一致状态不同；通过 head 绑定和 `is_current` 避免旧结果误放行，而不是假设 Kafka 全局有序。
- M2 只实现状态与门禁，不伪造 M3/M4 执行器；实机验收由受控验证事件模拟未来 Assay 合法结果。
- Merge 门禁放在仓库写锁内重新读取 MR/head 后执行，避免通过检查后源分支又变化的 TOCTOU。

### 验证证据

- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckRepository.java`
- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckGateService.java`
- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckController.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestMergeService.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/check/CheckGateApiTests.java`
- M2 专项 7 项全绿；最终后端 `clean verify` 共 64 项全绿、Checkstyle 0 违规。
- 真实 10 阶段验收通过：旧 Suite/Run 失效、门禁先拒绝后放行、重复结果幂等、Kafka/DLQ/零残留与服务恢复全部验证。

### 可用于简历的表述

> 设计按 `mr_id + head_commit` 版本化的 Check Suite/Run 状态机，新 push 自动将旧 Suite 转历史并取消未完成 Run；Merge 在仓库写锁内 fail-closed 校验当前 head 的阻塞 Check，通过 64 项测试与 Kafka 真实故障演练验证旧结果失效、非阻塞失败不拦截及阻塞结果门禁。

### 当前边界

- CodeCurator 已在 M3 接入为 non-blocking Run；CodeAssay 已在 M4 接入为 blocking Run。外部 LLM、任意 MR 构建部署和 Docker 沙箱仍未实现。
- Check rerun API、规则配置和 Review/Approval 尚未实现。

---

# 11. M3 CodeCurator 确定性静态评审基线

## 功能 M3-F01：受控 Diff 输入与逻辑/安全 Review Skill

### 新增能力

- 实现逻辑与安全两个确定性 Review Skill，只分析 MR 当前 base/head Diff 的新增行。
- 安全规则覆盖疑似硬编码凭据和 SQL 字符串拼接；逻辑规则覆盖空 `if` 条件体和单行空 `catch`。
- 二进制、超限或整体截断 Diff 不进入 Skill，避免不完整输入产生误导结论。
- 疑似 Credential 的原始值不会进入 Finding evidence、系统评论、API 或日志。

### 技术栈

- Eclipse JGit `DiffFormatter` / `FileHeader` / `EditList`
- Java 正则与 unified diff 解析
- Spring 模块公共门面
- Flyway V7

### 实现细节

- `UnifiedDiffAddedLineParser` 按 hunk 解析 `+` 行并还原真实 NEW 行号，规则不扫描未变更历史代码。
- Curator 通过 `MergeRequestReviewAccessService` 和 `RepositoryAccessService.requireSystem` 获取可信输入，不直接查询 MR 内部表或拼接仓库路径。
- Repository 仍重新计算受控路径、核对 `storage_path`/真实根目录并拒绝符号链接逃逸。
- 当前规则为 `SEC001_HARDCODED_CREDENTIAL`、`SEC002_SQL_CONCATENATION`、`LOGIC001_DANGLING_IF`、`LOGIC002_EMPTY_CATCH`。

### 难点与取舍

- 本阶段优先建立可复现、可定位、可测试的静态评审基线，没有用规则扫描器冒充 LLM/AI。
- 只分析新增行降低历史噪音；代价是跨文件语义和未修改上下文问题仍需后续外部 LLM/更强静态分析能力。

### 验证证据

- `backend/codetrove-curator/src/main/java/com/codetrove/curator/UnifiedDiffAddedLineParser.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/SecurityReviewSkill.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/LogicReviewSkill.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestReviewAccessService.java`
- 自动化验证真实 NEW 行号和 Credential evidence 脱敏；Windows 实机脚本通过真实 Git push 产生固定逻辑/安全 Finding。

### 可用于简历的表述

> 为 CodeCurator 实现确定性静态评审输入层：基于 JGit 固定 MR base/head，解析 unified diff 新增行与真实 NEW 行号，编写逻辑/安全 Review Skill 识别空条件体、空 catch、疑似硬编码凭据和 SQL 拼接，并对二进制/截断 Diff 与 Credential 原值执行 fail-safe 分流和脱敏。

### 当前边界

- 外部 LLM、RAG、Memory、PR Compression、模型路由均未实现。
- 当前规则是启发式基线，不等同于完整 SAST 产品或语义 AI 评审。

---

## 功能 M3-F02：真实并行编排、Judge 校验与幂等行级评论

### 新增能力

- 两个 Skill 在有界线程池中真实并行执行，而非顺序循环。
- Judge 校验 skill/severity/rule/path/line，使用 SHA-256 fingerprint 聚合重复 Finding。
- 持久化 ReviewTask/ReviewFinding，并为合法 Finding 写 SYSTEM `AI_REVIEW` 行级评论。
- 写评论前再次由 JGit 验证 current head/path/NEW/line，拒绝伪造或过期定位。

### 技术栈

- Java `CompletableFuture` / `ExecutorService`
- SHA-256 fingerprint
- Spring JDBC / Spring Transaction
- MySQL / Flyway V7
- JGit `EditList`

### 实现细节

- `(merge_request_id,head_commit)` 唯一约束任务，`(review_task_id,fingerprint)` 唯一约束 Finding。
- fingerprint 基于 rule/path/line/规范化 message 计算，同一候选跨 Skill 重复时聚合为一条。
- 系统评论 fingerprint 使用 `head_commit:finding_fingerprint`；同一 head 重放不重复，新 head 仍存在的问题可以重新报告。
- ReviewTask、ReviewFinding、系统评论和结果 Outbox 在同一事务提交，任一步失败整体回滚。

### 难点与取舍

- 并行不是只看代码结构：专项栅栏测试要求两个 Skill 在 500ms 内同时进入执行区，串行实现会确定失败。
- Finding 的首次 Judge 校验与写评论前 JGit 二次校验共同防止 TOCTOU 和模型/规则伪造定位。

### 验证证据

- `backend/codetrove-curator/src/main/java/com/codetrove/curator/CuratorReviewOrchestrator.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/ReviewJudge.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/CuratorRepository.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestCommentRepository.java`
- 核心测试覆盖并行栅栏、Judge 去重和伪造位置拒绝；跨模块测试产生 2 个 Finding 与 2 个系统行级评论。

### 可用于简历的表述

> 使用 `CompletableFuture` 与有界线程池并行编排逻辑/安全 Skill，设计 Judge 校验输出类型与真实 Diff 定位，并以 SHA-256 fingerprint 聚合去重；通过 Flyway V7 持久化 ReviewTask/Finding，在同一事务写幂等 SYSTEM 行级评论和结果 Outbox，评论落库前再由 JGit EditList 复核定位。

### 当前边界

- Finding disposition 更新 API 与误报反馈闭环尚未实现，当前 disposition 默认为 OPEN。
- 当前只有 LOGIC/SECURITY 两个 Skill，未实现 style/performance Skill。

---

## 功能 M3-F03：Curator command/result 链路、当前 head 隔离与 Resilience4j 降级

### 新增能力

- Check 在创建当前 head Run 的同一事务写 `curator.review-requested`，Curator 独立消费 command，避免与 Check 竞争原始 MR 事件。
- Curator 通过 started/completed/skipped 结果 Outbox 驱动非阻塞 Check Run。
- 旧 head command 标记 `CANCELLED/MR_STALE`，API 只返回当前 head Task/Findings。
- 每个 Skill 具备独立 Retry、CircuitBreaker、SemaphoreBulkhead 和 timeout；不可用时明确 `SKIPPED/SKILL_UNAVAILABLE`。

### 技术栈

- Spring Kafka / Transactional Outbox
- Resilience4j 2.2.0
- `CompletableFuture.orTimeout`
- MySQL 消费幂等唯一键
- Spring MVC / Spring Security

### 实现细节

- `codetrove.curator.commands.v1` 与 `codetrove.curator.results.v1` 分离命令和结果；稳定 MR Key 保留分区内顺序基础。
- `(consumer_name,event_id)` 消费记录与 Task/Finding/评论/结果 Outbox 处于同一数据库事务。
- 短暂异常有限重试，参数/输出校验异常不重试；任一 Skill 超时、熔断或隔离拒绝时不写 SUCCESS。
- `GET .../review-findings` 复用仓库 READ 权限与私有 404 隔离，支持 skill/severity/disposition 筛选。

### 难点与取舍

- Check 先创建 Run 再发 command，使 Curator 开始时始终有可信 `check_run_id`，避免两个消费者抢同一原始事件产生时序竞态。
- Curator Run 当前 non-blocking：静态评审不可用不会阻塞 Merge；`assay.integration` 仍以 blocking PENDING fail-closed。

### 验证证据

- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventProcessor.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/CuratorEventProcessor.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/CuratorEventConsumer.java`
- `backend/codetrove-curator/src/main/java/com/codetrove/curator/CuratorController.java`
- 处理器测试验证超时映射为 SKIPPED；集成测试验证重复 command 幂等、旧 head CANCELLED 和 API 当前 head 隔离。

### 可用于简历的表述

> 设计 Kafka command/result 双 Topic 的 Curator 链路，由 Check 在 Run 创建事务内写评审 command，Curator 以数据库唯一键保障至少一次消费幂等，并按 head 隔离历史任务；为每个 Skill 接入 Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead 与独立 timeout，异常时降级为 `SKIPPED/SKILL_UNAVAILABLE` 而不伪装成功。

### 当前边界

- 尚未接入真实 LLM Provider，因此没有真实 LLM 限流、Provider 错误分类和 Prompt 日志脱敏证据。
- Curator 为非阻塞 Check；仓库级规则配置与强制人工 Review 尚未实现。

---

## 功能 M3-F04：M3 双层验收与 Outbox 短租约故障修复

### 新增能力

- 建立 Curator 核心测试、Spring 跨模块真实 Git 集成测试与 Windows 最终 JAR 八阶段验收。
- 在 M3 扩展事件量后发现并修复 Kafka 停机时 Outbox 行锁间接阻塞 Git push 的真实回归。
- Publisher 改为短事务领取租约、事务外 Kafka send、短事务条件回写，保持至少一次投递与崩溃可恢复。
- M2 十阶段故障回归和 M3 八阶段真实链路均在修复后通过。

### 技术栈

- JUnit 5 / Spring Boot Test / H2 MySQL Mode
- PowerShell 5.1 / Git for Windows / MySQL CLI
- Windows MySQL 8.0.43
- Docker Redis 7.4.2 / Kafka 3.9.1 KRaft
- Maven Surefire / Checkstyle

### 实现细节

- 最终 `clean verify`：bootstrap 67 + curator 7，共 74 项，0 失败/错误/跳过；全模块 Checkstyle 0 违规，H2 从空库执行 V1～V7。
- M3 实机脚本真实 push 缺陷、等待 Kafka command/result、核对 Task/Finding/评论/API/Check、重放同一 command、push 干净新 head，并做零残留清理与后端恢复。
- Outbox 领取时递增 attempts 并把 `available_at` 推迟到租约截止时间；回写带 `(id,status,attempts)` 条件，避免过期发送者覆盖新状态。
- 最终 JAR 在加入每 Skill 独立 timeout 和并行栅栏测试后再次完成八阶段实机验收。

### 难点与取舍

- 旧 Publisher 的问题只有在真实 Kafka 停机、事件量增加和 Git push 同时出现时才暴露，单纯 happy-path 测试无法发现。
- 修复不改成 exactly-once；仍接受崩溃窗口导致重复发送，通过租约重领与消费幂等保证业务正确性。

### 验证证据

- `backend/codetrove-eventing/src/main/java/com/codetrove/eventing/OutboxPublisher.java`
- `backend/codetrove-curator/src/test/java/com/codetrove/curator/CuratorReviewEngineTests.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/curator/CuratorIntegrationTests.java`
- `scripts/verify-m2-event-check.ps1`
- `scripts/verify-m3-curator.ps1`
- `docs/20-m3-curator-verification-record.md`

### 可用于简历的表述

> 建立 CodeCurator 自动化与 Windows 真实环境双层验收，最终 74 项测试和全模块 Checkstyle 全绿，八阶段脚本覆盖真实 Git push、Kafka command/result、Finding/评论、重复消息、新 head 隔离与零残留；故障演练定位 Outbox 在 Kafka 停机时长事务持锁问题，并通过短租约领取、事务外发送和条件回写修复，完整回归 M2/M3 链路。

### 当前边界

- 实机脚本覆盖正常规则链路、重复消息和 head 隔离；强制 Skill 超时主要由处理器级自动化测试覆盖。
- Kafka 仍为单 Broker 开发拓扑，未做集群高可用或吞吐压测。

---

# 12. M4 CodeAssay 声明式 HTTP 测试基线

## 功能 M4-F01：当前 Head 用例发现、严格 Schema 与安全边界

### 新增能力

- 从 MR 当前 `head_commit` 的 `testcases/**/*.json` 发现声明式用例，不读取工作区或旧 head。
- 使用 JSON Schema Draft 2020-12 校验 v1 用例，顶层和各子对象未知字段直接拒绝。
- 对文件数量、单文件、总字节、timeout、target、path 和 Header 建立 fail-closed 限制。
- 只允许服务端配置的 `application` base URL 或本次执行的进程内 `mock`，用例不能指定任意 host。

### 技术栈

- JSON Schema Validator 1.5.9
- Jackson
- Eclipse JGit ObjectLoader
- JSON Schema Draft 2020-12
- Spring Configuration Properties

### 实现细节

- 单次最多 50 个文件，单文件最多 256 KiB，总计最多 2 MiB；读取 Git blob 前先检查 ObjectLoader 元数据大小。
- 顶层只支持 `schema_version/case_key/description/enabled/timeout_ms/data_pre/request/mocks/assertions`。
- Header 白名单只有 `Accept`、`Content-Type`、`X-CodeTrove-Test-Case`，拒绝 Authorization、Cookie、Host。
- path 必须以 `/` 开头，拒绝 scheme/host、`//`、反斜杠、NUL、`.`/`..` 路径段。
- 重复 `case_key` 被拒绝，避免结果表唯一键或报告语义冲突。

### 难点与取舍

- M4 明确定位为“受控 HTTP 执行器”，没有为了功能名称去构建或执行任意 MR 代码。
- 用严格 Schema 和白名单缩小执行面，代价是 tags、fission、cleanup、DB/Bean Mock 等设计字段暂时会被拒绝。

### 验证证据

- `backend/codetrove-assay/src/main/resources/assay-case-v1.schema.json`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayCaseParser.java`
- `backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestReviewAccessService.java`
- 核心测试验证未知字段、绝对 URL、危险 Header 与重复 case_key 拒绝。

### 可用于简历的表述

> 为 CodeAssay 设计基于 JSON Schema Draft 2020-12 的严格声明式用例协议，从 MR 当前 head 的 `testcases/**/*.json` 读取事实源，并通过文件体积、target/path/header 白名单和重复 case_key 校验在执行前 fail-closed，避免任意 URL、凭据 Header 与超大 Git 对象进入 HTTP 执行链路。

### 当前边界

- 当前没有独立 TestCase 数据库目录或上传/批准 API；用例通过 Git/MR 流程管理。
- 不支持任意 host、任意 MR 构建部署或通用代码执行。

---

## 功能 M4-F02：受限表达式、顺序 data_pre、WireMock 与结构化响应断言

### 新增能力

- 支持 `${context...}`、`${#uuid()}`、`${#randomLong(min,max)}` 受限表达式。
- `data_pre` 串行执行 HTTP 准备步骤，通过受限 JSON 路径保存上下文。
- 启动绑定 `127.0.0.1` 动态端口的进程内 WireMock，为主请求提供受控 Stub。
- 执行 7 类响应断言并输出结构化差异。

### 技术栈

- Java 17 HttpClient
- WireMock Jetty 12 3.13.2
- Jackson JsonNode
- 受限表达式解释器
- JUnit 5

### 实现细节

- `data_pre` 只允许 `$.status` 与 `$.body.<field...>` 提取，后续步骤只能访问已完成步骤写入的 context。
- WireMock 支持 method/path、固定 JSON 响应和 `expect_calls.min/max`，只监听回环地址。
- 断言支持 `equals/not_equals/exists/not_exists/contains/number_between/array_size`。
- AssertionDiff 保存 `path/operator/expected/actual/message`；敏感路径将 expected/actual 固定为 `[REDACTED]`。
- HTTP 响应使用 InputStream 有界读取 1 MiB+1，而不是先用 `ofByteArray` 完整载入后再判断。
- 报告正文限制 10000 字符并转义 Markdown/HTML 关键字符。

### 难点与取舍

- 不使用 SpEL/Groovy/JavaScript，避免表达式获得反射、文件、进程或用户类加载能力；用自定义小语言换取可预测安全边界。
- 初次使用通用 WireMock artifact 时缺少匹配的 HttpServerFactory；改为 `wiremock-jetty12` 并统一 JSON Schema Validator 版本后稳定通过。

### 验证证据

- `backend/codetrove-assay/src/main/java/com/codetrove/assay/ExpressionRenderer.java`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayHttpExecutor.java`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayCaseResult.java`
- `backend/codetrove-assay/src/test/java/com/codetrove/assay/AssayEngineTests.java`
- 核心成功测试串联 Schema、data_pre、context、WireMock、主请求和断言；失败测试验证精确差异。

### 可用于简历的表述

> 实现安全受限的声明式 HTTP 执行引擎：自定义解释 `${context...}` 与随机函数，串行执行 `data_pre` 并提取上下文；使用 WireMock Jetty 12 在回环动态端口构造 Stub，支持 7 类响应断言和结构化差异，并通过 1 MiB 有界流读取与敏感路径遮蔽控制内存和报告泄露风险。

### 当前边界

- 当前自动化与 Windows 实机验收使用进程内 WireMock，尚未验证任意外部 application target 的部署集成。
- 不支持脚本表达式、DB 断言、Spring Bean Mock、复杂 JSONPath、fission 或 cleanup。

---

## 功能 M4-F03：Assay Execution 状态机、Kafka Command/Result 与 Blocking Check

### 新增能力

- Check 创建 blocking Assay Run 时在同一事务写 `assay.execution-requested`。
- 持久化 Execution/CaseResult，并通过 started/completed 结果事件推进当前 head Check。
- 使用数据库消费幂等、Execution 唯一约束与幂等 SYSTEM TEST_REPORT 防止重放副作用。
- 使用 `run_token + lease_until` 解决执行中崩溃后的重新领取与旧执行者覆盖问题。

### 技术栈

- Spring Kafka / Transactional Outbox
- Spring JDBC / TransactionTemplate
- MySQL 8 / Flyway V8
- UUID run token / lease 状态机
- Check Suite/Run Merge 门禁

### 实现细节

- `(merge_request_id,head_commit)` 与 `check_run_id` 唯一；`(execution_id,case_key)` 唯一。
- prepare/start 使用短事务，HTTP/WireMock 在事务外，finish 短事务原子写 CaseResult、Execution 终态、TEST_REPORT 与 completed Outbox。
- 重复 command 可在租约条件下重新 claim RUNNING Execution；终态更新必须匹配 run token，旧执行者不能覆盖新结果。
- 旧 head command 映射为 `CANCELLED/MR_STALE`，不产生 CaseResult 或报告；API 只返回 MR 当前 head。
- 全部启用用例通过才为 `SUCCESS/ALL_CASES_PASSED`；断言失败为 `FAILED/ASSERTION_MISMATCH`；无启用用例为 blocking `SKIPPED/NO_ENABLED_CASES`。

### 难点与取舍

- 如果 command 已消费后进程在 HTTP 执行中崩溃，单纯状态置 RUNNING 会永久卡住；租约和 token 允许安全重领，又避免两个执行者竞争写终态。
- 平台的消费记录、Execution 和评论可以幂等，但外部 HTTP 副作用仍是至少一次语义，不能宣称 exactly-once。

### 验证证据

- `backend/codetrove-bootstrap/src/main/resources/db/migration/V8__create_assay_execution.sql`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayRepository.java`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayEventProcessor.java`
- `backend/codetrove-check/src/main/java/com/codetrove/check/CheckEventProcessor.java`
- `backend/codetrove-assay/src/main/java/com/codetrove/assay/AssayQueryService.java`
- 跨模块测试覆盖成功、断言失败、旧 head、command 重放和当前 head 报告 API。

### 可用于简历的表述

> 设计 Kafka command/result 驱动的 Assay 执行状态机：Check 在 Run 创建事务内写 command，Assay 以数据库唯一键保证至少一次消费幂等；通过 `run_token + lease_until` 支持 RUNNING 任务崩溃重领和条件终态回写，HTTP 在事务外执行，结果/幂等 TEST_REPORT/Outbox 在短事务原子提交，并将当前 head 的测试结果接入 blocking Merge 门禁。

### 当前边界

- Kafka 为单 Broker KRaft 开发拓扑，尚无集群高可用。
- 没有 rerun API、执行历史列表或后台租约扫描器；当前通过重复 command 触发重新 claim。

---

## 功能 M4-F04：82 项自动化与 Windows 八阶段真实链路验收

### 新增能力

- 建立 Assay 核心测试、Spring 跨模块真实 Git 集成测试与 Windows 最终 JAR 八阶段验收。
- 真实 Git Smart HTTP 先 push 通过用例，再 push 断言失败用例，验证两次 head 隔离和 blocking 状态变化。
- 验证 Kafka command/result、报告 API、重复 command 幂等、run token 清理、敏感数据检查、零残留清理和服务恢复。

### 技术栈

- Maven Surefire / Checkstyle
- Spring Boot Test / H2 MySQL Mode
- PowerShell 5.1 / Git for Windows / MySQL CLI
- Windows MySQL 8.0.43
- Docker Redis 7.4.2 / Kafka 3.9.1 KRaft

### 实现细节

- 最终 `clean verify`：bootstrap 70 + curator 7 + assay 5，共 82 项，0 失败/错误/跳过；全模块 Checkstyle 0 违规，H2 从空库执行 V1～V8。
- 实机脚本检查最终 JAR、Readiness、Topic、Flyway V8，并通过真实注册/登录/建库/Git push/MR 触发完整事件链路。
- passing head 验证 SUCCESS Execution/CaseResult、TEST_REPORT、blocking SUCCESS；failing head 验证结构化差异与 blocking FAILED。
- finally 按外键顺序清理 Assay/Curator/Check/Outbox/MR/仓库/用户和工作区，并恢复最终后端。

### 难点与取舍

- 构建成功不等于真实环境链路可用，因此单独验证系统 Git、Windows MySQL、Docker 中间件、Kafka 时序和最终 JAR。
- Docker Desktop 未运行时脚本前置启动失败；启动 Docker 引擎后复用同一最终 JAR完整重跑，八阶段退出码为 0。

### 验证证据

- `backend/codetrove-assay/src/test/java/com/codetrove/assay/AssayEngineTests.java`
- `backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/assay/AssayIntegrationTests.java`
- `scripts/verify-m4-assay.ps1`
- `docs/21-m4-assay-verification-record.md`
- 最终输出：`Real M4 Assay declarative HTTP verification passed`。

### 可用于简历的表述

> 为 CodeAssay 建立自动化与 Windows 真实环境双层验收：最终 82 项测试、Flyway V1～V8 和全模块 Checkstyle 全绿；八阶段脚本通过真实 Git push 验证通过/失败两次 head、Kafka command/result、Execution/CaseResult、结构化报告、重复消息幂等、blocking Check、敏感值控制、零残留与服务恢复。

### 当前边界

- 当前以功能、安全边界和可靠事件链路为主，尚未进行高并发、大规模用例或外部 application target 性能测试。
- WireMock Jetty 12 的内部 CrossOriginFilter 会输出 deprecated warning，但不影响构建或验收成功。

---

# 13. 当前可组合的简历项目描述

## 一句话版本

> CodeTrove 是一个基于 Java 17、Spring Boot 3.5 与 Vue 3 构建的智能代码协作与质量保障平台，目前已完成模块化工程基线、统一 API/Trace、双语前端工作台、JWT 认证、仓库元数据、JGit Smart HTTP、分支/文件浏览、基于真实 Git 的 MR/Diff/行级评论、push 后 head 历史同步、冲突安全的幂等 merge commit、Transactional Outbox、Kafka 幂等消费/DLQ、按 head 的 Check Merge 门禁、CodeCurator 确定性静态评审，以及 CodeAssay 受控声明式 HTTP 测试基线；外部 LLM、沙箱和流量生成能力仍为后续方向。

## 当前推荐的简历要点

1. 基于 Java 17、Spring Boot 3.5 和 Maven 构建 10 模块的模块化单体，使用 Enforcer、Checkstyle 和 GitHub Actions 配置固化构建与质量门禁。
2. 使用 Flyway V1～V8 管理 MySQL 迁移，采用低权限应用账号；通过 Docker Compose 提供 Redis 7.4 与单节点 KRaft Kafka 3.9，并用 Actuator Readiness 完成依赖故障注入与恢复验证。
3. 设计统一 REST 响应、错误码和 Trace ID 契约，基于 Servlet Filter + MDC 实现生成、透传和日志关联，并防护非法 Trace 值。
4. 基于 Spring Security 6、BCrypt 和 JJWT 实现注册、登录、15 分钟 JWT 与 `/users/me`，通过 Dummy BCrypt 降低账号枚举时序差异。
5. 使用 Spring JDBC 和 JGit 实现仓库元数据、创建者 OWNER、受控 bare 仓库与 README 初始提交，并通过事务回滚补偿协调数据库和文件系统。
6. 建立四角色权限矩阵与私有仓库 404 隔离，将可见性过滤下推至 SQL；仓库列表采用 Snowflake ID Keyset Pagination 和不透明游标。
7. 基于 JGit GitServlet 实现带 Basic Auth 的 Smart HTTP clone/fetch/push，将 READ/PUSH 权限接入 Resolver 与 ReceivePackFactory，并用 PreReceiveHook 原子拒绝直接修改 main。
8. 为 Git 协议设置请求、command、对象、pack、超时与并发限制，通过真实系统 Git CLI 验证普通分支写入、二次 fetch、保护分支和 Reporter 越权拒绝。
9. 基于 JGit RevWalk/TreeWalk/ObjectLoader 实现分支、目录树与文件浏览，严格限制 ref/path 语法，并以 1 MiB 阈值区分内联 UTF-8 文本、二进制和超大文件。
10. 基于 Spring JDBC 和 JGit 实现 MR 创建/列表/详情/更新/关闭，服务端固化 base/head commit，通过仓库行锁分配 iid、约束重复 OPEN MR，并以 version 乐观锁处理并发修改。
11. 基于 JGit DiffFormatter/EditList 实现受文件数与 patch 体积限制的固定快照 Diff，并校验行级评论的 commit、路径、OLD/NEW side 和真实变更行。
12. 基于 JGit ReceivePack Hook 同步 OPEN MR head/version 与提交历史；在仓库级 JVM 写锁内用 ResolveMerger 检测三方冲突、生成两父 merge commit，并通过目标引用 CAS 防止竞态覆盖。
13. 使用仓库级 Idempotency-Key、SHA-256 请求哈希与 PENDING/SUCCEEDED 操作记录实现 Merge 重放和 Git 成功/数据库未完成窗口恢复；明确当前锁为单 JVM Semaphore Lease，不冒充分布式锁。
14. 使用 Transactional Outbox 将 MR 状态与事件写入同一事务；Publisher 以 `SKIP LOCKED` 短租约领取、事务外 Kafka 发送和短事务条件回写避免故障长持锁，并保留至少一次语义。
15. 以 `(consumer_name,event_id)` 唯一键实现 Kafka 业务幂等，将消费记录与 Check/Curator 投影置于同一事务；未知 Schema/非法消息有限重试后进入 DLQ。
16. 设计按 MR/head 版本化的 Check Suite/Run 状态机，新 push 自动使旧 Suite/Run 失效；Merge 在仓库写锁内 fail-closed 校验当前 head 的阻塞 Check，非阻塞失败仅告警。
17. 实现 CodeCurator 确定性静态评审基线：解析 unified diff 新增行，使用逻辑/安全 Skill 识别 4 类问题，Judge 复核定位并以 SHA-256 fingerprint 去重，持久化 ReviewTask/Finding 和幂等系统行级评论。
18. 使用 `CompletableFuture` 有界线程池真实并行 Skill，并为每个 Skill 接入 Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead 与独立 timeout；不可用时降级为 `SKIPPED/SKILL_UNAVAILABLE`。
19. 实现 CodeAssay 受控声明式 HTTP 测试基线：从当前 head 读取 `testcases/**/*.json`，使用 Draft 2020-12 严格 Schema、受限表达式、顺序 `data_pre`、WireMock Jetty 12、7 类响应断言和结构化差异，并通过 Execution/CaseResult 与当前 head API 提供报告。
20. 设计 Assay Kafka command/result 与 run-token 租约状态机，HTTP 在事务外执行，结果/幂等 TEST_REPORT/Outbox 在短事务原子提交；旧 head 取消，只有全部启用用例通过才满足 blocking Merge 门禁。
21. 使用 Vue 3、TypeScript、Pinia、Vue Router 和 vue-i18n 实现代码协作工作台与中英文切换，完成格式、类型、生产构建和浏览器渲染验证。
22. 建立自动化与 Windows 真实环境双层验收：后端 82 项测试全通过、Flyway V1～V8、全模块 Checkstyle 0 违规；M4 八阶段覆盖真实 Git push 的通过/失败两次 head、Kafka、结构化报告、幂等、门禁、脱敏、零残留与服务恢复。
23. 将项目首次公开发布到 `Tmiemie/CodeTrove`：以 Git 暂存区审计真实上传文件，排除 Secret、运行数据、IDE/构建产物与大文件，修复 `.gitignore` 误伤前端 `src/data` 的问题，添加 MIT 与跨平台换行/执行位规则；验证远端 218 个文件完整、本地/远端 SHA 一致，并完成首轮 GitHub Actions 成功运行。

> CI 边界：目前只有首次公开 `main` 的一次成功运行证据，可以写“首轮 GitHub Actions 通过”，不能写“远端 CI 长期稳定运行”。

## 当前不能写入简历的表述

- “已实现成员管理 API”——当前只有成员表、创建者 OWNER 和角色矩阵，成员增删改端点尚未实现。
- “已实现 Review/Approval”——当前已实现基础 Merge 与阻塞型 Check 门禁，但审批流程尚未实现。
- “使用 Redisson/分布式锁控制并发合并”——当前是单节点 JVM 公平 Semaphore Lease，不支持多实例仓库写入协调。
- “Kafka exactly-once / Kafka 高可用集群”——当前是至少一次投递、数据库幂等消费与单节点 KRaft 开发拓扑。
- “已接入 LLM/已完成 AI 代码评审”——当前实现的是 CodeCurator 确定性静态评审编排基线；外部 LLM、RAG、Memory、PR Compression 与模型路由尚未接入。
- “已实现任意项目声明式集成测试平台 / 任意 MR 自动构建部署”——当前是服务端配置 target 或进程内 WireMock 的受控 HTTP 执行器。
- “已完成通用代码沙箱/Docker 隔离、DB 断言、Spring Bean Mock、fission、cleanup”——这些均属于 M5 后续增强。
- “已完成流量录制或 AI 自动生成测试用例”——当前用例由当前 Git head 提供，录制与 AI 生成尚未实现。
- “远端 CI 已长期稳定运行”——当前只验证了首次公开 `main` 的一轮 GitHub Actions 成功。
- “已实现 Refresh Token、Token 撤销和登录限流”——M1.1 未包含这些能力。

---

# 14. 后续功能记录模板

```markdown
## 功能 <里程碑-编号>：<功能名称>

### 新增能力
- <新增能力>

### 技术栈
- <技术栈>

### 实现细节
- <实现细节>

### 难点与取舍
- <难点与取舍>

### 验证证据
- 代码路径：<路径>
- 测试：<测试结果>
- 真实环境：<验收结果>
- 指标：<可复现指标>

### 可用于简历的表述
> <基于真实证据的简历表述>

### 当前边界
- <未实现边界>
```
