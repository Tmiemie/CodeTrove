# CodeAssay 声明式用例规范

## 1. 设计目标

- 用版本化 JSON 描述准备、执行、Mock 和断言。
- 同一用例在相同环境下结果可重复。
- 表达式功能受控，不允许执行任意代码。
- AI 或流量生成的用例先成为候选，人工批准后才能参与阻塞型回归。

## 2. 文件位置与命名

- 仓库默认目录：`testcases/**/*.json`。
- 文件名使用小写字母、数字、短横线和点。
- `case_key` 在仓库内唯一，建议使用点分命名：`payment.refund.full.success`。
- 文件编码 UTF-8，单文件大小和展开后 case 数量设上限。

## 3. 顶层结构 v1

```json
{
  "schema_version": "1.0",
  "case_key": "payment.refund.full.success",
  "description": "支付宝渠道全额退款成功",
  "tags": ["payment", "refund", "smoke"],
  "enabled": true,
  "timeout_ms": 10000,
  "data_pre": [],
  "request": {},
  "mocks": [],
  "assertions": [],
  "fission": {},
  "cleanup": []
}
```

必填：`schema_version`、`case_key`、`description`、`request`、`assertions`。

未知字段默认拒绝，避免拼写错误被静默忽略。

## 4. 表达式语言

### 4.1 允许形式

- 变量引用：`${context.tradeOrder.tradeNo}`。
- 当前裂变参数：`${matrix.channel}`。
- 内置函数：`${#uuid()}`、`${#randomLong(1,999999)}`、`${#now()}`。
- 字符串内插：`refund-${#uuid()}`。

### 4.2 解析规则

- 路径不存在立即失败，不返回 null 继续执行。
- 类型在渲染后仍保持目标字段声明类型；禁止隐式把复杂对象转字符串。
- 单步输出只能访问已完成步骤。
- 表达式最大深度、字符串长度和函数调用次数有限制。

### 4.3 禁止能力

- 任意 Java/Groovy/JavaScript 执行。
- 反射、文件系统访问、进程启动、网络访问。
- 用户自定义类加载。
- 动态 SQL 拼接。

## 5. data_pre

```json
{
  "key": "pay-success",
  "type": "http",
  "request": {
    "method": "POST",
    "path": "/api/pay",
    "headers": { "Content-Type": "application/json" },
    "body": { "channel": "${matrix.channel}", "amount": 100 }
  },
  "save_as": "tradeOrder",
  "extract": {
    "tradeNo": "$.body.tradeNo",
    "amount": "$.body.amount"
  }
}
```

- 按数组顺序串行执行。
- `save_as` 不得覆盖保留命名空间。
- 每步有独立超时，失败后默认停止主步骤。

## 6. 主请求

```json
{
  "request": {
    "type": "http",
    "method": "POST",
    "path": "/api/refunds",
    "headers": { "Content-Type": "application/json" },
    "body": {
      "tradeNo": "${context.tradeOrder.tradeNo}",
      "refundAmount": 100,
      "outRefundNo": "${#uuid()}"
    }
  }
}
```

安全约束：

- `path` 必须为相对路径。
- 目标 host 由受控环境配置决定，用例不得指定任意外网 host。
- Header 白名单不允许 Authorization 等真实凭据；测试身份由环境注入。

## 7. Mock

### 7.1 HTTP Mock

```json
{
  "id": "channel-refund",
  "type": "http",
  "match": {
    "method": "POST",
    "path": "/channel/refund",
    "json_body": {
      "$.amount": 100
    }
  },
  "respond": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "body": {
      "channelRefundNo": "${#uuid()}",
      "status": "SUCCESS"
    }
  },
  "expect_calls": { "min": 1, "max": 1 }
}
```

首版由 WireMock 承载，匹配规则编译为受控 Stub。

### 7.2 Spring Bean Mock

```json
{
  "id": "risk-client",
  "type": "spring_bean",
  "target": "riskClient.check",
  "match": { "args[0].amount": 100 },
  "return": { "allowed": true },
  "expect_calls": { "min": 1, "max": 1 }
}
```

仅支持已注册白名单接口和方法。不能通过配置调用任意 Bean 或表达式。

## 8. Assertions

### 8.1 响应断言

```json
{
  "type": "response",
  "operator": "equals",
  "path": "$.body.success",
  "expected": true
}
```

建议运算符：`equals`、`not_equals`、`exists`、`not_exists`、`contains`、`matches_safe_regex`、`number_between`、`array_size`。

正则必须使用有超时或线性复杂度保证的实现，防止 ReDoS。

### 8.2 DB 断言

```json
{
  "type": "database",
  "datasource": "app",
  "query_id": "refund_order.by_out_refund_no",
  "params": { "outRefundNo": "${request.body.outRefundNo}" },
  "expected": {
    "row_count": 1,
    "fields": {
      "status": "SUCCESS",
      "refund_amount": 100
    }
  }
}
```

- 用例只能引用预注册 `query_id`，不得提交任意 SQL。
- 数据源账号只读；需要写入时通过受控准备步骤完成。

### 8.3 Mock 调用断言

通过 `expect_calls` 和 `match` 校验调用次数及参数。

## 9. Fission 用例裂变

```json
{
  "fission": {
    "strategy": "cartesian",
    "dimensions": {
      "channel": ["alipay", "wechat", "bank"],
      "refundAmount": [100, 50, 1]
    },
    "max_cases": 20
  }
}
```

- 默认笛卡尔积。
- 展开前计算数量，超过系统上限立即拒绝。
- 单个裂变实例 ID 为 `case_key + 参数规范化哈希`。
- 报告必须显示该实例的完整参数。

## 10. Cleanup

- 默认在每个用例后执行，主步骤失败也执行。
- Cleanup 失败单独记录，不覆盖主失败原因。
- 只允许受控 HTTP 操作或注册的数据清理动作。

## 11. 执行状态

单用例阶段：

`VALIDATING -> PREPARING -> MOCKING -> EXECUTING -> ASSERTING -> CLEANING -> PASSED/FAILED/ERROR/SKIPPED`

失败分类：

- `SCHEMA_INVALID`
- `EXPRESSION_ERROR`
- `PRECONDITION_FAILED`
- `MOCK_SETUP_FAILED`
- `REQUEST_FAILED`
- `ASSERTION_MISMATCH`
- `CLEANUP_FAILED`
- `TIMEOUT`
- `INFRASTRUCTURE_ERROR`
- `SECURITY_POLICY_VIOLATION`

## 12. 断言差异格式

```json
{
  "assertion_index": 0,
  "type": "response",
  "path": "$.body.status",
  "operator": "equals",
  "expected": "SUCCESS",
  "actual": "FAILED",
  "message": "Value mismatch"
}
```

敏感字段在报告中继续脱敏；实际大正文只保存哈希和受控引用。

## 13. AI 生成治理

1. LLM 输出只能进入 `DRAFT`。
2. 先做 JSON 解析和 Schema 校验。
3. 再做表达式、目标地址、Mock 白名单和数据安全检查。
4. 由 MAINTAINER 审核并批准。
5. 批准后的内容写回仓库，进入正常代码评审流程。
6. AI 不得直接修改生产用例或绕过 Git 历史。

## 14. 版本兼容

- `1.x` 只允许向后兼容新增。
- 执行器明确列出支持的 Schema 版本。
- 不支持的版本在排队前失败。
- 提供独立迁移器，不在运行时静默改写用户文件。

## 15. M4 可执行子集与边界

M4 首个可运行版本只接受以下 v1 子集；Schema 中未声明的字段继续按顶层和各子对象 `additionalProperties=false` 拒绝：

- 用例从 MR 当前 `head_commit` 的 `testcases/**/*.json` 读取；单次最多 50 个文件，单文件最多 256 KiB，总计最多 2 MiB。
- 顶层支持 `schema_version`、`case_key`、`description`、`enabled`、`timeout_ms`、`data_pre`、`request`、`mocks`、`assertions`。
- 请求支持受控 `application` 与 `mock` 两种 target。`application` 的 base URL 仅由服务端 `CODETROVE_ASSAY_TARGET_BASE_URL` 配置；`mock` 指向本次执行的进程内 WireMock。
- 请求 path 必须是以 `/` 开头的相对 HTTP 路径，不允许 scheme、host、反斜杠、NUL 或 `..` 路径段。
- Header 仅允许 `Accept`、`Content-Type`、`X-CodeTrove-Test-Case`；Authorization/Cookie/Host 等由配置指定也会被拒绝。
- M4 表达式只开放 `${context.<save_as>.<field>}`、`${#uuid()}`、`${#randomLong(min,max)}`；禁止反射、脚本、文件、进程与任意网络访问。
- `data_pre` 只支持顺序 HTTP 步骤和受限 `$.status`、`$.body.<field...>` 提取。
- HTTP Mock 由 WireMock 承载，支持 method/path、固定 JSON 响应和 `expect_calls.min/max`；Spring Bean Mock 留到 M5。
- 响应断言首版支持 `equals`、`not_equals`、`exists`、`not_exists`、`contains`、`number_between`、`array_size`；失败差异必须保存 path/operator/expected/actual。
- `fission`、`cleanup`、DB 断言、Bean Mock、Mock 参数 JSONPath、Docker 沙箱和自动构建/部署 MR 代码尚未进入 M4，出现时由 Schema 拒绝而不是静默忽略。

Check 语义：

- 至少一个启用用例且全部通过：`SUCCESS/ALL_CASES_PASSED`。
- 断言失败：`FAILED/ASSERTION_MISMATCH`。
- Schema、表达式或安全策略失败：`FAILED/CASE_DEFINITION_INVALID`。
- 没有 `testcases/**/*.json` 或全部 disabled：`SKIPPED/NO_ENABLED_CASES`；由于 Assay 是 blocking Check，该状态仍禁止 Merge。
- 旧 head：`CANCELLED/MR_STALE`。
- 受控测试目标不可用或执行器异常：`FAILED/INFRASTRUCTURE_ERROR`。
