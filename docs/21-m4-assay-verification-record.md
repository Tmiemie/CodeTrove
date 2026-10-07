# CodeTrove M4 CodeAssay 声明式 HTTP 测试验证记录

## 1. 实施范围与命名边界

M4 完成 CodeAssay 的受控声明式 HTTP 测试基线：

- 用例只从 MR 当前 `head_commit` 的 `testcases/**/*.json` 读取。
- 使用 JSON Schema Draft 2020-12 严格校验结构，未知字段拒绝。
- 支持受限表达式、顺序 `data_pre`、进程内 WireMock、主 HTTP 请求、响应断言和结构化差异。
- 持久化 AssayExecution/AssayCaseResult，并写幂等 SYSTEM `TEST_REPORT` 评论。
- 通过 Kafka command/result 驱动当前 head 的 blocking `assay.integration` Check。

本阶段不是通用代码沙箱，不自动构建、部署或运行任意 MR 分支代码，也没有实现 Docker 隔离、DB 断言、Spring Bean Mock、fission、cleanup、流量录制或 AI 用例生成。

## 2. 用例契约与资源限制

M4 顶层支持：

- `schema_version`
- `case_key`
- `description`
- `enabled`
- `timeout_ms`
- `data_pre`
- `request`
- `mocks`
- `assertions`

限制：

- 单次最多 50 个用例文件。
- 单文件最多 262144 bytes。
- 总计最多 2097152 bytes。
- HTTP 响应正文最多有界读取 1048576 bytes；读取第 1048577 byte 即判定超限。
- 单 case timeout 范围 100～30000 ms，默认 5000 ms。
- Header 仅允许 `Accept`、`Content-Type`、`X-CodeTrove-Test-Case`。
- target 仅允许服务端配置的 `application` base URL或本次执行的进程内 `mock` WireMock。
- path 必须以 `/` 开头，拒绝 scheme/host、`//`、反斜杠、NUL、`.`/`..` 路径段。

## 3. 表达式、准备步骤、Mock 与断言

表达式只开放：

- `${context.<save_as>.<field>}`
- `${#uuid()}`
- `${#randomLong(min,max)}`

禁止脚本、反射、文件系统、进程、用户类加载和任意网络访问。

`data_pre` 串行执行受控 HTTP 步骤，支持从 `$.status` 与 `$.body.<field-path>` 提取值写入上下文。HTTP Mock 使用 WireMock Jetty 12，只绑定 `127.0.0.1` 动态端口，支持 method/path、固定 JSON 响应和 `expect_calls.min/max`。

响应断言支持：

- `equals`
- `not_equals`
- `exists`
- `not_exists`
- `contains`
- `number_between`
- `array_size`

断言失败保存结构化 `path/operator/expected/actual/message`。包含 password/passwd/secret/token/apiKey/api_key 语义的断言路径将 expected/actual 保存为 `[REDACTED]`；报告正文转义 Markdown/HTML 关键字符并限制为 10000 字符。

## 4. 数据模型与状态机

Flyway V8 创建：

- `codetrove_assay_execution`
  - `(merge_request_id,head_commit)` 唯一。
  - `check_run_id` 唯一。
  - 保存状态、conclusion、attempt、run_token、lease_until、汇总和时间。
- `codetrove_assay_case_result`
  - `(execution_id,case_key)` 唯一。
  - 保存 source_path、status、failure_code、duration_ms 和结构化 assertion_diff JSON。

Execution 状态：`PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED`。

结论口径：

- 全部启用用例通过：`SUCCESS/ALL_CASES_PASSED`。
- 断言失败：`FAILED/ASSERTION_MISMATCH`。
- Schema、表达式或安全策略失败：`FAILED/CASE_DEFINITION_INVALID`。
- 无用例或全部 disabled：`SKIPPED/NO_ENABLED_CASES`，blocking Check 仍禁止 Merge。
- 旧 head：`CANCELLED/MR_STALE`。
- 目标不可用或执行异常：`FAILED/INFRASTRUCTURE_ERROR`。

## 5. 事件、事务与恢复

1. Check 消费 MR 事件，创建当前 head Suite/Run。
2. 在同一 MySQL 事务写 `assay.execution-requested` 到 `codetrove.assay.commands.v1`。
3. Assay 以 `(consumer_name,event_id)` 登记消费幂等记录。
4. Execution 使用 `run_token + lease_until` claim；重复 command 可在租约条件下重新 claim RUNNING Execution。
5. prepare/start 在短事务完成，HTTP/WireMock 执行在事务外。
6. CaseResult、Execution 终态、幂等 TEST_REPORT 和 completed Outbox 在 finish 短事务原子提交。
7. Assay 写 started/completed 到 `codetrove.assay.results.v1`，Check 只允许当前 head/run 更新。
8. 旧执行者必须携带原 run token 条件回写，无法覆盖重新 claim 后的新终态。

Kafka 与外部 HTTP 仍是至少一次语义；平台消费记录和结果幂等不能把远端 HTTP 副作用描述为 exactly-once。

## 6. API

`GET /api/v1/repositories/{repoId}/merge-requests/{iid}/test-report`

- 复用仓库 READ 权限和私有仓库 404 隔离。
- 只返回当前 head 的 Execution 与 CaseResult。
- 无当前 Execution 时返回 `execution=null,cases=[]`。
- `assertionDiff` 返回真正的结构化 JSON 数组，不是二次编码字符串。

## 7. 自动化验证

最终执行：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

结果：

- bootstrap 70 项。
- curator 7 项。
- assay 5 项。
- 合计 82 项。
- 0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- H2 MySQL 模式从空库成功执行 V1～V8。
- Spring Boot 可执行 JAR 打包成功。

Assay 核心 5 项覆盖：

- 合法 Schema + `data_pre` + context 表达式 + WireMock + 主请求 + 响应断言成功。
- 断言失败返回精确 path/expected/actual。
- 未知字段由 Schema 拒绝。
- 绝对 URL/危险 Header 在执行前被拒绝。
- 重复 `case_key` 被拒绝。

Spring 跨模块 3 项覆盖：

- 真实 JGit 当前 head 用例产生 SUCCESS Execution/CaseResult、幂等 TEST_REPORT 和 blocking SUCCESS Check。
- 断言失败持久化结构化差异并使 blocking Check 为 FAILED。
- 旧 head command 生成 CANCELLED Execution，不产生 CaseResult/报告。
- 同时覆盖 command 重放幂等和当前 head 报告 API。

## 8. Windows 真实环境验收

执行：

```powershell
.\scripts\verify-m4-assay.ps1
```

环境：

- Java 17 / Spring Boot 3.5.7 最终 JAR。
- Windows MySQL 8.0.43 / Flyway V8。
- Docker Redis 7.4.2。
- Docker Apache Kafka 3.9.1 单节点 KRaft。
- Git for Windows。
- WireMock Jetty 12 3.13.2。

八阶段验收：

1. 检查最终 JAR、Readiness、Assay command/result Topic 和 Flyway V8。
2. 真实注册/登录/建库，通过 Git Smart HTTP push passing testcase 并创建 MR。
3. 验证 Kafka command/result、SUCCESS Execution/CaseResult、TEST_REPORT 和 blocking SUCCESS Check。
4. 验证报告 API 与重复 command 幂等。
5. 第二次真实 push 将当前 head 用例改为断言失败。
6. 验证当前 head API 返回结构化 path/expected/actual，blocking Check 为 FAILED。
7. 验证 completed Outbox 已发布、run token 清空且敏感数据未泄露。
8. 输出 `Real M4 Assay declarative HTTP verification passed`，完成零残留清理并恢复后端。

最终脚本退出码 0，八阶段全部通过。

## 9. 依赖问题与修复

- 初次使用 `org.wiremock:wiremock:3.13.2` 时，启动服务器的测试因缺少可用 HttpServerFactory/Jetty 11 失败。
- 调整为 `org.wiremock:wiremock-jetty12:3.13.2`。
- WireMock 依赖 JSON Schema Validator 1.5.9，项目统一版本到 1.5.9，消除依赖收敛冲突。
- HTTP 响应改为 InputStream 有界读取，避免 `ofByteArray` 先完整载入超大响应。
- Git testcase blob 先检查 ObjectLoader 元数据大小，再按上限读取，避免大对象被误分类为仓库存储故障。

WireMock Jetty 12 当前会输出其内部 `CrossOriginFilter` deprecated warning；它不影响测试和实机验收成功，也不是 CodeTrove 编译错误。

## 10. 当前边界

可以真实声明：

- CodeAssay 声明式 HTTP 测试基线/受控 HTTP 执行器。
- 当前 head testcase 发现、严格 Schema、受限表达式、顺序 `data_pre`。
- WireMock 回环动态 Stub、白名单 target/path/header、响应体限制。
- 响应断言、结构化差异、Execution/CaseResult、当前 head 报告 API。
- Kafka command/result、消费幂等、run-token 条件终态、幂等 TEST_REPORT 与 blocking Assay Check。
- 82 项自动化测试与 Windows 八阶段实机验收。

不能声明：

- 任意 MR 分支自动构建、部署或执行。
- 通用代码沙箱或 Docker 隔离已完成。
- DB 断言、Spring Bean Mock、fission、cleanup 已完成。
- 流量录制、AI 自动生成测试用例已完成。
- 外部 HTTP exactly-once。
- 已验证任意外部 application target；当前自动化与实机验收使用进程内 WireMock。
