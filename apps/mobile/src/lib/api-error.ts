import { isProblemDetail, type ProblemDetail } from '@school/api-client';

/**
 * `Retry-After` as whole seconds (at least 1): either delta-seconds or an HTTP date.
 * Undefined when absent or unreadable.
 */
export function parseRetryAfter(header: string | null | undefined, nowMs: number = Date.now()): number | undefined {
  const value = header?.trim();
  if (!value) return undefined;
  if (/^\d+$/.test(value)) return Math.max(1, Number(value));
  const date = Date.parse(value);
  if (Number.isNaN(date)) return undefined;
  return Math.max(1, Math.ceil((date - nowMs) / 1000));
}

function retryAfterOf(response: Response): number | undefined {
  return parseRetryAfter(response.headers?.get('Retry-After'));
}

/** A failed API call, carrying the RFC 9457 problem when the server sent one. */
export class ApiRequestError extends Error {
  readonly status: number | undefined;
  readonly problem: ProblemDetail | undefined;
  /** Seconds to wait before retrying (`Retry-After`, sent with 429), when the server said so. */
  readonly retryAfterSeconds: number | undefined;

  constructor(
    status: number | undefined,
    problem?: ProblemDetail,
    options?: { cause?: unknown; retryAfterSeconds?: number | undefined },
  ) {
    super(problem?.title ?? `Request failed${status ? ` (${status})` : ''}`, options);
    this.name = 'ApiRequestError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = options?.retryAfterSeconds;
  }
}

/** Unwrap an openapi-fetch result, throwing ApiRequestError on failure. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiRequestError(
      result.response.status,
      isProblemDetail(result.error) ? result.error : undefined,
      { retryAfterSeconds: retryAfterOf(result.response) },
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
      { retryAfterSeconds: retryAfterOf(result.response) },
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
