export type ApiMeta = {
  traceId: string;
  nextCursor?: string | null;
};

export type ApiEnvelope<T> = {
  data: T;
  meta: ApiMeta;
};

export type ApiErrorBody = {
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
    traceId?: string;
  };
};

export type User = {
  id: string;
  username: string;
  displayName: string;
  status: string;
};

export type LoginResult = {
  tokenType: string;
  accessToken: string;
  expiresAt: string;
  user: User;
};

export type Repository = {
  id: string;
  owner: string;
  name: string;
  slug: string;
  description: string | null;
  visibility: "PRIVATE" | "PUBLIC";
  defaultBranch: string;
  status: string;
  currentUserRole: string | null;
  gitHttpUrl: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type Branch = {
  name: string;
  commitId: string;
  commitMessage: string;
  authorName: string;
  authoredAt: string;
  default: boolean;
};

export type TreeEntry = {
  name: string;
  path: string;
  type: "TREE" | "BLOB" | "SYMLINK" | "GITLINK";
  objectId: string;
  size: number | null;
};

export type TreePage = {
  commitId: string;
  path: string;
  entries: TreeEntry[];
  nextCursor?: string | null;
};

export type BlobView = {
  path: string;
  objectId: string;
  size: number;
  binary: boolean;
  contentIncluded: boolean;
  content: string | null;
  encoding: string | null;
  notIncludedReason: string | null;
};

export type Author = {
  id: string;
  username: string;
  displayName: string;
};

export type MergeRequest = {
  id: string;
  iid: number;
  title: string;
  description: string | null;
  sourceBranch: string;
  targetBranch: string;
  baseCommit: string;
  headCommit: string;
  status: "OPEN" | "MERGED" | "CLOSED";
  author: Author;
  mergedBy: string | null;
  mergedAt: string | null;
  mergeCommit: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type DiffFile = {
  status: string;
  oldPath: string | null;
  newPath: string | null;
  additions: number;
  deletions: number;
  binary: boolean;
  patch: string | null;
  truncated: boolean;
};

export type DiffView = {
  baseCommit: string;
  headCommit: string;
  files: DiffFile[];
  truncated: boolean;
};

export type Comment = {
  id: string;
  type: string;
  body: string;
  author: Author | null;
  position: {
    commitId: string;
    filePath: string;
    side: string;
    line: number;
  } | null;
  createdAt: string;
  updatedAt: string;
};

export type CheckRun = {
  id: string;
  checkType: string;
  name: string;
  blocking: boolean;
  status: string;
  conclusion: string | null;
  detailsUrl: string | null;
  attempt: number;
  startedAt: string | null;
  finishedAt: string | null;
};

export type CheckSuite = {
  id: string;
  headCommit: string;
  status: string;
  current: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  runs: CheckRun[];
};

export type CheckResponse = {
  current: CheckSuite | null;
  history: CheckSuite[];
};

export type ReviewFinding = {
  id: string;
  skill: string;
  severity: string;
  ruleId: string;
  filePath: string;
  side: string;
  lineNumber: number;
  title: string;
  message: string;
  evidence: string;
  suggestion: string;
  disposition: string;
};

export type ReviewReport = {
  task: {
    id: string;
    headCommit: string;
    checkRunId: string;
    status: string;
    conclusion: string | null;
    attempt: number;
    findingCount: number;
  } | null;
  findings: ReviewFinding[];
};

export type AssertionDiff = {
  path?: string;
  operator?: string;
  expected?: unknown;
  actual?: unknown;
  message?: string;
};

export type AssayCase = {
  id: string;
  caseKey: string;
  sourcePath: string;
  status: string;
  failureCode: string | null;
  durationMs: number;
  assertionDiff: AssertionDiff[];
};

export type AssayReport = {
  execution: {
    id: string;
    headCommit: string;
    checkRunId: string;
    status: string;
    conclusion: string | null;
    attempt: number;
    totalCount: number;
    passedCount: number;
    failedCount: number;
    skippedCount: number;
  } | null;
  cases: AssayCase[];
};

export type MergeResult = {
  iid: number;
  status: string;
  mergeCommit: string;
  mergedBy: string;
  mergedAt: string;
  idempotentReplay: boolean;
};
