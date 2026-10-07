# CodeTrove M1.3 Git Smart HTTP 验证记录

## 1. 实施范围

M1.3 实现可由标准 Git 客户端访问的 Smart HTTP 基线：

- `clone` / `fetch` 通过 JGit `upload-pack`。
- 普通分支 `push` 通过 JGit `receive-pack`。
- `/git/{owner}/{repo}.git` 使用 UTF-8 Basic Auth，并复用平台用户密码认证。
- 私有仓库读取隔离与四角色 `READ/PUSH` 授权。
- 默认分支 `main` 直接 push 保护。
- HTTP 请求、Git command、单对象、pack、I/O 超时和并发请求限制。

本阶段验收时尚未实现分支/文件 REST 浏览、MR、Diff、评论、服务端合并和仓库级写锁；其中分支/文件浏览已在 M1.4 完成，MR/Diff/评论基础已在 M1.5 完成，服务端合并和仓库级写锁仍未完成，因此 M1 尚未整体放行。

## 2. 实现证据

- JGit HTTP 装配：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitSmartHttpConfiguration.java`
- Basic Auth：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitBasicAuthenticationFilter.java`
- 请求与并发限制：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRequestLimitFilter.java`
- 仓库解析：`backend/codetrove-repository/src/main/java/com/codetrove/repository/CodeTroveRepositoryResolver.java`
- Pack 工厂：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitPackFactories.java`
- 默认分支保护：`backend/codetrove-repository/src/main/java/com/codetrove/repository/ProtectedBranchHook.java`
- 复用密码认证：`backend/codetrove-common/src/main/java/com/codetrove/common/security/PasswordAuthenticationService.java`
- HTTP 专项测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitSmartHttpTests.java`
- 策略专项测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/repository/GitPolicyTests.java`
- 真实 Git CLI 测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/GitCliEndToEndTests.java`
- 真实环境验收脚本：`scripts/verify-m1-git.ps1`

## 3. 认证与授权

- REST API 保持 `Authorization: Bearer <JWT>`；JWT Filter 对 `/git/**` 明确跳过。
- Git Smart HTTP 使用 `Authorization: Basic ...`，认证逻辑与 REST 登录共用 BCrypt、账号状态检查和不存在用户 Dummy BCrypt。
- 缺失、格式错误或无效 Basic 凭据返回 401，并携带 `WWW-Authenticate: Basic realm="CodeTrove Git", charset="UTF-8"`。
- `upload-pack` 仅为可见仓库创建：私有仓库非成员统一按不存在处理，公开仓库允许已认证非成员读取。
- `receive-pack` 要求 `PUSH` 权限：OWNER、MAINTAINER、DEVELOPER 允许，REPORTER 拒绝。

## 4. 分支保护与资源限制

- 默认分支由仓库元数据决定，当前默认值为 `main`。
- 直接创建、更新或删除默认分支均被 PreReceiveHook 拒绝。
- 同一批 push 只要触及默认分支，就拒绝全部仍处于 `NOT_ATTEMPTED` 的命令，避免普通分支被部分写入。
- 错误消息固定为 `protected branch requires merge request`。
- 禁止 non-fast-forward push；普通分支允许创建和删除。
- 默认限制：
  - I/O 超时：60 秒。
  - HTTP 请求体：115343360 bytes（110 MiB）。
  - command：1048576 bytes（1 MiB）。
  - 单对象：20971520 bytes（20 MiB）。
  - pack：104857600 bytes（100 MiB）。
  - 并发 Git 请求：8。
- HTTP Content-Length 超限返回 413；并发许可耗尽返回 429 和 `Retry-After: 1`。
- chunked 请求不能只依赖 Content-Length，最终仍由 JGit 的 command/object/pack 限制兜底。

## 5. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

最终结果：

- 后端共 32 项测试全部通过，0 失败、0 错误、0 跳过。
- 所有模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 重新打包成功。
- M1.3 新增 11 项专项验证：
  - 5 项真实 HTTP 测试：Basic challenge、错误密码、私有仓库 clone 广告、私有非成员 404、超大请求 413。
  - 5 项策略测试：默认分支批量原子拒绝、普通分支放行、Owner ReceivePack 配置、Reporter 拒绝、UploadPack 超时。
  - 1 项系统 Git CLI 端到端测试。

## 6. Git CLI 与真实本机环境验收

### 6.1 自动化 Git CLI

环境：Git for Windows 2.55.0、Java 17、Spring Boot 随机真实 HTTP 端口、H2 MySQL 兼容模式和真实临时 bare 仓库。

端到端测试实际调用系统 `git.exe`，完成：

1. OWNER 使用 Basic Auth clone 私有仓库，读取初始化 README。
2. 创建 `feature/git-smart-http`，commit 后 push 成功。
3. 第二工作区 clone 后 fetch 该 feature 分支，远端 commit 可解析。
4. 尝试把同一 commit 直接 push 到 `main`，客户端收到 `protected branch requires merge request`，操作失败。
5. REPORTER 可以 clone 同一私有仓库。
6. REPORTER push 新分支失败，并通过 OWNER `ls-remote` 确认远端没有产生该分支。
7. 测试结束释放 JGit WindowCache，并在 Windows 上清理临时 pack、仓库和数据库记录。

### 6.2 Windows MySQL + Docker Redis + 最终 JAR

执行：

```powershell
.\scripts\verify-m1-git.ps1
```

真实环境：Windows MySQL 8.0.43、Docker Redis 7.4.2、最终 Spring Boot 可执行 JAR、Git for Windows 2.55.0。

脚本使用随机临时用户名、随机密码与随机仓库 slug，实际完成 7 个阶段：

1. 检查后端 Readiness 与 MySQL 管理 login-path。
2. 创建 OWNER、REPORTER 和私有仓库。
3. OWNER clone 私有仓库。
4. 普通 feature 分支 commit、push，并在第二工作区 fetch/解析远端 commit。
5. 验证直接 push `main` 被保护钩子拒绝。
6. 验证 REPORTER 可以 clone、不能 push，且远端不存在目标分支。
7. 输出 `Real Git Smart HTTP verification passed`。

清理与恢复结果：

- 临时用户数量：0。
- 临时仓库元数据数量：0。
- 临时仓库目录数量：0。
- MySQL 服务：Running。
- Redis 容器：healthy。
- 后端清理后重新启动，Readiness：UP。
- 验收脚本通过 `GIT_ASKPASS` 提供随机临时密码，不把真实密码写入 URL、脚本参数或项目文件。

## 7. 过程中发现并处理的问题

- repository 模块初版缺少 Jakarta Servlet API：补充 provided 依赖，使 JGit 7.3 Jakarta Servlet 类型可编译。
- `RepositoryNotFoundException` 包路径误用：按 JGit 7.3 源码改为 `org.eclipse.jgit.errors.RepositoryNotFoundException`。
- 首次 Git CLI 测试将测试 HTTP 上限设为 64 bytes，误拦正常 upload-pack POST：改为 4 KiB，并使用 4097 bytes 单独验证 413；生产默认仍为 110 MiB。
- JGit 将 `ServiceNotAuthorizedException` 映射为 HTTP 401，因此 Git 客户端对已认证但无 push 权限显示标准 `Authentication failed`；测试不只检查文案，还验证远端分支不存在。
- Windows 上 JGit WindowCache 会短暂持有 pack 映射文件：测试清理前通过 `WindowCacheConfig.install()` 替换缓存，并保留最多 5 次有界重试；持续泄漏仍会使测试失败。

## 8. 结论与边界

M1.3 Git Smart HTTP 基线通过。当前可以真实声明已实现带 Basic Auth、仓库授权、默认分支保护和资源限制的 clone/fetch/push。

仍未完成：

- 仓库成员管理 API。
- 分支与文件 REST 浏览。
- MR、Diff、行级评论和服务端 merge commit。
- 仓库级写锁与并发 push 完整性压力测试。
- HTTPS/TLS、凭据助手和生产级反向代理部署。
- Git LFS、SSH、多节点仓库存储。
