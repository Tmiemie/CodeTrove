export type FileNode = {
  name: string;
  type: "file" | "folder";
  messageKey: string;
  age: { unit: "minute" | "hour" | "day" | "yesterday"; value?: number };
};

export type PullRequestItem = {
  id: number;
  titleKey: string;
  author: string;
  branch: string;
  checks: { passed: number; total: number } | null;
  state: "open" | "merged";
  age: { unit: "minute" | "day" | "yesterday"; value?: number };
};

export type DiffLine = {
  oldNumber?: number;
  newNumber?: number;
  kind: "context" | "add" | "remove" | "header";
  content: string;
  commentKey?: string;
};

export const repositoryFiles: FileNode[] = [
  {
    name: "backend",
    type: "folder",
    messageKey: "repository.fileMessages.backend",
    age: { unit: "hour", value: 2 },
  },
  {
    name: "docs",
    type: "folder",
    messageKey: "repository.fileMessages.docs",
    age: { unit: "yesterday" },
  },
  {
    name: "frontend",
    type: "folder",
    messageKey: "repository.fileMessages.frontend",
    age: { unit: "minute", value: 12 },
  },
  {
    name: ".gitignore",
    type: "file",
    messageKey: "repository.fileMessages.gitignore",
    age: { unit: "hour", value: 3 },
  },
  {
    name: "codetrove-project-design.md",
    type: "file",
    messageKey: "repository.fileMessages.design",
    age: { unit: "yesterday" },
  },
  {
    name: "README.md",
    type: "file",
    messageKey: "repository.fileMessages.readme",
    age: { unit: "hour", value: 3 },
  },
];

export const pullRequests: PullRequestItem[] = [
  {
    id: 24,
    titleKey: "pullRequests.titles.workspace",
    author: "Tmiemie",
    branch: "feature/repository-workspace → main",
    checks: { passed: 2, total: 2 },
    state: "open",
    age: { unit: "minute", value: 12 },
  },
  {
    id: 23,
    titleKey: "pullRequests.titles.acceptance",
    author: "Tmiemie",
    branch: "docs/mvp-gates → main",
    checks: { passed: 2, total: 2 },
    state: "open",
    age: { unit: "yesterday" },
  },
  {
    id: 22,
    titleKey: "pullRequests.titles.modules",
    author: "Tmiemie",
    branch: "chore/backend-bootstrap → main",
    checks: null,
    state: "merged",
    age: { unit: "day", value: 2 },
  },
];

export const diffLines: DiffLine[] = [
  {
    kind: "header",
    content: "@@ -8,10 +8,15 @@ public class RepositoryService {",
  },
  {
    oldNumber: 8,
    newNumber: 8,
    kind: "context",
    content: "    private final RepositoryGateway repositoryGateway;",
  },
  { oldNumber: 9, newNumber: 9, kind: "context", content: "" },
  {
    oldNumber: 10,
    kind: "remove",
    content: "    public Repository find(Long id) {",
  },
  {
    oldNumber: 11,
    kind: "remove",
    content: "        return repositoryGateway.find(id);",
  },
  {
    newNumber: 10,
    kind: "add",
    content: "    public Repository find(RepositoryId id) {",
  },
  {
    newNumber: 11,
    kind: "add",
    content: "        return repositoryGateway.find(id)",
  },
  {
    newNumber: 12,
    kind: "add",
    content:
      "            .orElseThrow(() => new RepositoryNotFoundException(id));",
    commentKey: "pullRequestDetail.diffComments.missing",
  },
  { oldNumber: 12, newNumber: 13, kind: "context", content: "    }" },
  {
    kind: "header",
    content:
      "@@ -35,6 +40,10 @@ public MergeResult merge(MergeCommand command) {",
  },
  {
    oldNumber: 35,
    newNumber: 40,
    kind: "context",
    content: "    permissionService.requireMerge(command.actorId());",
  },
  {
    newNumber: 41,
    kind: "add",
    content: "    checkGate.requirePassed(command.mergeRequestId());",
    commentKey: "pullRequestDetail.diffComments.head",
  },
  {
    newNumber: 42,
    kind: "add",
    content: "    repositoryLock.lock(command.repositoryId());",
  },
  {
    oldNumber: 36,
    newNumber: 43,
    kind: "context",
    content: "    return repositoryGateway.merge(command);",
  },
];

export const testCases = [
  {
    name: "authentication.login.success",
    status: "passed",
    duration: "182 ms",
    categoryKey: "actions.categories.auth",
  },
  {
    name: "repository.create.private",
    status: "passed",
    duration: "246 ms",
    categoryKey: "actions.categories.repository",
  },
  {
    name: "merge-request.stale-head",
    status: "passed",
    duration: "315 ms",
    categoryKey: "actions.categories.mergeGate",
  },
  {
    name: "assay.invalid-schema",
    status: "failed",
    duration: "91 ms",
    categoryKey: "actions.categories.assay",
  },
  {
    name: "curator.duplicate-finding",
    status: "passed",
    duration: "428 ms",
    categoryKey: "actions.categories.curator",
  },
];
