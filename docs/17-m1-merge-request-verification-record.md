# CodeTrove M1.5 Merge Request、Diff 与评论验证记录

## 1. 实施范围

M1.5 在认证、仓库权限、Git Smart HTTP 和代码浏览能力之上，实现代码协作的评审数据基础：

- MR 创建、列表、详情、编辑与关闭。
- 创建时保存目标分支 `base_commit` 与源分支 `head_commit` 快照。
- 基于固定快照生成受限 JGit Diff。
- 普通评论与绑定 commit/file/side/line 的行级评论。
- MR 和评论的不透明游标分页。
- `version` 乐观锁、私有仓库隔离与角色权限校验。

本阶段不实现 Merge、Check 门禁、审批、push 后自动更新 MR head、Outbox 或 Kafka 事件。

## 2. 实现证据

- 数据迁移：`backend/codetrove-bootstrap/src/main/resources/db/migration/V4__create_merge_request_and_comment.sql`
- 仓库公共访问门面：`backend/codetrove-repository/src/main/java/com/codetrove/repository/RepositoryAccessService.java`
- MR API：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestController.java`
- MR 服务：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestService.java`
- MR 持久化：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestRepository.java`
- Diff：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestDiffService.java`
- 评论服务：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestCollaborationService.java`
- 评论持久化：`backend/codetrove-merge-request/src/main/java/com/codetrove/mergerequest/MergeRequestCommentRepository.java`
- 集成测试：`backend/codetrove-bootstrap/src/test/java/com/codetrove/bootstrap/mergerequest/MergeRequestApiTests.java`
- 真实环境脚本：`scripts/verify-m1-merge-request.ps1`

## 3. 数据模型与并发控制

Flyway V4 创建：

- `codetrove_merge_request`：仓库内 iid、标题、描述、源/目标分支、base/head commit、状态、作者、合并字段、version 和审计时间。
- `codetrove_merge_request_comment`：评论类型、Diff 定位、作者类型、正文、系统去重 fingerprint 和审计时间。

约束：

- `(repository_id, iid)` 唯一。
- 源分支与目标分支不能相同。
- 非 `MERGED` 状态不能写入合并人和合并时间。
- Diff 评论必须完整提供路径、side、行号和 commit；普通评论不能携带这些字段。
- 创建 MR 时在同一数据库事务中锁定仓库行，再检查重复 OPEN 组合并分配下一个 iid，防止并发分配重复编号。
- 更新和关闭使用 `status='OPEN' AND version=?` 条件更新，成功后 version 加一；陈旧 version 返回 409 `STATE_CONFLICT`。

## 4. MR API 行为

实现：

- `POST /api/v1/repositories/{repoId}/merge-requests`
- `GET /api/v1/repositories/{repoId}/merge-requests`
- `GET /api/v1/repositories/{repoId}/merge-requests/{iid}`
- `PATCH /api/v1/repositories/{repoId}/merge-requests/{iid}`

行为：

- 创建要求 `CREATE_MERGE_REQUEST` 权限，只接受两个不同且存在的本地分支。
- base/head commit 由服务端从精确 `refs/heads/*` 解析，客户端不能提交 commit 快照、author、iid 或 version 初值。
- 同仓库同源/目标最多一个 OPEN MR，重复创建返回 409 `MR_ALREADY_OPEN`。
- 列表支持状态、目标分支与基于 Snowflake ID 的倒序 Keyset Pagination。
- 作者、OWNER、MAINTAINER可以编辑标题/描述或关闭 OPEN MR；其他成员不能编辑他人 MR。
- M1.5 仅允许 `OPEN -> CLOSED`，不允许重开或直接写入 `MERGED`。

## 5. 受限 Diff

实现：

- `GET /api/v1/repositories/{repoId}/merge-requests/{iid}/diff`
- 固定比较 MR 保存的 `base_commit -> head_commit`，不接受客户端覆盖提交范围。
- 返回 change type、old/new path、additions、deletions、binary、patch 和 truncated。
- JGit `DiffFormatter` 开启 rename detection，使用 `RawTextComparator.DEFAULT` 和 3 行上下文。

默认资源限制：

- 文件数：200。
- 单文件 patch：262144 bytes（256 KiB）。
- 总 patch：2097152 bytes（2 MiB）。
- 二进制文件不返回 patch；超限 patch 截断并显式返回 `truncated=true`。

## 6. 普通评论与行级评论

实现：

- `POST /api/v1/repositories/{repoId}/merge-requests/{iid}/comments`
- `GET /api/v1/repositories/{repoId}/merge-requests/{iid}/comments`

行为：

- 评论要求 `COMMENT` 权限，关闭后的 MR 不允许新增评论。
- 普通评论只保存正文和作者。
- 行级评论的 commit 必须等于 MR 的 head commit。
- Diff API 与行级评论校验共用 JGit `EditList`：`NEW` 只能命中真实新增区间，`OLD` 只能命中真实删除区间，路径必须与对应 side 一致。
- 无效定位返回 400 `MR_DIFF_POSITION_INVALID`，不写入数据库。
- 评论正文保存 Markdown 原文，服务端不生成 HTML；前端渲染必须净化。
- 评论按 Snowflake ID 升序分页，使用 Base64 URL-safe 不透明游标。

## 7. 自动化验证

执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- 后端共 51 项测试全部通过，0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- Spring Boot 可执行 JAR 打包成功。
- M1.5 新增 11 组集成测试，覆盖：
  - 真实本地分支解析与 base/head commit 快照。
  - 相同分支、缺失分支和重复 OPEN MR。
  - 状态/目标分支筛选与 MR 游标分页。
  - 作者编辑、关闭、陈旧 version 和非作者权限。
  - 私有仓库隔离与公开仓库非成员写拒绝。
  - 小型文本 Diff、二进制 Diff 和单文件 patch 截断。
  - 普通评论、有效行级评论与评论游标分页。
  - 错误 commit、路径、side、行号均被拒绝。
  - 关闭状态禁止新增评论。

## 8. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m1-merge-request.ps1
```

环境：Windows MySQL 8.0.43、Docker Redis 7.4.2、最终 Spring Boot JAR、Git for Windows 2.55.0。

脚本完成 8 个阶段：

1. 检查最终 JAR Readiness 与真实 MySQL Flyway V4。
2. 创建随机临时 OWNER、outsider 和私有仓库。
3. 通过真实 Git Smart HTTP clone、commit、push feature 分支。
4. 创建 MR，校验 iid、base/head commit、OPEN/version 与重复 OPEN 冲突。
5. 校验 MR 列表、详情与包含真实代码行的固定快照 Diff。
6. 创建普通评论和有效行级评论，拒绝无效行，并验证评论分页。
7. 校验私有仓库 404、乐观锁冲突、关闭状态和关闭后评论拒绝。
8. 输出 `Real M1.5 merge request verification passed`。

验收完成后：

- 临时用户数量：0。
- 临时 MR 数量：0。
- 临时评论数量：0。
- 临时仓库元数据数量：0。
- 临时仓库目录数量：0。
- MySQL：Running。
- Redis：healthy。
- 后端重新启动后 Readiness：UP。

## 9. 过程中发现并处理的问题

- 公开仓库已认证非成员的角色为 `null`，旧权限服务对不可空 Map 使用 `getOrDefault(null, ...)` 会抛 NPE，导致写请求误报 500；现已显式将空角色判为无权限并通过测试验证返回 403。
- Windows 运行中的 JAR 会阻止 Maven `clean`；全量验证统一先确认进程归属并停止后端，构建完成后再恢复服务。
- Diff 展示和评论定位如果各自计算行号可能产生偏差，因此共用 JGit `FileHeader.toEditList()` 作为行区间事实源。
- Patch 限额不能只在序列化后检查；实现有界输出流，在 JGit 写 patch 时即停止累积，避免超大响应占用无界内存。

## 10. 结论与边界

M1.5 MR、Diff 与评论基础通过。当前可以真实声明：平台支持基于真实 Git 分支快照创建和管理 MR、生成受资源限制的 JGit Diff，并发表经过 commit/file/side/line 校验的行级评论。

仍未完成：

- push 后自动更新 MR head 与增量提交记录。
- Review/Approval、Check Suite/Run 和 Merge 门禁。
- 无冲突 merge commit、冲突检测和幂等 Merge。
- Outbox/Kafka 事件与异步 AI/测试任务。
- 前端接入真实 MR/Diff/评论 API。
