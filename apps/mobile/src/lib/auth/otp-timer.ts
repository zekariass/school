/** Seconds before the user may ask for another code. */
export const RESEND_COOLDOWN_SECONDS = 30;

export const DEFAULT_CODE_LENGTH = 6;

/** Current time in ms (a function, so screens do not call `Date.now` while rendering). */
export function nowMs(): number {
  return Date.now();
}

/** Whole seconds left until `deadlineMs`, never negative. */
export function secondsLeft(deadlineMs: number, nowMs: number): number {
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000));
}

/** "4:07": minutes and zero-padded seconds. */
export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/** Keeps digits only (pasted or autofilled codes may carry spaces or dashes) and caps the length. */
export function sanitizeCode(input: string, length: number = DEFAULT_CODE_LENGTH): string {
  return input.replace(/\D/g, '').slice(0, length);
}

/** Reads a positive integer route param; falls back when missing or not a number. */
export function intParam(value: string | string[] | undefined, fallback: number): number {
  const raw = Array.isArray(value) ? value[0] : value;
  const parsed = raw === undefined ? NaN : Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
