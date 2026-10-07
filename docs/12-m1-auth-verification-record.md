# CodeTrove M1.1 认证基线验证记录

## 1. 实施范围

M1.1 只实现后续仓库与 Git 权限所需的认证基线：

- 用户注册、登录和当前用户查询。
- BCrypt cost 12 密码哈希。
- 15 分钟 HMAC-SHA256 JWT Access Token。
- 无状态 Spring Security 过滤链与统一 401/403 错误响应。
- Flyway V2 用户表迁移。
- Windows 用户级 `CODETROVE_JWT_SECRET`，真实值不写入项目或验证记录。

本阶段未实现 Refresh Token、Token 撤销、登录限流、连续失败锁定、仓库成员与角色权限；这些能力不得描述为已完成。

## 2. 实现证据

- 迁移：`backend/codetrove-bootstrap/src/main/resources/db/migration/V2__create_user.sql`
- API：`backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthController.java`
- 业务服务：`backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthService.java`
- JWT：`backend/codetrove-auth/src/main/java/com/codetrove/auth/JwtTokenService.java`
- 过滤器：`backend/codetrove-auth/src/main/java/com/codetrove/auth/JwtAuthenticationFilter.java`
- 安全配置：`backend/codetrove-auth/src/main/java/com/codetrove/auth/AuthSecurityConfiguration.java`
- 集成测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/auth/AuthApiTests.java`

## 3. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

结果：

- 11 项测试全部通过，0 失败、0 错误、0 跳过。
- 6 项认证集成测试覆盖：注册、BCrypt 哈希、登录、`/users/me`、重复用户名、错误密码、不存在用户、未认证、无效 Token、过期 Token。
- 所有模块 Checkstyle 为 0 违规。
- H2 空库依次应用 Flyway V1、V2；重复启动确认 Schema 已是 V2 且无需再次迁移。
- Spring Boot 可执行 JAR 成功重新打包。

## 4. 真实环境验证

环境：Windows 本机 MySQL 8.0.43、Docker Redis 7.4.2、Java 17。

验证结果：

- Docker Desktop 启动后，Redis 健康检查通过。
- 最新后端启动后 Readiness 为 `UP`。
- 真实 MySQL 的 `flyway_schema_history` 当前版本为 2。
- `codetrove.codetrove_user` 表存在。
- 使用随机临时账号完成注册、登录、Bearer Token 访问 `/api/v1/users/me`。
- 数据库保存的是 BCrypt cost 12 哈希，不是明文密码。
- 验收完成后删除临时账号；`m1check_%` 临时账号数量为 0。
- JWT 用户环境变量存在且长度为 64；验证过程未输出真实值。

## 5. 安全边界

- 用户名规范化为小写；仅允许 URL-safe 字符。
- 密码长度限制为 12～72。
- 注册响应和用户查询不包含 `password_hash`。
- 不存在用户与错误密码统一返回 `AUTH_INVALID_CREDENTIALS`；不存在用户也执行 BCrypt 虚拟校验，降低时序枚举差异。
- JWT 只包含用户 ID、用户名、签发时间、过期时间和 token ID。
- 用户状态不是 `ACTIVE` 时，不建立认证上下文。
- Access Token 不应持久化到浏览器 Local Storage。

## 6. 过程中发现并处理的问题

- 非 Web 上下文测试无法创建 `HttpSecurity`：安全过滤链增加 Servlet Web 环境条件。
- Spring Security 自动生成未使用的默认开发密码：禁用 `UserDetailsServiceAutoConfiguration`。
- 运行中的 JAR 被 Windows 锁定导致 repackage 失败：只停止确认属于 CodeTrove 的后端进程后重跑，最终完整构建成功。
- Docker Desktop 最初未运行：取得用户确认后启动，并等待 Docker API 就绪再继续。

## 7. 结论

M1.1 认证基线通过。M1 尚未整体完成，下一步应实现仓库与成员权限模型，再接 Git Smart HTTP 和 MR 主链路。
