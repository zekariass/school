import { isProblemDetail, type ProblemDetail } from '@school/api-client';

/** A failed API call, carrying the RFC 9457 problem when the server sent one. */
export class ApiRequestError extends Error {
  readonly status: number | undefined;
  readonly problem: ProblemDetail | undefined;

  constructor(status: number | undefined, problem?: ProblemDetail, options?: { cause?: unknown }) {
    super(problem?.title ?? `Request failed${status ? ` (${status})` : ''}`, options);
    this.name = 'ApiRequestError';
    this.status = status;
    this.problem = problem;
  }
}

/** Unwrap an openapi-fetch result, throwing ApiRequestError on failure. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiRequestError(
      result.response.status,
      isProblemDetail(result.error) ? result.error : undefined,
    );
  }
  return result.data;
}

/** For untyped calls whose success body we don't read: throws ApiRequestError unless 2xx. */
export function assertOk(result: { error?: unknown; response: Response }): void {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiRequestError(
      result.response.status,
      isProblemDetail(result.error) ? result.error : undefined,
    );
  }
}

/**
 * Runs an API call and converts transport failures (offline, DNS, timeout) into an
 * ApiRequestError without a status, so callers handle one error type.
 */
export async function request<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (error) {
    if (error instanceof ApiRequestError) throw error;
    throw new ApiRequestError(undefined, undefined, { cause: error });
  }
}

/** True when the failure is a 4xx response: retrying cannot help. */
export function isClientError(error: unknown): boolean {
  return (
    error instanceof ApiRequestError &&
    error.status !== undefined &&
    error.status >= 400 &&
    error.status < 500
  );
}

/** React Query retry policy: never retry 4xx problems, retry others twice. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (isClientError(error)) return false;
  return failureCount < 2;
}

/** The server-provided (already localized) problem of an error, if any. */
export function problemOf(error: unknown): ProblemDetail | undefined {
  return error instanceof ApiRequestError ? error.problem : undefined;
}
