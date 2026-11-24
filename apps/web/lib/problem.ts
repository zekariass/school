import { isProblemDetail, type ProblemDetail } from "@school/api-client";

/** The RFC 9457 problem in a failed response, if it carries one. */
export async function readProblem(response: Response): Promise<ProblemDetail | null> {
  try {
    const body: unknown = await response.json();
    return isProblemDetail(body) ? body : null;
  } catch {
    return null;
  }
}

/** `Retry-After` in whole seconds (the API sends delta-seconds), or null. */
export function retryAfterSeconds(value: string | null | undefined): number | null {
  if (!value) return null;
  const seconds = Number(value.trim());
  return Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : null;
}

/** "4:05" for 245 seconds. */
export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

/** Whole seconds from `now` until `deadline` (both epoch ms), never negative. */
export function secondsUntil(deadline: number, now: number): number {
  return Math.max(0, Math.ceil((deadline - now) / 1000));
}
