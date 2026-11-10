import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

export type Schemas = components["schemas"];
export type Todo = Schemas["TodoResponse"];
export type TodoRequest = Schemas["TodoRequest"];
export type TodoPage = Schemas["TodoPageResponse"];
export type TodoStats = Schemas["TodoStatsResponse"];
export type Priority = Schemas["Priority"];
export type SortField = Schemas["SortField"];
export type SortOrder = Schemas["SortOrder"];
export type User = Schemas["UserResponse"];
export type ErrorResponse = Schemas["ErrorResponse"];

/** An error returned by the API, carrying the server's structured error body. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ErrorResponse,
  ) {
    super(body.message);
    this.name = "ApiError";
  }

  /** Field-level validation messages, keyed by field name. */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries(this.body.fieldErrors.map((e) => [e.field, e.message]));
  }
}

/** Called when the server says the session is missing or expired, so the app can sign out. */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

const sessionExpiry: Middleware = {
  onResponse({ request, response }) {
    const isSignIn = new URL(request.url).pathname.startsWith("/api/auth/");
    if (response.status === 401 && !isSignIn) onUnauthorized();
    return response;
  },
};

/**
 * A fully typed API client. Paths, parameters, request bodies and responses are all checked
 * against `schema.ts`, which is generated from the backend's OpenAPI spec.
 *
 * There is no token handling here on purpose: the session lives in an HttpOnly cookie that
 * JavaScript can't read, and the browser attaches it to same-origin `/api` requests itself.
 */
export const client = createClient<paths>({
  baseUrl: globalThis.location?.origin ?? "",
  credentials: "same-origin",
  // Look `fetch` up on every call (rather than once at startup) so tests can stub it.
  fetch: (request) => globalThis.fetch(request),
});
client.use(sessionExpiry);

/** Unwraps an openapi-fetch result, turning error responses into an `ApiError`. */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await call;
  if (response.ok) return data as T;
  const body = isErrorResponse(error)
    ? error
    : { code: "internal_error" as const, message: response.statusText || "Request failed", fieldErrors: [] };
  throw new ApiError(response.status, body);
}

function isErrorResponse(value: unknown): value is ErrorResponse {
  return typeof value === "object" && value !== null && "code" in value && "message" in value;
}
