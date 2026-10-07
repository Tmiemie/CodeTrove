# CodeTrove GitHub 首次公开发布记录

## 1. 发布目标

- 远端仓库：`https://github.com/Tmiemie/CodeTrove`
- 默认分支：`main`
- 可见性：Public
- 许可证：MIT
- 本地仓库：`D:\myDevelop\myProject\CodeTrove`

## 2. 发布前审计

发布前使用 Git 暂存区作为唯一上传清单，完成以下检查：

- 实际发布文件共 218 个。
- `target/`、`node_modules/`、`dist/`、`runtime/`、`.idea/`、`.vscode/` 和真实 `.env` 未进入 Git 索引。
- 暂存文件中没有大于等于 5 MiB 的文件。
- 未发现私钥头、GitHub Token、AWS Access Key、常见 API Key、本机用户绝对路径、项目本机绝对路径或企业邮箱。
- `application-test.yml` 与认证测试中的 `test-only-secret-key-with-at-least-32-bytes` 是固定测试夹具，不是真实凭据。
- `.env.example` 仅包含变量名与替换占位值。
- `backend/mvnw` 在 Git 索引中为 `100755`，可在 GitHub Actions Linux Runner 执行。
- 新增 `.gitattributes` 固定跨平台文本换行策略。

审计中发现根 `.gitignore` 的 `data/` 规则会误忽略 `frontend/src/data/mock.ts`。已改为根目录限定 `/data/`，重新暂存后确认该源码进入 Git 索引；这避免了远端 clone 后前端因缺少模块而构建失败。

## 3. 本机发布验证

发布前最终验证：

- 后端 `clean verify`：bootstrap 70 + curator 7 + assay 5，共 82 项，0 失败、0 错误、0 跳过。
- 全模块 Checkstyle 0 违规。
- H2 MySQL 模式从空库执行 Flyway V1～V8。
- M4 最终 JAR 八阶段 Windows 实机验收通过。
- 前端 Prettier 检查通过。
- 前端 Vue TypeScript 检查通过。
- 前端 Vite 生产构建成功，1787 个模块完成转换。

## 4. Git 发布过程

1. 本地初始化 `main` 分支并创建提交 `feat: release CodeTrove MVP`。
2. 绑定远端 `https://github.com/Tmiemie/CodeTrove.git`。
3. 普通 push 首次被拒绝，因为 GitHub 仓库已有一个仅含 README 的 `Initial commit`。
4. 未使用 force push；先 fetch 并确认远端只有该初始化提交。
5. 使用 `--allow-unrelated-histories` 合并远端历史，并在 README 冲突时保留本地完整版本。
6. 正常 fast-forward 推送到远端 `main`。

发布后的首次远端提交为 `30db1a97a066c3c989ae12e9bc027a713d5cd59f`。本地 `main` 与 `origin/main` 完全一致，远端文件数为 218，关键文件均存在：

- `README.md`
- `README.zh-CN.md`
- `LICENSE`
- `.github/workflows/ci.yml`
- `frontend/src/data/mock.ts`
- `backend/codetrove-bootstrap/src/main/resources/db/migration/V8__create_assay_execution.sql`

## 5. 远端验证

GitHub 仓库页面已验证：

- 仓库显示为 Public。
- README 正常渲染。
- MIT License 可识别。
- 后端、前端、部署、文档与脚本目录均可见。

首次 GitHub Actions 运行：

- Workflow：`CI`
- 触发：push to `main`
- 关联提交：`30db1a9`
- GitHub 页面最终状态：`completed successfully`

这证明首轮远端 CI 成功，但只有一次运行证据，不能夸大为“长期稳定运行”或“生产级 CI 可靠性”。

## 6. 当前发布边界

- 当前发布的是源码、文档、Compose 配置与 CI，不是在线部署的 CodeTrove 服务。
- 前端仍使用演示数据，尚未连接真实后端 API。
- 后端本地运行依赖 MySQL、Redis、Kafka 和环境变量；真实 Secret 未提交。
- 外部 LLM、RAG、Memory、Docker 测试沙箱、DB/Bean Mock、流量录制和 AI 用例生成仍未实现。
- 尚未创建 GitHub Release、版本 Tag、Demo 视频、在线演示站点或生产部署。
