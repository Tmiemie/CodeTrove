import type {
  AssayReport,
  BlobView,
  Branch,
  CheckResponse,
  Comment,
  DiffView,
  LoginResult,
  MergeRequest,
  MergeResult,
  Repository,
  ReviewReport,
  TreePage,
  User,
} from "./types";
import { apiRequest } from "./client";

const json = (value: unknown) => JSON.stringify(value);

export const api = {
  register(username: string, password: string, displayName: string) {
    return apiRequest<User>("/auth/register", {
      method: "POST",
      authenticated: false,
      body: json({ username, password, displayName }),
    });
  },
  login(username: string, password: string) {
    return apiRequest<LoginResult>("/auth/login", {
      method: "POST",
      authenticated: false,
      body: json({ username, password }),
    });
  },
  me() {
    return apiRequest<User>("/users/me");
  },
  repositories() {
    return apiRequest<Repository[]>("/repositories?limit=100");
  },
  createRepository(input: {
    name: string;
    slug: string;
    description: string;
    visibility: "PRIVATE" | "PUBLIC";
    initializeWithReadme: boolean;
  }) {
    return apiRequest<Repository>("/repositories", {
      method: "POST",
      body: json(input),
    });
  },
  repository(id: string) {
    return apiRequest<Repository>(`/repositories/${id}`);
  },
  branches(id: string) {
    return apiRequest<Branch[]>(`/repositories/${id}/branches`);
  },
  tree(id: string, ref: string, path = "") {
    const params = new URLSearchParams({ ref, path, limit: "500" });
    return apiRequest<TreePage>(`/repositories/${id}/tree?${params}`);
  },
  blob(id: string, ref: string, path: string) {
    const params = new URLSearchParams({ ref, path });
    return apiRequest<BlobView>(`/repositories/${id}/blob?${params}`);
  },
  mergeRequests(id: string, status?: string) {
    const params = new URLSearchParams({ limit: "100" });
    if (status) params.set("status", status);
    return apiRequest<MergeRequest[]>(
      `/repositories/${id}/merge-requests?${params}`,
    );
  },
  createMergeRequest(
    id: string,
    input: {
      title: string;
      description: string;
      sourceBranch: string;
      targetBranch: string;
    },
  ) {
    return apiRequest<MergeRequest>(`/repositories/${id}/merge-requests`, {
      method: "POST",
      body: json(input),
    });
  },
  mergeRequest(id: string, iid: number) {
    return apiRequest<MergeRequest>(
      `/repositories/${id}/merge-requests/${iid}`,
    );
  },
  diff(id: string, iid: number) {
    return apiRequest<DiffView>(
      `/repositories/${id}/merge-requests/${iid}/diff`,
    );
  },
  comments(id: string, iid: number) {
    return apiRequest<Comment[]>(
      `/repositories/${id}/merge-requests/${iid}/comments?limit=100`,
    );
  },
  createComment(id: string, iid: number, body: string) {
    return apiRequest<Comment>(
      `/repositories/${id}/merge-requests/${iid}/comments`,
      {
        method: "POST",
        body: json({ body }),
      },
    );
  },
  checks(id: string, iid: number) {
    return apiRequest<CheckResponse>(
      `/repositories/${id}/merge-requests/${iid}/checks?includeHistory=true`,
    );
  },
  findings(id: string, iid: number) {
    return apiRequest<ReviewReport>(
      `/repositories/${id}/merge-requests/${iid}/review-findings`,
    );
  },
  assayReport(id: string, iid: number) {
    return apiRequest<AssayReport>(
      `/repositories/${id}/merge-requests/${iid}/test-report`,
    );
  },
  merge(id: string, iid: number, expectedHeadCommit: string) {
    return apiRequest<MergeResult>(
      `/repositories/${id}/merge-requests/${iid}/merge`,
      {
        method: "POST",
        headers: { "Idempotency-Key": `ui-${iid}-${expectedHeadCommit}` },
        body: json({ expectedHeadCommit, strategy: "MERGE_COMMIT" }),
      },
    );
  },
};
