# CodeTrove M1.2 仓库与成员权限验证记录

## 1. 实施范围

M1.2 实现仓库元数据、成员角色、受控 bare Git 存储和基础查询 API：

- `POST /api/v1/repositories` 创建仓库。
- `GET /api/v1/repositories` 查询当前用户可见仓库，支持不透明游标分页。
- `GET /api/v1/repositories/{repoId}` 查询仓库详情。
- `OWNER/MAINTAINER/DEVELOPER/REPORTER` 权限矩阵。
- 创建者自动成为 OWNER。
- 私有仓库成员隔离，公开仓库对已认证用户可读。
- JGit 创建真实 bare 仓库，可选直接写入 README 初始提交。

本阶段未开放 Git Smart HTTP clone/fetch/push，也未实现成员增删改端点、分支浏览和文件浏览；这些能力不得描述为已完成。

## 2. 实现证据

- 迁移：`backend/codetrove-bootstrap/src/main/resources/db/migration/V3__create_repository_and_member.sql`
- API：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryController.java`
- 业务服务：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryService.java`
- 元数据持久层：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryMetadataRepository.java`
- Git 存储：`backend/codetrove-repository/src/main/java/com/codetrove/repository/GitRepositoryStorage.java`
- 权限矩阵：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryAuthorizationService.java`
- 公共认证主体：`backend/codetrove-common/src/main/java/com/codetrove/common/security/AuthenticatedUser.java`
- 集成测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/repository/RepositoryApiTests.java`

## 3. 数据与存储设计

- `codetrove_repository` 保存仓库元数据、状态、逻辑默认分支、受控存储路径和乐观锁版本。
- `codetrove_repository_member` 使用 `(repository_id, user_id)` 联合主键并限制角色枚举。
- `(owner_id, slug)` 唯一约束保证同一所有者下 slug 唯一。
- 仓库路径由服务端基于 `storage-root/owner/slug.git` 生成并规范化，用户不能提交绝对路径。
- API 只返回逻辑 `gitHttpUrl`，不暴露 `storage_path`。
- 初始化流程为：写入 `INITIALIZING` 元数据和 OWNER 成员 → 创建 bare Git → 转为 `ACTIVE`。
- 如果数据库事务回滚，事务同步回调删除本次新建的仓库目录。
- 调用前已存在的目录拒绝复用且不允许补偿逻辑删除。

## 4. 权限矩阵

| 权限 | OWNER | MAINTAINER | DEVELOPER | REPORTER |
| --- | --- | --- | --- | --- |
| READ | 是 | 是 | 是 | 是 |
| CREATE_MERGE_REQUEST | 是 | 是 | 是 | 是 |
| COMMENT | 是 | 是 | 是 | 是 |
| PUSH | 是 | 是 | 是 | 否 |
| MERGE | 是 | 是 | 是 | 否 |
| MODIFY_RULES | 是 | 是 | 否 | 否 |
| MANAGE_MEMBERS | 是 | 否 | 否 | 否 |
| MANAGE_REPOSITORY | 是 | 否 | 否 | 否 |

当前仓库创建、列表和详情已接入认证与可见性过滤；后续成员管理、Git push、MR merge 等端点必须复用该授权服务。

## 5. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

结果：

- 后端共 21 项测试全部通过，0 失败、0 错误、0 跳过。
- 10 项仓库集成测试覆盖：
  - 所有仓库 API 必须认证。
  - 创建私有仓库并自动授予 OWNER。
  - 创建真实 bare Git 仓库、`main` HEAD 和 README 初始提交。
  - 重复 slug 返回 409 且不删除原仓库。
  - 私有仓库对非成员列表不可见、详情返回 404。
  - 公开仓库对已认证非成员可见。
  - `../escape` 等非法 slug 在创建目录前被拒绝。
  - 已存在存储目录拒绝复用且原文件保留，数据库事务回滚。
  - 四角色权限矩阵全部断言。
  - 列表限制 1～100，基于 ID 的 Base64 URL-safe 游标分页无重复、无遗漏，非法游标返回统一 400。
  - 非数字仓库 ID 返回统一 400 `VALIDATION_FAILED`，不落入通用 500。
- H2 空库依次执行 Flyway V1、V2、V3，重复启动确认 Schema 已为 V3。
- 全部模块 Checkstyle 为 0 违规。
- Spring Boot 可执行 JAR 打包成功。

## 6. 真实环境验证

环境：Windows 本机 MySQL 8.0.43、Docker Redis 7.4.2、Java 17、Git for Windows。

验证结果：

- 最新后端 Readiness 为 `UP`。
- 真实 MySQL `flyway_schema_history` 当前版本为 3。
- `codetrove_repository` 与 `codetrove_repository_member` 表存在。
- 随机创建临时 OWNER 和 outsider 用户、一个私有仓库和一个公开仓库。
- OWNER 能在列表中看到两个仓库，private/public 均正确。
- outsider 列表只能看到公开仓库，访问私有详情返回 404。
- API 与数据库中的当前角色均为 OWNER。
- `git rev-parse --is-bare-repository` 返回 `true`。
- bare 仓库 HEAD 为 `refs/heads/main`，`main:README.md` 包含初始内容。
- API 响应不含绝对 `storagePath`。
- 11 个真实闭环断言全部为 `true`。
- 验收后的临时用户、成员、仓库元数据和文件目录全部清理，数据库与文件系统残留数均为 0。

## 7. 过程中发现并处理的问题

- JGit `URIish` 会引入额外受检异常且临时工作区 push 过重：改为直接使用 JGit ObjectInserter 向 bare 仓库写入 blob、tree 和 commit。
- Windows Git 对象文件可能带只读属性：生产补偿与测试清理均先解除文件只读属性，再逆序删除。
- 重复 slug 初版错误地尝试清理目标路径，可能误删原仓库：改为数据库冲突时不触碰现有目录，并加入回归测试。
- 存储层已拒绝已有目录，但 Service 初版仍重复执行清理：删除上层无条件清理，由存储层只处理本次新建路径。
- 仓库列表最初只有 `limit`：补齐通用 API 契约要求的不透明游标和 `meta.nextCursor`。
- 方法级 `limit` 校验需要统一异常映射：为 Controller 开启 `@Validated`，并将 `ConstraintViolationException` 映射为 `VALIDATION_FAILED`。

## 8. 结论

M1.2 仓库与成员权限基线通过。下一步 M1.3 应实现 Git Smart HTTP clone/fetch/push，并把 READ/PUSH 权限、受保护分支和资源限制接入真实 Git 协议处理链路。
