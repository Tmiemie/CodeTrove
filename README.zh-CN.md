[English](README.md) | **简体中文**

# CodeTrove

CodeTrove 是一个智能代码协作与质量保障平台，将仓库协作、AI 辅助评审、声明式集成测试和合并检查串联成一套完整工作流。

> 当前状态：M0、M1.1～M1.6、M2、M3 和 M4 CodeAssay 声明式 HTTP 基线均已完成。CodeTrove 现已包含认证、仓库权限、Git Smart HTTP、MR、冲突安全/幂等 Merge、Transactional Outbox、Kafka 幂等/DLQ、当前 head Check 门禁、确定性逻辑/安全评审，以及包含 Schema 校验、数据准备、WireMock、响应断言、结构化报告和 blocking Assay Check 的受控 HTTP 测试执行器。外部 LLM/RAG/Memory、任意 MR 构建部署、Docker 沙箱、DB/Bean Mock 断言、流量录制和 AI 用例生成仍属于后续工作。

## 当前能力

- Java 17 与 Spring Boot 3.5 模块化后端
- Vue 3、TypeScript 与 Vite 前端
- 完整的英文 `en-US` / 简体中文 `zh-CN` 界面及语言持久化
- Windows MySQL 集成与 Flyway 数据库迁移
- Docker 中运行的 Redis 7.4 与 Apache Kafka 3.9；Redis 启用鉴权/AOF，Kafka 使用本地单节点 KRaft
- Liveness 与 Readiness 健康检查组，Readiness 包含 MySQL、Redis 与 Kafka
- 统一 API 成功/错误响应与 `X-Trace-Id`
- 用户名密码注册、BCrypt 密码哈希与 15 分钟 JWT Access Token
- 仓库创建/列表/详情、四角色授权与 JGit bare 仓库初始化
- 支持 Basic Auth、仓库授权、`main` 保护和资源限制的 Git Smart HTTP clone/fetch/push
- 受权限控制的分支元数据、Git tree 游标分页浏览和受限 UTF-8 文件读取
- 支持仓库内 iid 与乐观锁的 Merge Request 创建/列表/详情/更新/关闭 API
- 基于快照的受限 JGit Diff、二进制/patch 大小控制，以及经过定位校验的普通/行级评论
- 源分支成功 push 后自动更新匹配 OPEN MR 的 head，并追加去重的提交历史
- 仓库级串行 `MERGE_COMMIT`，包含三方冲突检测、expected head 校验、目标引用 CAS、幂等重放和 PENDING 操作恢复
- MR 生命周期使用 Transactional Outbox，Kafka 至少一次投递、数据库消费幂等、有限重试和统一 DLQ
- 每个 head 对应 Check Suite/Run：旧 head 自动转历史，阻塞型 Check 控制 Merge，非阻塞 Check 仅告警
- CodeCurator 确定性评审基线：逻辑/安全 Review Skill、真实并行执行、Judge 定位校验/去重、ReviewTask/Finding 持久化和幂等系统行级评论
- Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead、有界线程池、每 Skill 独立超时和明确的 `SKIPPED/SKILL_UNAVAILABLE` 降级
- 只返回当前 head 的 Review Findings API，复用仓库 READ 权限并支持 skill/severity/disposition 筛选
- CodeAssay 当前 head 声明式 HTTP 基线：Draft 2020-12 严格 Schema、受限表达式与 `data_pre`、回环 WireMock、响应断言、结构化差异、Execution/CaseResult 持久化和幂等 TEST_REPORT 评论
- Kafka 驱动的 `assay.execution-requested/started/completed`、数据库消费幂等、run-token 租约恢复，以及只有全部启用用例通过才 SUCCESS 的 blocking Assay Check
- CodeAssay 明确不是通用沙箱：不构建/部署任意 MR 代码，尚无 Docker 隔离、DB 断言、Spring Bean Mock、fission、cleanup、流量录制或 AI 用例生成
- Kafka Topic：`codetrove.mr.events.v1`、`codetrove.curator.commands.v1`、`codetrove.curator.results.v1`、`codetrove.assay.commands.v1`、`codetrove.assay.results.v1` 与 `codetrove.dlq.v1`
- GitHub Actions 后端测试与前端检查
- 融合克制猫咪策展员形象的蓝紫粉仪表盘界面

## 代码仓库

- GitHub：`https://github.com/Tmiemie/CodeTrove`
- 可见性：公开
- 许可证：MIT

## 项目结构

```text
CodeTrove/
├── backend/             Maven 模块化后端
├── frontend/            Vue 3 前端
├── deploy/              Docker Compose 文件
├── docs/                架构与契约文档
├── scripts/             Windows 本地脚本
└── .github/workflows/   CI
```

## 环境要求

- Windows 10/11
- Java 17
- Docker Desktop
- Node.js 22 与 npm
- Git
- MySQL 8（采用推荐的 Windows 本机 MySQL 工作流时需要）

后端使用 Maven Wrapper，因此无需强制单独安装 Maven。

## 本地配置

从 `.env.example` 复制变量名，但不要提交真实值。

必需的 Windows 用户环境变量：

```text
CODETROVE_DB_URL
CODETROVE_DB_USERNAME
CODETROVE_DB_PASSWORD
CODETROVE_REDIS_HOST
CODETROVE_REDIS_PORT
CODETROVE_REDIS_PASSWORD
CODETROVE_KAFKA_PORT
CODETROVE_KAFKA_BOOTSTRAP_SERVERS
CODETROVE_EVENTING_ENABLED
CODETROVE_CHECK_GATE_ENABLED
CODETROVE_OUTBOX_BATCH_SIZE
CODETROVE_OUTBOX_MAX_ATTEMPTS
CODETROVE_OUTBOX_POLL_INTERVAL_MS
CODETROVE_KAFKA_SEND_TIMEOUT
CODETROVE_CHECK_CONSUMER_GROUP
CODETROVE_CURATOR_CONSUMER_GROUP
CODETROVE_CURATOR_ENABLED
CODETROVE_CURATOR_PARALLELISM
CODETROVE_CURATOR_SKILL_TIMEOUT
CODETROVE_CURATOR_MAX_ATTEMPTS
CODETROVE_ASSAY_CONSUMER_GROUP
CODETROVE_ASSAY_ENABLED
CODETROVE_ASSAY_TARGET_BASE_URL
CODETROVE_JWT_SECRET
CODETROVE_NODE_ID
CODETROVE_REPOSITORY_ROOT
CODETROVE_REPOSITORY_MAX_INLINE_BLOB_BYTES
CODETROVE_MR_MAX_DIFF_FILES
CODETROVE_MR_MAX_FILE_PATCH_BYTES
CODETROVE_MR_MAX_TOTAL_PATCH_BYTES
CODETROVE_GIT_TIMEOUT_SECONDS
CODETROVE_GIT_MAX_REQUEST_BYTES
CODETROVE_GIT_MAX_COMMAND_BYTES
CODETROVE_GIT_MAX_OBJECT_BYTES
CODETROVE_GIT_MAX_PACK_BYTES
CODETROVE_GIT_MAX_CONCURRENT_REQUESTS
```

推荐的本地数据库 URL：

```text
jdbc:mysql://127.0.0.1:3306/codetrove?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
```

请使用独立数据库和仅限 `codetrove.*` 的低权限账号。应用不得使用 MySQL root 账号。

创建或修改 Windows 用户环境变量后，请重启终端或 IDE，确保新进程继承这些变量。

## 本地启动

### 1. 启动 Redis 与 Kafka

在项目根目录打开 PowerShell：

```powershell
$env:CODETROVE_REDIS_PASSWORD = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PASSWORD', 'User')
$env:CODETROVE_REDIS_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PORT', 'User')
docker compose -f deploy/compose.yml up -d redis kafka
```

### 2. 构建后端

```powershell
cd backend
.\mvnw.cmd clean verify
cd ..
```

### 3. 启动后端

辅助脚本会读取已配置的 Windows 用户环境变量：

```powershell
.\scripts\start-local.ps1 -SkipFrontend
```

后端端点：

- `http://127.0.0.1:8080/actuator/health/liveness`
- `http://127.0.0.1:8080/actuator/health/readiness`
- `http://127.0.0.1:8080/api/v1/platform/status`

### 4. 启动前端

```powershell
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1 --port 28741
```

前端地址：

- `http://127.0.0.1:28741`

当前前端仍使用演示数据，尚未接入后端业务 API。

## Git Smart HTTP

先通过 REST API 创建仓库，再使用响应中的 Git 地址：

```powershell
git clone http://127.0.0.1:8080/git/<owner>/<repo>.git
```

Git 会通过 Basic Auth 提示输入 CodeTrove 用户名和密码。不要把真实凭据写入已提交脚本、Shell 历史或 remote URL。本地端点仅用于 HTTP 开发环境，生产部署必须增加 TLS 终止。

当前 Git 行为：

- 具备 `READ` 权限的已认证用户可以 clone/fetch；
- `OWNER`、`MAINTAINER`、`DEVELOPER` 可以 push 普通分支；
- `REPORTER` 不能 push；
- 直接创建、更新或删除 `main` 会收到 `protected branch requires merge request`；
- non-fast-forward push 被拒绝；
- 请求、command、对象、pack、超时和并发限制可通过 `CODETROVE_GIT_*` 环境变量配置。

## 供其他贡献者使用的可选 Docker MySQL

维护者工作流使用现有 Windows MySQL。未安装 MySQL 的贡献者可以使用可选的 Docker MySQL 配置文件。

在当前终端或未纳入版本控制的 `.env` 文件中设置以下附加变量：

```text
CODETROVE_MYSQL_ROOT_PASSWORD=<strong-local-root-password>
CODETROVE_DB_PASSWORD=<strong-local-app-password>
CODETROVE_MYSQL_PORT=3307
```

启动 Redis、Kafka 与 MySQL：

```powershell
docker compose -f deploy/compose.yml -f deploy/compose.with-mysql.yml up -d
```

使用以下数据库 URL：

```text
jdbc:mysql://127.0.0.1:3307/codetrove?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
```

维护者的默认 Compose 命令不会启动这个可选 MySQL 容器。

## 验证

确保 Redis、Kafka 与后端正在运行，然后执行：

```powershell
.\scripts\verify-m0.ps1
.\scripts\verify-m1-git.ps1
.\scripts\verify-m1-browse.ps1
.\scripts\verify-m1-merge-request.ps1
.\scripts\verify-m1-merge.ps1
.\scripts\verify-m2-event-check.ps1
.\scripts\verify-m3-curator.ps1
.\scripts\verify-m4-assay.ps1
```

`verify-m0.ps1` 验证：

- 所有后端模块与测试
- 前端格式、类型检查和生产构建
- Docker Compose 语法
- Redis 容器健康状态
- 后端 Liveness/Readiness
- API 响应与 Trace ID 透传

`verify-m1-git.ps1` 使用随机临时用户和私有仓库，在 Windows MySQL、Docker Redis、最终 JAR 与系统 Git 客户端上验证真实 clone、普通分支 push/fetch、`main` 保护、REPORTER push 拒绝、临时数据清理和后端恢复。

`verify-m1-browse.ps1` 会真实 push 包含嵌套源码、二进制和超大文本的 feature 分支，再验证 branches/tree/blob REST API、非法 ref/path、私有仓库隔离、临时数据清理和后端恢复。

`verify-m1-merge-request.ps1` 会通过 Git Smart HTTP 真实 push feature 分支，创建和更新 MR，验证固定快照 Diff、普通/行级评论、无效定位与陈旧版本拒绝，关闭 MR，清理全部临时记录与仓库，并恢复后端。

`verify-m1-merge.ps1` 会创建 MR，执行第二次真实 HTTP push 验证 head/提交历史同步，满足隔离的 M2 阻塞 Check 前置条件，通过 REST API 合并，校验两父提交与远端 `main`，重放幂等键、核对数据库终态、清理全部临时数据并恢复后端。

`verify-m2-event-check.ps1` 验证真实的 Outbox → Kafka → Check → Merge 链路：在真实 push 期间停止 Kafka，确认 MR head 已提交、Outbox 为 PENDING 且 Readiness DOWN；恢复后验证最终发布、旧 Suite 取消、Merge 先拒绝后放行、重复结果幂等、坏 Schema 进入 DLQ、零残留清理与服务恢复。DLQ 验证会先记录本轮开始时的 Topic 末尾 offset，再只消费本轮新增消息，因此 Kafka 卷保留历史消息也不影响重复执行。

`verify-m3-curator.ps1` 通过真实 Git Smart HTTP push 固定逻辑/安全缺陷，验证 Kafka command/result 链路、并行确定性 Skill、Judge 认可的定位、两条 Review Finding、幂等系统行级评论、疑似 Credential 原值脱敏、重复 command 幂等、干净新 head 隔离、非阻塞 Curator Check、零残留清理与后端恢复。

`verify-m4-assay.ps1` 通过真实 Git Smart HTTP 先 push 通过用例、再 push 断言失败用例，验证当前 head 用例发现、Assay command/result Topic、Execution/CaseResult、`data_pre`、回环 WireMock、结构化断言差异、幂等 TEST_REPORT、重复 command、blocking SUCCESS/FAILED 语义、run token 清理、敏感值检查、零残留清理和后端恢复。

## 本地停止

```powershell
.\scripts\stop-local.ps1
```

Redis 与 Kafka 数据卷会被保留。停止脚本不会删除数据卷。

## CI

GitHub Actions 执行：

- Java 17 后端 `clean verify`
- 前端 `npm ci`
- Prettier 格式检查
- Vue TypeScript 检查
- Vite 生产构建

第三方 Actions 均固定到不可变的 commit SHA。

## 安全说明

切勿提交：

- `.env` 文件
- 数据库、Redis、JWT 或 LLM 密钥
- 运行时数据与日志
- Git 裸仓库
- IDE 工作区文件
- `node_modules`、`dist` 或 Maven `target` 目录

首次推送到 GitHub 前，CodeTrove 需要完成专项的密钥、隐私、许可证、生成文件与大文件检查。

## 文档

建议从以下文档开始：

- `codetrove-project-design.md`
- `docs/01-scope-and-milestones.md`
- `docs/02-architecture.md`
- `docs/04-api-contract.md`
- `docs/08-acceptance-checklist.md`
- `docs/09-frontend-design.md`
- `docs/10-m0-verification-record.md`
- `docs/11-i18n.md`
- `docs/12-m1-auth-verification-record.md`
- `docs/13-resume-feature-ledger.md` — 按功能整理的实现证据与简历表述
- `docs/14-m1-repository-verification-record.md`
- `docs/15-m1-git-smart-http-verification-record.md`
- `docs/16-m1-repository-browse-verification-record.md`
- `docs/17-m1-merge-request-verification-record.md`
- `docs/18-m1-merge-verification-record.md`
- `docs/19-m2-event-check-verification-record.md`
- `docs/20-m3-curator-verification-record.md`
- `docs/21-m4-assay-verification-record.md`

每个可独立验收的功能完成后，必须随实现和验证文档同步更新简历功能台账。

## 许可证

本项目使用 [MIT License](LICENSE)。
