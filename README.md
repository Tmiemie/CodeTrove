**English** | [简体中文](README.zh-CN.md)

# CodeTrove

CodeTrove is an intelligent code collaboration and quality assurance platform. It connects repository collaboration, AI-assisted review, declarative integration tests, and merge checks in one workflow.

> Current status: M0, M1.1–M1.6, M2, M3, and the M4 CodeAssay declarative HTTP baseline are complete. CodeTrove now includes authentication, repository authorization, Git Smart HTTP, merge requests, conflict-safe/idempotent merge commits, Transactional Outbox, Kafka idempotency/DLQ, current-head Check gating, deterministic logic/security review, and a controlled HTTP test executor with schema validation, data preparation, WireMock, response assertions, structured reports, and blocking Assay checks. External LLM/RAG/Memory, arbitrary MR build/deployment, Docker sandboxing, DB/Bean Mock assertions, traffic recording, and AI-generated cases remain future work.

## Current capabilities

- Java 17 and Spring Boot 3.5 modular backend
- Vue 3, TypeScript, and Vite frontend
- Complete English `en-US` / Simplified Chinese `zh-CN` interface with persisted language preference
- Windows MySQL integration with Flyway migrations
- Redis 7.4 and Apache Kafka 3.9 in Docker; Redis uses authentication/AOF and Kafka uses single-node KRaft for local development
- Liveness and readiness health groups, with MySQL, Redis, and Kafka included in readiness
- Unified API success/error responses and `X-Trace-Id`
- Username/password registration, BCrypt password hashing, and 15-minute JWT access tokens
- Repository create/list/detail APIs, four-role authorization, and JGit bare repository initialization
- Git Smart HTTP clone/fetch/push with Basic Auth, repository authorization, protected `main`, and resource limits
- Authenticated branch metadata, paginated Git tree browsing, and size-limited UTF-8 blob reading
- Merge request create/list/detail/update/close APIs with repository-local iid and optimistic locking
- Snapshot-based JGit diffs with binary and patch-size limits, plus validated general and line comments
- Successful source-branch pushes update matching open MR heads and append deduplicated commit history
- Repository-serialized `MERGE_COMMIT` with three-way conflict detection, expected-head validation, target-ref CAS, idempotent replay, and pending-operation recovery
- Transactional Outbox for MR lifecycle events, Kafka at-least-once delivery, database consumer idempotency, bounded retries, and a shared DLQ
- Per-head Check Suites/Runs: stale heads become historical, blocking checks gate Merge, and non-blocking checks remain advisory
- Deterministic CodeCurator baseline with logic/security Review Skills, real parallel execution, Judge validation/deduplication, ReviewTask/Finding persistence, and idempotent system line comments
- Resilience4j Retry/CircuitBreaker/SemaphoreBulkhead, bounded executors, per-Skill timeouts, and explicit `SKIPPED/SKILL_UNAVAILABLE` degradation
- Current-head Review Findings API with repository READ authorization and skill/severity/disposition filters
- Current-head CodeAssay declarative HTTP baseline: Draft 2020-12 strict schema, controlled expressions and `data_pre`, loopback WireMock, response assertions, structured diffs, Execution/CaseResult persistence, and idempotent TEST_REPORT comments
- Kafka-driven `assay.execution-requested/started/completed`, database consumer idempotency, run-token lease recovery, and a blocking Assay Check that only passes when enabled cases all pass
- CodeAssay is intentionally not a general sandbox: it does not build/deploy arbitrary MR code and does not yet provide Docker isolation, DB assertions, Spring Bean Mock, fission, cleanup, traffic recording, or AI-generated cases
- Kafka topics: `codetrove.mr.events.v1`, `codetrove.curator.commands.v1`, `codetrove.curator.results.v1`, `codetrove.assay.commands.v1`, `codetrove.assay.results.v1`, and `codetrove.dlq.v1`
- GitHub Actions for backend tests and frontend checks
- Blue-purple-pink dashboard interface with a restrained cat-curator identity

## Repository

- GitHub: `https://github.com/Tmiemie/CodeTrove`
- Visibility: public
- License: MIT

## Project layout

```text
CodeTrove/
├── backend/             Maven modular backend
├── frontend/            Vue 3 frontend
├── deploy/              Docker Compose files
├── docs/                Architecture and contracts
├── scripts/             Windows local scripts
└── .github/workflows/   CI
```

## Prerequisites

- Windows 10/11
- Java 17
- Docker Desktop
- Node.js 22 and npm
- Git
- MySQL 8 if using the recommended local Windows MySQL workflow

The backend uses Maven Wrapper, so a separate Maven installation is optional.

## Local configuration

Copy variable names from `.env.example`, but do not commit real values.

Required Windows user environment variables:

```text
CODETROVE_DB_URL
CODETROVE_DB_USERNAME
CODETROVE_DB_PASSWORD
CODETROVE_REDIS_HOST
CODETROVE_REDIS_PORT
CODETROVE_REDIS_PASSWORD
CODETROVE_KAFKA_PORT
CODETROVE_KAFKA_BOOTSTRAP_SERVERS
CODETROVE_EVENTING_ENABLED
CODETROVE_CHECK_GATE_ENABLED
CODETROVE_OUTBOX_BATCH_SIZE
CODETROVE_OUTBOX_MAX_ATTEMPTS
CODETROVE_OUTBOX_POLL_INTERVAL_MS
CODETROVE_KAFKA_SEND_TIMEOUT
CODETROVE_CHECK_CONSUMER_GROUP
CODETROVE_CURATOR_CONSUMER_GROUP
CODETROVE_CURATOR_ENABLED
CODETROVE_CURATOR_PARALLELISM
CODETROVE_CURATOR_SKILL_TIMEOUT
CODETROVE_CURATOR_MAX_ATTEMPTS
CODETROVE_ASSAY_CONSUMER_GROUP
CODETROVE_ASSAY_ENABLED
CODETROVE_ASSAY_TARGET_BASE_URL
CODETROVE_JWT_SECRET
CODETROVE_NODE_ID
CODETROVE_REPOSITORY_ROOT
CODETROVE_REPOSITORY_MAX_INLINE_BLOB_BYTES
CODETROVE_MR_MAX_DIFF_FILES
CODETROVE_MR_MAX_FILE_PATCH_BYTES
CODETROVE_MR_MAX_TOTAL_PATCH_BYTES
CODETROVE_GIT_TIMEOUT_SECONDS
CODETROVE_GIT_MAX_REQUEST_BYTES
CODETROVE_GIT_MAX_COMMAND_BYTES
CODETROVE_GIT_MAX_OBJECT_BYTES
CODETROVE_GIT_MAX_PACK_BYTES
CODETROVE_GIT_MAX_CONCURRENT_REQUESTS
```

Recommended local database URL:

```text
jdbc:mysql://127.0.0.1:3306/codetrove?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
```

Use a dedicated database and a low-privilege account restricted to `codetrove.*`. Do not use the MySQL root account in the application.

After creating or changing Windows user environment variables, restart the terminal or IDE so new processes inherit them.

## Start locally

### 1. Start Redis and Kafka

From the project root in PowerShell:

```powershell
$env:CODETROVE_REDIS_PASSWORD = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PASSWORD', 'User')
$env:CODETROVE_REDIS_PORT = [Environment]::GetEnvironmentVariable('CODETROVE_REDIS_PORT', 'User')
docker compose -f deploy/compose.yml up -d redis kafka
```

### 2. Build the backend

```powershell
cd backend
.\mvnw.cmd clean verify
cd ..
```

### 3. Start the backend

The helper script loads the configured Windows user variables:

```powershell
.\scripts\start-local.ps1 -SkipFrontend
```

Backend endpoints:

- `http://127.0.0.1:8080/actuator/health/liveness`
- `http://127.0.0.1:8080/actuator/health/readiness`
- `http://127.0.0.1:8080/api/v1/platform/status`

### 4. Start the frontend

```powershell
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1 --port 28741
```

Frontend URL:

- `http://127.0.0.1:28741`

The current frontend uses demo data. Backend API integration is still pending.

## Git Smart HTTP

Create a repository through the REST API, then use its returned Git URL:

```powershell
git clone http://127.0.0.1:8080/git/<owner>/<repo>.git
```

Git prompts for the CodeTrove username and password through Basic Auth. Do not put real credentials in committed scripts, shell history, or remote URLs. The local endpoint is HTTP-only for development; production deployment requires TLS termination.

Current Git behavior:

- authenticated users with `READ` can clone and fetch;
- `OWNER`, `MAINTAINER`, and `DEVELOPER` can push ordinary branches;
- `REPORTER` cannot push;
- direct create/update/delete of `main` is rejected with `protected branch requires merge request`;
- non-fast-forward pushes are rejected;
- request, command, object, pack, timeout, and concurrency limits are configurable through the `CODETROVE_GIT_*` variables.

## Optional Docker MySQL for other contributors

The maintainer workflow uses an existing Windows MySQL instance. Contributors without MySQL can use the optional Docker MySQL file.

Set these additional variables in the current terminal or an untracked `.env` file:

```text
CODETROVE_MYSQL_ROOT_PASSWORD=<strong-local-root-password>
CODETROVE_DB_PASSWORD=<strong-local-app-password>
CODETROVE_MYSQL_PORT=3307
```

Start Redis, Kafka, and MySQL:

```powershell
docker compose -f deploy/compose.yml -f deploy/compose.with-mysql.yml up -d
```

Use this database URL:

```text
jdbc:mysql://127.0.0.1:3307/codetrove?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
```

This optional MySQL container is not started by the maintainer's default Compose command.

## Verification

With Redis, Kafka, and the backend running:

```powershell
.\scripts\verify-m0.ps1
.\scripts\verify-m1-git.ps1
.\scripts\verify-m1-browse.ps1
.\scripts\verify-m1-merge-request.ps1
.\scripts\verify-m1-merge.ps1
.\scripts\verify-m2-event-check.ps1
.\scripts\verify-m3-curator.ps1
.\scripts\verify-m4-assay.ps1
```

`verify-m0.ps1` verifies:

- all backend modules and tests
- frontend formatting, type checks, and production build
- Docker Compose syntax
- Redis container health
- backend liveness/readiness
- API response and Trace ID propagation

`verify-m1-git.ps1` uses random temporary users and a private repository to verify real clone, ordinary-branch push/fetch, protected `main`, Reporter push denial, cleanup, and backend recovery against Windows MySQL, Docker Redis, the final JAR, and the system Git client.

`verify-m1-browse.ps1` pushes a real feature branch containing nested source files, binary data, and an oversized text file, then verifies the branches/tree/blob REST APIs, invalid ref/path handling, private isolation, cleanup, and backend recovery.

`verify-m1-merge-request.ps1` performs a real Git Smart HTTP feature push, creates and updates an MR, verifies snapshot-based diff and general/line comments, rejects invalid positions and stale versions, closes the MR, cleans all temporary records and repositories, and restores the backend.

`verify-m1-merge.ps1` creates an MR, performs a second real HTTP push to verify head/history synchronization, satisfies the isolated M2 blocking-check prerequisite, merges through the REST API, verifies the two-parent commit and remote `main`, replays the idempotency key, checks database terminal state, removes all temporary data, and restores the backend.

`verify-m2-event-check.ps1` validates the real Outbox → Kafka → Check → Merge path. It stops Kafka during a real push, verifies a committed MR head plus PENDING Outbox and DOWN readiness, restarts Kafka, verifies eventual publication and stale-suite cancellation, checks merge rejection/approval, duplicate result idempotency, invalid-schema DLQ delivery, zero-residue cleanup, and backend recovery. DLQ verification starts from the topic end offset recorded for the current run, so retained historical messages do not break repeat runs.

`verify-m3-curator.ps1` pushes fixed logic/security defects through real Git Smart HTTP and validates the Kafka command/result flow, parallel deterministic Skills, Judge-approved locations, two Review Findings, idempotent system line comments, credential-value redaction, duplicate-command idempotency, clean-head isolation, non-blocking Curator Check state, zero-residue cleanup, and backend recovery.

`verify-m4-assay.ps1` pushes a passing declarative case and then a failing case through real Git Smart HTTP. It validates current-head testcase discovery, Assay command/result topics, Execution/CaseResult persistence, `data_pre`, loopback WireMock, structured assertion diffs, idempotent TEST_REPORT comments, duplicate-command handling, blocking SUCCESS/FAILED semantics, run-token cleanup, sensitive-value checks, zero-residue cleanup, and backend recovery.

## Stop locally

```powershell
.\scripts\stop-local.ps1
```

The Redis and Kafka data volumes are preserved. Removing volumes is intentionally not part of the stop script.

## CI

GitHub Actions runs:

- Java 17 backend `clean verify`
- frontend `npm ci`
- Prettier format check
- Vue TypeScript check
- Vite production build

Third-party Actions are pinned to immutable commit SHAs. The first public `main` run completed successfully; see `docs/22-github-release-record.md`. This is one verified run, not a claim of long-term CI stability.

## Security notes

Never commit:

- `.env` files
- database, Redis, JWT, or LLM secrets
- runtime data and logs
- bare Git repositories
- IDE workspace files
- `node_modules`, `dist`, or Maven `target` directories

The first public GitHub release completed the secret, privacy, license, generated-file, and large-file review. Repeat the same review before future releases.

## Documentation

Start with:

- `codetrove-project-design.md`
- `docs/01-scope-and-milestones.md`
- `docs/02-architecture.md`
- `docs/04-api-contract.md`
- `docs/08-acceptance-checklist.md`
- `docs/09-frontend-design.md`
- `docs/10-m0-verification-record.md`
- `docs/11-i18n.md`
- `docs/12-m1-auth-verification-record.md`
- `docs/13-resume-feature-ledger.md` — feature-level implementation evidence and resume-ready wording
- `docs/14-m1-repository-verification-record.md`
- `docs/15-m1-git-smart-http-verification-record.md`
- `docs/16-m1-repository-browse-verification-record.md`
- `docs/17-m1-merge-request-verification-record.md`
- `docs/18-m1-merge-verification-record.md`
- `docs/19-m2-event-check-verification-record.md`
- `docs/20-m3-curator-verification-record.md`
- `docs/21-m4-assay-verification-record.md`
- `docs/22-github-release-record.md`

Every independently accepted feature must update the resume feature ledger together with its implementation and verification documents.

## License

Released under the [MIT License](LICENSE).
