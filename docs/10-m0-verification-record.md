# M0 验收记录

## 当前状态

M0 本机工程基线已经通过完整验收。GitHub Actions 配置已完成并与本机命令对齐；远端 CI 只有在首次推送 GitHub 后才能产生运行证据。公开发布前的最终安全扫描和许可证选择仍属于发布门槛。

## 已验证项目

### 1. Java 与 Maven 构建基线

- 环境：Java 17、Maven Wrapper 3.9.11、Spring Boot 3.5.7。
- 命令：`backend\mvnw.cmd clean verify`。
- 结果：10 个业务/启动模块全部构建成功；Java 与 Maven Enforcer 规则通过。
- 结论：通过。

### 2. 模块边界

- 范围：`common`、`auth`、`repository`、`merge-request`、`check`、`eventing`、`curator`、`assay`、`search`、`bootstrap`。
- 验证：父 POM Reactor 构建全部成功；业务模块仅依赖 `common`，启动模块负责装配。
- 结论：通过当前骨架边界验证。

### 3. Windows 本机 MySQL

- MySQL：8.0.43。
- 字符集：`utf8mb4`。
- 排序规则：`utf8mb4_0900_ai_ci`。
- 时区：UTC+8。
- 项目数据库：`codetrove`。
- 应用账号：`codetrove_app@localhost`，权限限于 `codetrove.*`。
- 隔离验证：项目库建表与 DML 成功；读取 `mysql.user` 被拒绝。
- 结论：通过。

### 4. Flyway 迁移

- 迁移：`V1__create_platform_metadata.sql`。
- 首次启动：成功创建 `flyway_schema_history` 和 `codetrove_platform_metadata`。
- 基线数据：`schema_baseline=M0`。
- 重启验证：Flyway 报告 Schema 已是版本 1，无重复迁移。
- 测试环境：H2 MySQL 模式执行相同迁移成功。
- 结论：通过。

### 5. Spring Boot 健康检查

- Liveness：`GET /actuator/health/liveness`，正常状态 HTTP 200 / `UP`。
- Readiness：`GET /actuator/health/readiness`，包含应用状态、MySQL、Redis 和 ping。
- 正常状态：MySQL 与 Redis 可用时 HTTP 200 / `UP`。
- 故障演练：停止 Redis 后 readiness 返回 HTTP 503 / `DOWN`；恢复 Redis 并等待容器 healthy 后 readiness 回到 HTTP 200 / `UP`。
- 结论：通过。

### 6. Redis Docker Compose

- 镜像：`redis:7.4.2-alpine`。
- Compose：`deploy/compose.yml`，不包含 MySQL。
- 网络绑定：仅 `127.0.0.1:6379`。
- 鉴权：随机强密码通过 Windows 用户环境变量 `CODETROVE_REDIS_PASSWORD` 注入；无密码 `PING` 返回 `NOAUTH`，正确密码返回 `PONG`。
- 持久化：命名卷 `codetrove-redis-data`，开启 AOF。
- 容器状态：`codetrove-redis` 为 `healthy`。
- 结论：通过。

### 7. 统一 API 响应与 Trace ID

- 成功端点：`GET /api/v1/platform/status`。
- 成功响应：HTTP 200，结构为 `data + meta.traceId`。
- Trace 透传：请求 `X-Trace-Id: manual-check-001`，响应头与响应体均保持相同值。
- 未提供 Trace：服务端生成 32 位十六进制 Trace ID。
- 非法 Trace：测试确认包含空格或 CRLF 的值会被替换。
- 未知路径：HTTP 404，错误结构为 `error.code/message/details/traceId`。
- 错误保护：响应不包含堆栈、SQL、绝对路径和密钥。
- 自动化测试：`ApiContractTests` 4 项全部通过。
- 结论：通过。

### 8. 前端构建

- 技术栈：Vue 3、TypeScript、Vite。
- 命令：`frontend\npm run build`。
- 结果：TypeScript 与生产构建成功。
- 浏览器验证：首页可渲染，控制台无运行时错误。
- 视觉规范：`docs/09-frontend-design.md`。
- 结论：通过。


### 9. CI、README 与本机脚本

- CI：`.github/workflows/ci.yml`，后端使用 Java 17，前端使用 Node 22。
- 供应链约束：`checkout`、`setup-java`、`setup-node` 均固定到不可变 commit SHA。
- 后端 CI：Maven `verify`，包含 5 项测试和 Checkstyle；当前 0 个 Checkstyle 违规。
- 前端 CI：`npm ci`、Prettier、Vue TypeScript 检查和生产构建。
- 本机启动：`scripts/start-local.ps1`，只复用或启动 CodeTrove 自身服务，端口冲突时不覆盖其他应用。
- 本机停止：`scripts/stop-local.ps1`，仅停止命令行属于 CodeTrove 的 8080/28741 进程，保留 Redis 数据卷。
- 本机验收：`scripts/verify-m0.ps1` 六个阶段全部通过。
- README：包含 Windows MySQL 开发方式和贡献者可选 Docker MySQL 方式。
- 结论：本机可复现性通过；远端 CI 运行证据待首次 GitHub push。

## 测试汇总

- 后端测试：5 项，0 失败，0 错误。
- 后端 Checkstyle：全部模块 0 违规。
- Redis Compose、鉴权、容器健康和 readiness 故障演练：通过。
- 前端生产构建：通过。
- 真实 MySQL 启动与接口验证：通过。

## 发布前待完成项目

1. GitHub 首次 push 后取得远端 Actions 运行证据。
2. 确认开源许可证并添加许可证文件。
3. 执行 GitHub 发布前 Secret、隐私、许可证、生成物和大文件扫描。
4. 由用户确认 GitHub 仓库名称、公开/私有可见性和首次 push。
