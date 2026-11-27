import { ApiRequestError } from "@/lib/api-error";

/**
 * How a failed call is shown. A 404 is "nothing here": the API answers 404 to everybody who may not see a case or an
 * incident (administrators and platform administrators included, for incidents), so a screen never says "you may not
 * see this" and never hints that the thing exists. Every other failure is shown inline with the API's own sentence.
 */
export type Outcome =
  | { kind: "nothing" }
  | { kind: "forbidden"; detail?: string }
  | { kind: "rateLimited"; retryAfter?: number; detail?: string }
  | { kind: "conflict"; detail?: string }
  | { kind: "failed"; detail?: string };

export function outcomeOf(error: unknown): Outcome {
  if (!(error instanceof ApiRequestError)) return { kind: "failed" };
  const detail = error.problem?.detail ?? error.problem?.title;
  switch (error.status) {
    case 404:
      return { kind: "nothing" };
    case 403:
      return { kind: "forbidden", detail };
    case 409:
      return { kind: "conflict", detail };
    case 429:
      return { kind: "rateLimited", retryAfter: error.retryAfter, detail };
    default:
      return { kind: "failed", detail };
  }
}

/** True when the screen must look as if nothing exists at this address. */
export function isNothingHere(error: unknown): boolean {
  return outcomeOf(error).kind === "nothing";
}
