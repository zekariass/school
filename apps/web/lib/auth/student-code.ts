import { retryAfterSeconds } from "@/lib/problem";

/**
 * How the class-code sign-in reports a failure. The API answers every wrong, expired or revoked code (and a school that is
 * not ACTIVE) with the same 400 `auth.invalid_code`, so this screen can never say which it was: it shows the API's own
 * generic sentence when there is one. A 429 carries `Retry-After`: the child sees how long to wait.
 */
export type StudentCodeFailure =
  | { kind: "invalid"; detail?: string }
  | { kind: "rateLimited"; seconds: number }
  | { kind: "unavailable" };

/** The fallback wait when the API limited the sign-in without saying for how long. */
export const DEFAULT_WAIT_SECONDS = 60;

/** What the Route Handler answered: its status, the problem's code and detail when the body was one, and `Retry-After`. */
export interface SignInAnswer {
  status: number;
  code?: string;
  detail?: string;
  retryAfter?: string | null;
}

export function failureOf(answer: SignInAnswer): StudentCodeFailure {
  if (answer.status === 429 || answer.code === "auth.rate_limited") {
    return { kind: "rateLimited", seconds: retryAfterSeconds(answer.retryAfter) ?? DEFAULT_WAIT_SECONDS };
  }
  // 5xx, a lost connection (status 0) or the web server's own "upstream unavailable": not about the code.
  if (answer.status === 0 || answer.status >= 500) return { kind: "unavailable" };
  // Everything else (the API's 400, an account that cannot sign in, a malformed code) looks like a wrong code.
  return { kind: "invalid", detail: answer.detail };
}

/** Why the camera is not scanning (the names are the browser's `DOMException` names). */
export type CameraProblem = "denied" | "unavailable";

export function cameraProblemOf(error: unknown): CameraProblem {
  const name = typeof error === "object" && error !== null ? (error as { name?: unknown }).name : undefined;
  return name === "NotAllowedError" || name === "SecurityError" ? "denied" : "unavailable";
}

/** Seconds a scanned code is ignored after a try, so a code held in front of the camera is not sent over and over. */
export const SCAN_COOLDOWN_MS = 2500;

/** The camera is read about eight times a second: enough to find a QR code, light on a child's old laptop. */
export const SCAN_INTERVAL_MS = 125;

/** True when enough time has passed since the last decoded frame. */
export function frameDue(now: number, last: number, interval = SCAN_INTERVAL_MS): boolean {
  return now - last >= interval;
}

/** The largest side of the picture handed to the decoder: smaller frames decode faster and a QR code is big in a frame. */
export const SCAN_MAX_SIDE = 640;

/** The size a video frame of `width` x `height` is drawn at for decoding (never enlarged). */
export function scanSize(width: number, height: number, maxSide = SCAN_MAX_SIDE): { width: number; height: number } {
  if (width <= 0 || height <= 0) return { width: 0, height: 0 };
  const scale = Math.min(1, maxSide / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}
