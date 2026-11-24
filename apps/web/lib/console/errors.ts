import { ApiRequestError } from "@/lib/api-error";

/** The message to show for a failed call: the API's localized detail, else `fallback`. */
export function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiRequestError && error.problem) {
    return error.problem.detail ?? error.problem.title ?? fallback;
  }
  return fallback;
}

/**
 * Per-field messages of a `validation_failed` problem, keyed by the field's last
 * path segment ("scope.countryCode" gives "countryCode").
 */
export function fieldErrors(error: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (error instanceof ApiRequestError) {
    for (const e of error.problem?.errors ?? []) {
      const key = e.field.split(".").pop() ?? e.field;
      out[key] ??= e.message;
    }
  }
  return out;
}

/** True for a 409 problem with one of these codes (e.g. a taken slug). */
export function hasProblemCode(error: unknown, ...codes: string[]): boolean {
  return error instanceof ApiRequestError && !!error.problem && codes.includes(error.problem.code);
}
