# CodeTrove M1.4 分支与文件浏览验证记录

## 1. 实施范围

M1.4 在已有仓库元数据、权限矩阵和 Git Smart HTTP 基础上，实现只读代码浏览 API：

- `GET /api/v1/repositories/{repoId}/branches`
- `GET /api/v1/repositories/{repoId}/tree`
- `GET /api/v1/repositories/{repoId}/blob`
- 分支名与完整 40 位 commit ID 定位。
- 当前目录单层浏览与不透明游标分页。
- 小型 UTF-8 文本内联，二进制和超大文本仅返回元数据。
- ref、路径、游标、文件类型和私有仓库隔离安全校验。

本阶段验收时不提供任意文件下载，不支持 tag/reflog/缩写 SHA/revision 表达式，也尚未实现 MR、Diff、评论和合并。MR/Diff/评论基础随后已在 M1.5 完成，合并仍未实现。

## 2. 实现证据

- API：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryController.java`
- 应用服务：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryService.java`
- Git 对象浏览：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryBrowser.java`
- 受控仓库打开：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryStorage.java`
- 错误码：`backend/codetrove-common/src/main/java/com/codetrove/common/exception/ErrorCode.java`
- 集成测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryBrowseApiTests.java`
- 真实环境脚本：`scripts/verify-m1-browse.ps1`

## 3. API 行为

### 3.1 分支列表

- 只枚举 `refs/heads/*`，按分支名升序排列。
- 返回分支名、40 位 commit ID、最后提交短消息、作者、提交时间和默认分支标记。
- 空仓库返回空数组，不把 HEAD、tag 或 remote-tracking ref 混入结果。

### 3.2 目录树

- `ref` 只接受本地分支名、`refs/heads/*` 或完整 40 位 commit ID。
- 根目录使用空路径；子目录使用 `/` 分隔的仓库内部路径。
- 只列当前目录，不递归；条目类型为 `TREE/BLOB/SYMLINK/GITLINK`。
- 目录条目不计算文件大小；blob/symlink 返回 Git 对象大小。
- `limit` 范围 1～500，按名称稳定排序，使用 Base64 URL-safe 不透明游标。
- 响应带解析后的 `commitId`、规范化 `path`、`entries` 和 `nextCursor`。

### 3.3 文件读取

- 默认最多内联 1048576 bytes，可由 `CODETROVE_REPOSITORY_MAX_INLINE_BLOB_BYTES` 调整。
- 小型、不含 NUL、严格 UTF-8 可解码的文件返回文本内容。
- NUL 或 UTF-8 解码失败按 `BINARY` 返回，不内联内容。
- 超过阈值的 UTF-8 文本返回 `TOO_LARGE`，不把大文本装入 API 响应。
- 目录、submodule 或不存在路径返回 `REPOSITORY_PATH_NOT_FOUND`。

## 4. 安全边界

- 浏览端点复用 `findVisibleById`：私有仓库非成员统一 404，公开仓库允许已认证非成员读取。
- 仓库打开时重新计算受控 `storage-root/owner/slug.git`，必须与数据库路径完全一致。
- 仓库目录或 owner 父目录是符号链接时拒绝打开。
- 内容读取只使用 JGit Object Database 和 TreeWalk，不把仓库内部路径拼接为宿主文件路径，因此 Git symlink 不会被跟随到仓库外。
- 路径拒绝绝对路径、反斜杠、NUL、`.`、`..`、空段和尾随 `/`。
- ref 拒绝 tag、缩写 SHA、reflog、`HEAD~1`、`^{tree}` 等通用 revision 表达式。
- 非法参数返回 400 `VALIDATION_FAILED`；合法但不存在的 ref/path 分别返回 404 `REPOSITORY_REF_NOT_FOUND` / `REPOSITORY_PATH_NOT_FOUND`。

## 5. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- 后端共 40 项测试全部通过，0 失败、0 错误、0 跳过。
- 全部模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 打包成功。
- M1.4 新增 8 组集成测试，覆盖：
  - 两个本地分支、提交元数据和默认分支标记。
  - 根目录两页游标分页、子目录浏览和完整 commit ID 浏览。
  - 小型 UTF-8 文本、二进制文件和超过测试阈值的大文本。
  - tag、缩写 SHA、revision 表达式与不存在分支。
  - `../`、绝对路径、反斜杠、双斜杠、尾随斜杠、`.` 和非法游标。
  - 文件当目录、目录当文件和不存在路径。
  - 私有仓库非成员 404、公开仓库已认证非成员可读。
  - 未认证访问和 `limit > 500`。

## 6. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m1-browse.ps1
```

环境：Windows MySQL 8.0.43、Docker Redis 7.4.2、最终 Spring Boot JAR、Git for Windows 2.55.0。

脚本完成 8 个阶段：

1. 检查后端 Readiness 和 MySQL 管理 login-path。
2. 创建随机临时 OWNER、outsider 和私有仓库。
3. 通过真实 Git Smart HTTP clone/push 构造 feature 分支、嵌套目录、小文本、二进制和超过 1 MiB 的文本。
4. 分支 API 的 commit ID 与 `git rev-parse HEAD` 一致。
5. 根目录游标分页与完整 commit ID 子目录浏览通过。
6. 小型 UTF-8 文本内联、二进制返回 `BINARY`、超大文本返回 `TOO_LARGE`。
7. `HEAD~1`、`../README.md` 和私有仓库 outsider 访问返回预期 400/404。
8. 输出 `Real repository browse verification passed`。

验收完成后：

- 临时用户数量：0。
- 临时仓库元数据数量：0。
- 临时仓库目录数量：0。
- MySQL：Running。
- Redis：healthy。
- 后端重新启动后 Readiness：UP。

## 7. 过程中发现并处理的问题

- repository 模块使用 `@JsonProperty("default")` 时缺少直接 Jackson 注解依赖：补充 `jackson-annotations`，避免依赖传递关系不明确。
- PowerShell 5.1 对单个管道对象的 `.Count` 行为不稳定：真实验收断言改为显式数组 `@(...)` 计数。
- 浏览路径未映射到文件系统，而是用 TreeWalk 读取 Git 对象，避免 symlink/path traversal 绕过宿主目录边界。
- 大文件在读取完整内容前先依据 ObjectLoader size 分流，只读取最多 8192 bytes 样本判断二进制，避免 API 内存放大。

## 8. 结论与边界

M1.4 分支与文件浏览 API 通过。当前可以真实声明：平台支持受权限控制的分支元数据、Git tree 浏览、commit 固定版本浏览，以及安全受限的文本文件读取。

仍未完成：

- tag、历史提交列表、提交详情和 blame。
- 任意文件下载、Range 请求和语法高亮。
- 搜索与合并；MR、受限 Diff 和行级评论已在 M1.5 完成。
- 前端接入真实 branches/tree/blob API。
