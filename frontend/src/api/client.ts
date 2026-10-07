import type { ApiEnvelope, ApiErrorBody } from "./types";

const TOKEN_KEY = "codetrove.session.token";
const API_BASE = (
  import.meta.env.VITE_CODETROVE_API_BASE_URL || "/api/v1"
).replace(/\/$/, "");

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;
  readonly traceId?: string;

  constructor(
    status: number,
    code: string,
    message: string,
    details: Record<string, unknown> = {},
    traceId?: string,
  ) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
    this.details = details;
    this.traceId = traceId;
  }
}

export function readSessionToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function writeSessionToken(token: string | null) {
  if (token) sessionStorage.setItem(TOKEN_KEY, token);
  else sessionStorage.removeItem(TOKEN_KEY);
}

export async function apiRequest<T>(
  path: string,
  options: RequestInit & { authenticated?: boolean } = {},
): Promise<ApiEnvelope<T>> {
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const authenticated = options.authenticated !== false;
  const token = readSessionToken();
  if (authenticated && token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  } catch {
    throw new ApiClientError(
      0,
      "NETWORK_UNAVAILABLE",
      "Cannot reach the CodeTrove backend",
    );
  }

  const text = await response.text();
  const payload = text
    ? (JSON.parse(text) as ApiEnvelope<T> | ApiErrorBody)
    : null;
  if (!response.ok) {
    const error = (payload as ApiErrorBody | null)?.error;
    if (response.status === 401) {
      writeSessionToken(null);
      window.dispatchEvent(new CustomEvent("codetrove:unauthorized"));
    }
    throw new ApiClientError(
      response.status,
      error?.code ?? `HTTP_${response.status}`,
      error?.message ?? response.statusText,
      error?.details ?? {},
      error?.traceId ?? response.headers.get("X-Trace-Id") ?? undefined,
    );
  }
  return payload as ApiEnvelope<T>;
}

export function errorMessage(error: unknown) {
  if (error instanceof ApiClientError) {
    return error.traceId
      ? `${error.message} · ${error.code} · ${error.traceId}`
      : `${error.message} · ${error.code}`;
  }
  return error instanceof Error ? error.message : "Unexpected request failure";
}
