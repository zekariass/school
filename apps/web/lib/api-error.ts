import { isProblemDetail, type ProblemDetail } from "@school/api-client";

/** A failed API call, carrying the RFC 9457 problem when the server sent one. */
export class ApiRequestError extends Error {
  readonly status: number | undefined;
  readonly problem: ProblemDetail | undefined;

  constructor(status: number | undefined, problem?: ProblemDetail) {
    super(problem?.title ?? `Request failed${status ? ` (${status})` : ""}`);
    this.name = "ApiRequestError";
    this.status = status;
    this.problem = problem;
  }
}

/** Unwrap an openapi-fetch result, throwing ApiRequestError on failure. */
export function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiRequestError(
      result.response.status,
      isProblemDetail(result.error) ? result.error : undefined,
    );
  }
  return result.data;
}

/** React Query retry policy: never retry 4xx problems, retry others twice. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (
    error instanceof ApiRequestError &&
    error.status !== undefined &&
    error.status >= 400 &&
    error.status < 500
  ) {
    return false;
  }
  return failureCount < 2;
}

/** For endpoints answering 204 No Content: throws ApiRequestError unless the call succeeded. */
export function ensureOk(result: { error?: unknown; response: Response }): void {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiRequestError(
      result.response.status,
      isProblemDetail(result.error) ? result.error : undefined,
    );
  }
}
