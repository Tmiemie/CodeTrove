# CodeTrove REST API 契约

## 1. 通用约定

- 基础路径：`/api/v1`。
- 媒体类型：`application/json; charset=utf-8`。
- 时间：ISO 8601 UTC，例如 `2026-10-04T05:00:00Z`。
- ID 在 JSON 中使用字符串传输，避免前端整数精度丢失。
- 分页使用游标：`limit`（默认 20，最大 100）和 `cursor`。
- 写接口支持 `Idempotency-Key` 的场景必须在端点中注明。
- 所有响应返回 `X-Trace-Id`。

## 2. 统一响应

成功：

```json
{
  "data": {},
  "meta": { "traceId": "..." }
}
```

分页：

```json
{
  "data": [],
  "meta": {
    "traceId": "...",
    "nextCursor": "opaque-or-null"
  }
}
```

失败：

```json
{
  "error": {
    "code": "MR_CHECKS_NOT_PASSED",
    "message": "Blocking checks have not passed",
    "details": {},
    "traceId": "..."
  }
}
```

禁止在生产响应中返回堆栈、SQL、绝对路径、Token 或第三方密钥。

## 3. HTTP 状态约定

| 状态 | 用途 |
| --- | --- |
| 200 | 查询或更新成功 |
| 201 | 创建成功 |
| 202 | 已接受异步任务 |
| 204 | 删除或无响应体操作成功 |
| 400 | 请求格式或字段非法 |
| 401 | 未认证或 Token 失效 |
| 403 | 已认证但无权限 |
| 404 | 资源不存在或无权感知其存在 |
| 409 | 状态冲突、重复资源、分支变化、不可合并 |
| 412 | `If-Match` 等前置条件失败 |
| 422 | 语义校验失败，例如用例 Schema 不合法 |
| 429 | 限流 |
| 503 | 依赖暂不可用 |

## 4. 核心错误码

- `AUTH_INVALID_CREDENTIALS`
- `AUTH_TOKEN_INVALID`
- `AUTH_TOKEN_EXPIRED`
- `ACCESS_DENIED`
- `USERNAME_CONFLICT`
- `REPOSITORY_NOT_FOUND`
- `REPOSITORY_PERMISSION_DENIED`
- `REPOSITORY_SLUG_CONFLICT`
- `REPOSITORY_REF_NOT_FOUND`
- `REPOSITORY_PATH_NOT_FOUND`
- `GIT_REF_CHANGED`
- `MR_NOT_OPEN`
- `MR_ALREADY_OPEN`
- `MR_BRANCHES_IDENTICAL`
- `MR_HEAD_CHANGED`
- `MR_DIFF_POSITION_INVALID`
- `MR_MERGE_CONFLICT`
- `MR_CHECKS_NOT_PASSED`
- `IDEMPOTENCY_KEY_CONFLICT`
- `CHECK_STALE`
- `ASSAY_SCHEMA_INVALID`
- `ASSAY_SECURITY_POLICY_VIOLATION`
- `RATE_LIMITED`
- `DEPENDENCY_UNAVAILABLE`

## 5. Auth API

### POST `/auth/register`

```json
{
  "username": "alice",
  "password": "user-provided-secret",
  "displayName": "Alice"
}
```

返回 201，不回传密码哈希。用户名会去除首尾空白并统一转为小写；同名用户返回 409 `USERNAME_CONFLICT`。用户名长度 3～64，仅允许字母、数字、`_`、`-`，且必须以字母或数字开始和结束；密码长度 12～72。

### POST `/auth/login`

```json
{
  "username": "alice",
  "password": "user-provided-secret"
}
```

返回 15 分钟有效的 HMAC-SHA256 JWT Access Token：

```json
{
  "data": {
    "tokenType": "Bearer",
    "accessToken": "...",
    "expiresAt": "2026-10-04T09:15:00Z",
    "user": {
      "id": "123456789012345678",
      "username": "alice",
      "displayName": "Alice",
      "status": "ACTIVE"
    }
  },
  "meta": { "traceId": "..." }
}
```

Token 只包含用户 ID、用户名、签发时间、过期时间和 token ID。不存在用户和错误密码统一返回 401 `AUTH_INVALID_CREDENTIALS`；无效 Token 返回 `AUTH_TOKEN_INVALID`，过期 Token 返回 `AUTH_TOKEN_EXPIRED`。Refresh Token、轮换与撤销尚未实现，进入后续认证增强阶段；不得把 Access Token 持久化到浏览器 Local Storage。

### GET `/users/me`

要求 `Authorization: Bearer <access-token>`，返回当前活动用户的公开资料和状态。无 Token 或 Token 无效返回统一 401 错误。

## 6. Repository API

### POST `/repositories`

```json
{
  "name": "demo-service",
  "slug": "demo-service",
  "visibility": "PRIVATE",
  "initializeWithReadme": true
}
```

创建成功返回 201，并在同一事务中把创建者写为 `OWNER`。`slug` 只允许小写字母、数字和连字符；同一所有者下重复 slug 返回 409 `REPOSITORY_SLUG_CONFLICT`。服务端在受控根目录创建 bare Git 仓库，可选直接写入 `main` 分支的 README 初始提交。响应中的 `gitHttpUrl` 是已启用的 M1.3 Git Smart HTTP 地址；绝对 `storage_path` 不对外返回。

### GET `/repositories`

查询当前用户可见仓库：自己的成员仓库与 `PUBLIC` 仓库。需要 Bearer Token。

- `limit` 默认 20，范围 1～100。
- `cursor` 为基于仓库 ID 的 Base64 URL-safe 不透明游标。
- 响应使用 `meta.nextCursor`；没有下一页时省略。
- 无效 cursor 或越界 limit 返回 400 `VALIDATION_FAILED`。

### GET `/repositories/{repoId}`

返回仓库详情、当前用户角色和逻辑 Git HTTP URL。需要 Bearer Token。私有仓库对非成员统一返回 404 `REPOSITORY_NOT_FOUND`，不暴露资源是否存在；公开仓库对已认证非成员可读，`currentUserRole` 为空。非数字 `repoId` 返回 400 `VALIDATION_FAILED`，不落入通用 500。

### GET `/repositories/{repoId}/branches`

返回按分支名升序排列的本地分支，字段为 `name/commitId/commitMessage/authorName/authoredAt/default`。只返回 `refs/heads/*`，不混入 tag、remote-tracking ref 或符号 HEAD；空仓库返回空数组。需要仓库 `READ` 权限。

### GET `/repositories/{repoId}/tree?ref=main&path=src`

- `ref` 必填，只接受完整分支名或 40 位十六进制 commit ID；不接受 tag、缩写 SHA、reflog、`HEAD~1`、`^{tree}` 等 revision 表达式。
- `path` 为空表示根目录；非空路径最长 1024 个字符，必须使用 `/`，禁止绝对路径、反斜杠、NUL、`.`、`..`、空路径段和尾随 `/`。
- 只列当前目录，不递归；条目包含 `name/path/type/objectId/size`，`type` 为 `TREE/BLOB/SYMLINK/GITLINK`。
- `limit` 默认 100，范围 1～500；`cursor` 是基于最后一个条目名称的 Base64 URL-safe 不透明游标。
- 响应同时返回解析后的 40 位 `commitId` 和当前规范化 `path`；目标不存在或不是目录返回 404 `REPOSITORY_PATH_NOT_FOUND`。
- 需要仓库 `READ` 权限；私有仓库非成员仍统一返回 404 `REPOSITORY_NOT_FOUND`。

### GET `/repositories/{repoId}/blob?ref=main&path=README.md`

- `ref` 规则与 tree 相同；`path` 必填并使用相同路径校验。
- 返回 `path/objectId/size/binary/contentIncluded/content/encoding/notIncludedReason`。
- 仅当文件不超过配置上限（默认 1048576 bytes）、不含 NUL 且能被严格 UTF-8 解码时，返回 `contentIncluded=true`、`encoding=UTF-8` 和文本 `content`。
- 二进制文件返回 `binary=true`、`contentIncluded=false`、`notIncludedReason=BINARY`；超大文本返回 `binary=false`、`contentIncluded=false`、`notIncludedReason=TOO_LARGE`。M1.4 不提供任意文件下载端点。
- 目录、submodule 或不存在路径返回 404 `REPOSITORY_PATH_NOT_FOUND`。

### 成员管理

- `GET /repositories/{repoId}/members`
- `POST /repositories/{repoId}/members`
- `PATCH /repositories/{repoId}/members/{userId}`
- `DELETE /repositories/{repoId}/members/{userId}`

## 7. Git Smart HTTP

路径：`/git/{owner}/{repo}.git/*`。

- `GET .../info/refs?service=git-upload-pack`
- `POST .../git-upload-pack`
- `GET .../info/refs?service=git-receive-pack`
- `POST .../git-receive-pack`

要求：

- 所有 Git Smart HTTP 请求使用 UTF-8 Basic Auth；缺少或错误凭据返回 401，并携带 `WWW-Authenticate: Basic realm="CodeTrove Git", charset="UTF-8"`。
- clone/fetch 需要 `READ` 权限；当前公开仓库允许已认证非成员读取，私有仓库非成员统一表现为 404。
- push 需要 `PUSH` 权限；`OWNER/MAINTAINER/DEVELOPER` 允许，`REPORTER` 拒绝。
- JWT Filter 跳过 `/git/**`，避免把 Basic Header 当作 Bearer Token；REST API 继续使用 JWT。
- 默认分支 `main` 禁止直接创建、更新或删除；触及默认分支的同批 push 原子拒绝全部尚未执行的 ref 命令，错误消息为 `protected branch requires merge request`。
- 禁止 non-fast-forward push；普通分支允许创建和删除。
- 默认限制：60 秒 I/O 超时、110 MiB HTTP 请求、1 MiB command、20 MiB 单对象、100 MiB pack、最多 8 个并发 Git 请求。
- 超出 HTTP 请求体限制返回 413；并发许可耗尽返回 429 与 `Retry-After: 1`。
- 仓库名严格匹配 `{owner}/{repo}.git`，最终存储路径只能来自数据库中已激活仓库的受控绝对路径，客户端不能提交文件系统路径。

## 8. Merge Request API

### POST `/repositories/{repoId}/merge-requests`

```json
{
  "title": "Add payment validation",
  "description": "...",
  "sourceBranch": "feature/payment-validation",
  "targetBranch": "main"
}
```

- 需要 `CREATE_MERGE_REQUEST` 权限。
- 标题去除首尾空白后长度 1～255；描述最大 20000 字符。
- 源/目标必须是两个不同且存在的本地分支，创建时解析并保存 `baseCommit` 和 `headCommit`。
- 同仓库、同源分支、同目标分支最多一个 `OPEN` MR；仓库内 `iid` 在锁定仓库行后递增分配。
- 创建成功返回 201；分支不存在返回 404 `REPOSITORY_REF_NOT_FOUND`，分支相同或重复 OPEN MR 返回 409。

### GET `/repositories/{repoId}/merge-requests`

- 需要仓库 `READ` 权限。
- 可选筛选：`status=OPEN|CLOSED|MERGED`、`targetBranch`。
- `limit` 默认 20，范围 1～100；使用基于 MR Snowflake ID 的 Base64 URL-safe 不透明游标。
- 按 ID 倒序返回 MR 摘要及 `meta.nextCursor`。

### GET `/repositories/{repoId}/merge-requests/{iid}`

返回 MR、作者、分支、创建时 `baseCommit/headCommit`、状态、版本和时间。M1.5 尚无 Check 汇总与 mergeability，响应不得伪造这些字段。

### GET `/repositories/{repoId}/merge-requests/{iid}/diff`

- Diff 固定使用 MR 保存的 `baseCommit -> headCommit`，M1.5 不接受客户端覆盖 commit。
- 返回文件状态、old/new path、additions、deletions、patch 和 truncated。
- 默认最多 200 个文件、单文件 patch 262144 bytes、总 patch 2097152 bytes；超限时保留统计与路径并标记截断。
- 二进制文件不输出 patch；提交对象不存在返回 409 `MR_HEAD_CHANGED`。

### PATCH `/repositories/{repoId}/merge-requests/{iid}`

```json
{
  "title": "Updated title",
  "description": "Updated description",
  "status": "CLOSED",
  "version": 0
}
```

- 只能修改 `OPEN` MR；至少提供一个可变字段。
- 作者、OWNER 或 MAINTAINER 可编辑标题/描述或关闭 MR；其他成员返回 403。
- `version` 必填并执行乐观锁；版本不匹配返回 409 `STATE_CONFLICT`。
- M1.5 只允许状态变为 `CLOSED`，不支持重开或直接写入 `MERGED`。

### POST `/repositories/{repoId}/merge-requests/{iid}/merge`

请求头：

```text
Idempotency-Key: merge-20261005-001
```

```json
{
  "expectedHeadCommit": "40-hex-commit-id",
  "strategy": "MERGE_COMMIT"
}
```

- 需要仓库 `MERGE` 权限；当前矩阵允许 OWNER、MAINTAINER、DEVELOPER，REPORTER 拒绝。
- `Idempotency-Key` 必填，长度 8～128，只允许字母、数字、`.`、`_`、`:`、`-`；在仓库范围内唯一。
- `expectedHeadCommit` 必须是 40 位十六进制并同时等于 MR 当前 head 与临界区重新读取的源分支 head，否则返回 409 `MR_HEAD_CHANGED`。
- 首版只支持 `MERGE_COMMIT`；服务端在仓库级 JVM 锁内重新读取源/目标引用并执行 JGit 三方合并。
- 有冲突返回 409 `MR_MERGE_CONFLICT`，不更新目标引用或 MR 状态。
- 生成两父 merge commit 后，再次核对源引用，并以进入临界区时的目标 commit 做引用 CAS；CAS 失败返回 409 `GIT_REF_CHANGED`。
- 合并操作先持久化为 `PENDING`，Git 引用 CAS 成功后将 MR 与操作更新为 `MERGED/SUCCEEDED`；重试相同 key 可恢复引用已成功但数据库未完成的操作。
- 相同 key + 相同请求在成功后返回原 merge commit，并标记 `idempotentReplay=true`；相同 key + 不同请求返回 409 `IDEMPOTENCY_KEY_CONFLICT`。
- 响应返回 `iid/status/mergeCommit/mergedBy/mergedAt/idempotentReplay`。
- M2 已接入当前 head 的 Check 门禁：必须存在 `isCurrent=true` 且 `headCommit` 等于 MR 当前 head 的 Suite，所有 blocking Run 必须为 `SUCCESS`；否则返回 409 `MR_CHECKS_NOT_PASSED`。
- M4 启用后，`assay.integration` 为 blocking `PENDING → RUNNING → SUCCESS/FAILED/SKIPPED/CANCELLED`，由当前 head 的声明式 HTTP 测试真实推进；只有 `SUCCESS` 可满足合并门禁。

## 9. Comment API

### POST `/repositories/{repoId}/merge-requests/{iid}/comments`

普通评论：

```json
{ "body": "Please add a boundary test." }
```

Diff 评论：

```json
{
  "body": "Possible null dereference.",
  "position": {
    "commitId": "40-hex-head-commit",
    "filePath": "src/main/java/example/PayService.java",
    "side": "NEW",
    "line": 42
  }
}
```

- 需要 `COMMENT` 权限，正文去除首尾空白后长度 1～10000。
- `position` 整体可空；一旦提供，四个定位字段都必须存在。
- `commitId` 必须等于 MR 保存的 `headCommit`。
- `NEW` 只能定位当前 Diff 的新增行，`OLD` 只能定位删除行；路径必须与对应 side 的 Diff 路径一致。
- 无效定位返回 400 `MR_DIFF_POSITION_INVALID`；关闭后的 MR 仍可查看评论，但 M1.5 不允许新增评论。
- 服务端保存 Markdown 原文，不渲染 HTML；前端展示时必须净化。

### GET `/repositories/{repoId}/merge-requests/{iid}/comments`

- 需要仓库 `READ` 权限。
- `limit` 默认 50，范围 1～100；按 comment Snowflake ID 升序返回并使用不透明游标继续。
- 返回评论类型 `GENERAL/DIFF`、作者、正文、可选定位和创建时间。

## 10. Check API

### GET `/repositories/{repoId}/merge-requests/{iid}/checks`

返回当前 Check Suite；`includeHistory=true` 时同时返回历史 Suite。每个 Suite 返回 `id/headCommit/status/isCurrent/createdAt/updatedAt/runs`，Run 返回 `id/checkType/name/blocking/status/conclusion/detailsUrl/attempt/startedAt/finishedAt`。

- 需要仓库 `READ` 权限；私有仓库非成员统一 404。
- 当前 MR 尚未被事件消费者建立 Suite 时，返回 `current=null`，不伪造 SUCCESS。
- 新 head 对应的新 Suite 激活后，旧 Suite 标记 `isCurrent=false`，其结果不能参与门禁。

### POST `/repositories/{repoId}/merge-requests/{iid}/checks/{checkName}/rerun`

- 权限：MAINTAINER 或以上；可配置允许 DEVELOPER 重跑。
- 必须绑定当前 `headCommit`。
- 返回 202 和新 attempt。

## 11. Curator API

### GET `/repositories/{repoId}/merge-requests/{iid}/review-findings`

- 需要仓库 `READ` 权限；私有仓库非成员继续统一返回 404。
- 只返回 MR 当前 `headCommit` 对应的 ReviewTask 与 Findings；尚无任务时返回 `task=null, findings=[]`。
- 支持精确筛选 `skill=LOGIC|SECURITY`、`severity=INFO|WARNING|ERROR|CRITICAL`、`disposition=OPEN|ACCEPTED|FALSE_POSITIVE|FIXED`；非法筛选返回 400。
- Finding 返回稳定 ID、skill/severity/ruleId、filePath/side/lineNumber、title/message、受控 evidence/suggestion 与 disposition。
- 当前实现来自确定性静态规则 Skill；外部 LLM Provider 尚未接入。

### PATCH `/repositories/{repoId}/merge-requests/{iid}/review-findings/{findingId}`（规划，尚未实现）

```json
{
  "disposition": "FALSE_POSITIVE",
  "reason": "Generated accessor is accepted by team convention."
}
```

误报反馈必须记录操作者和原因，写入长期 Memory 前需去除敏感代码片段。

## 12. Assay API

### GET `/repositories/{repoId}/merge-requests/{iid}/test-report`

- 需要仓库 `READ` 权限；私有仓库非成员继续统一返回 404。
- 只返回 MR 当前 `headCommit` 对应的 Execution 与 CaseResult；历史 head 不混入当前报告。
- 尚无当前 Execution 时返回 `execution=null,cases=[]`。
- `execution` 返回 `id/headCommit/checkRunId/status/conclusion/attempt/totalCount/passedCount/failedCount/skippedCount`。
- `cases` 返回 `id/caseKey/sourcePath/status/failureCode/durationMs/assertionDiff`。
- `assertionDiff` 是结构化 JSON 数组，元素包含 `path/operator/expected/actual/message`，不是需要客户端二次解析的 JSON 字符串。

M4 没有公开上传/验证/批准 TestCase 的写 API。用例事实源是 MR 当前 head 的 `testcases/**/*.json`，由 Git/MR 流程评审。执行 target 只能是服务端配置的 `application` base URL 或本次执行的进程内 `mock` WireMock；接口不接受任意 host。

规划但尚未实现：执行历史列表、按 execution ID 查询、独立 testcase validate/approve、报告正文下载、rerun API。

## 13. API 演进规则

- `/api/v1` 内只做向后兼容新增。
- 删除字段、改变枚举含义或错误语义时升级到 `/api/v2`。
- 响应新增字段时客户端必须忽略未知字段。
- API 实现后以 OpenAPI 作为机器可读事实源，本文保留边界和语义说明。
