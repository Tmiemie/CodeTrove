# CodeTrove M4.5 前后端真实联调验证记录

## 1. 验收范围

M4.5 将 Vue 工作台的核心业务页面从 `frontend/src/data/mock.ts` 切换为真实 Spring Boot REST API，形成浏览器可操作的产品主链路：

```text
注册/登录 → 创建/选择仓库 → 浏览分支/tree/blob
→ 创建 MR → 查看 Diff/评论 → Curator/Assay/Check → Merge
```

M0～M4 代表后端主链路 MVP；本阶段验收通过后，项目才可描述为“完整可交互产品 MVP”。

## 2. 实现内容

### 2.1 API 与会话层

- `frontend/src/api/types.ts`：统一前后端 DTO 类型，64 位 ID 保持字符串。
- `frontend/src/api/client.ts`：统一 Fetch 客户端、Bearer Token、结构化错误、网络异常和 401 处理。
- `frontend/src/api/index.ts`：封装 Auth、Repository、MR、Diff、Comment、Check、Curator、Assay 和 Merge API。
- `frontend/src/stores/session.ts`：管理真实用户、仓库列表、当前仓库和当前标签页会话。
- Access Token 只保存在 `sessionStorage`，键为 `codetrove.session.token`；当前仓库也只保存在当前标签页。
- Vite `/api` 代理到 `http://127.0.0.1:8080`；API 基础路径可用 `VITE_CODETROVE_API_BASE_URL` 覆盖。
- Git clone 地址默认使用 `http://127.0.0.1:8080`，可用 `VITE_CODETROVE_GIT_BASE_URL` 覆盖。

### 2.2 浏览器业务页面

- 登录/注册：真实调用后端，显示结构化错误；支持中英文切换。
- 应用壳层：显示真实用户、仓库、可见性和当前角色；支持仓库切换和退出。
- 仓库页：真实创建仓库、查询分支、浏览 tree、读取受限 UTF-8 blob、复制 Git Smart HTTP 地址。
- MR 列表：真实查询开放/关闭/已合并 MR，并支持从两个真实分支创建 MR。
- MR 详情：并行读取详情、Diff、评论、Check、Curator Findings 和 Assay Report；支持真实普通评论与 `MERGE_COMMIT`。
- Actions：按 MR 展示当前或最近历史 Check Suite、Curator 和 Assay 结果；没有 rerun API 时只提供刷新并明确边界。
- Settings：只读展示真实仓库元数据、角色和 Git 地址；成员管理、规则修改、归档/删除等无 API 功能不提供伪成功按钮。

## 3. 静态与构建验证

执行：

```powershell
npm --prefix frontend run format
npm --prefix frontend run typecheck
npm --prefix frontend run build
backend\mvnw.cmd -f backend\pom.xml verify
```

结果：

- Prettier 成功。
- `vue-tsc -b` 成功。
- Vite production build 成功，1791 个模块完成转换。
- 后端 Reactor 11 个项目全部成功。
- bootstrap 70 + curator 7 + assay 5，共 82 项测试；0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- H2 MySQL 模式从空库成功执行 Flyway V1～V8。
- Spring Boot 可执行 JAR repackage 成功。
- 第一次在运行态执行 `verify` 时，Windows 文件锁导致 JAR 重命名失败；测试与 Checkstyle 已通过。安全停止 CodeTrove 后端后重跑完整 `verify` 成功，并恢复后端 Readiness。
- WireMock Jetty 12 的内部 `CrossOriginFilter` deprecated warning 仍存在，不是 CodeTrove 编译错误。

## 4. Windows 浏览器真实链路

环境：

- Windows MySQL 8.0.43。
- Docker Redis 7.4.2 与 Kafka 3.9.1 单节点 KRaft，容器均 healthy。
- Java 17 / Spring Boot 3.5.7 最终 JAR。
- Vue 3 / Vite 开发服务器。

实际验证：

1. 打开登录页，无白屏；中英文登录界面可切换。
2. 注册并登录真实账号，进入工作台后显示真实用户。
3. 创建私有仓库，后端返回真实 owner/name/role/Git URL，并初始化 `main` 和 README。
4. 仓库页读取真实分支、tree 和 README blob 内容。
5. 创建 MR #1，真实显示 1 个 Diff 文件；提交普通评论后刷新仍存在。
6. MR #1 无启用 Assay 用例，得到 `SKIPPED/NO_ENABLED_CASES`，Merge 正确保持阻塞。
7. 创建带有效 `testcases/m45-browser.json` 的 `feature/m45-pass` 分支和 MR #2。
8. MR #2 得到：
   - `curator.review = SUCCESS / NO_FINDINGS`
   - `assay.integration = SUCCESS / ALL_CASES_PASSED`
   - Assay case `m45.browser.success = PASSED`
9. 发现并修复前端门禁误把 `conclusion` 当成固定 `SUCCESS` 的问题；正确契约是 blocking Run 的 `status=SUCCESS`，conclusion 保留业务原因。
10. 修正后浏览器显示“阻塞检查已通过”，Merge 按钮启用；点击后后端真实返回并刷新为 `MERGED`。
11. Actions 页对已合并 MR 使用 `current ?? history[0]` 展示 Suite，避免合并后错误显示 `NOT_STARTED`。
12. Settings 页面只有只读信息和明确未实现列表，无伪保存/伪归档操作。
13. 中英文切换在登录页和工作台新增页面均生效。
14. 15 分钟 JWT 到期时，后端 401 会清理 `sessionStorage` 会话并跳转登录页；过期时的写请求没有伪造成功。

## 5. 当前边界

- 当前没有 Refresh Token、Token 撤销、登录限流或 Cookie 会话。
- 当前没有成员管理、仓库更新/归档/删除、规则编辑、Check rerun 或 Finding disposition 写 API。
- MR 的 feature 分支仍需通过 Git Smart HTTP 推送；浏览器不编辑或上传任意代码。
- CodeCurator 仍是确定性静态评审基线，不是外部 LLM 评审。
- CodeAssay 仍是受控声明式 HTTP 执行器，不构建/部署任意 MR 代码。
- 本阶段没有新增后端自动化测试数量，后端回归仍为 82 项；新增证据来自前端静态检查、生产构建和 Windows 浏览器真实链路。

## 6. 结论

M4.5 通过。CodeTrove 已完成可交互产品 MVP：核心浏览器页面以真实 REST API 为事实源，能够完成登录、仓库、MR、Diff、评论、质量报告与门禁合并闭环；无后端能力的入口已隐藏或明确为只读边界。
