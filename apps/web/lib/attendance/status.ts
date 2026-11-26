/** The five marks of a roll and how a tap moves between them. */

export const STATUSES = ["PRESENT", "ABSENT", "LATE", "EXCUSED", "LEFT_EARLY"] as const;
export type AttendanceStatus = (typeof STATUSES)[number];

export const MIN_LATE = 1;
export const MAX_LATE = 600;
export const MAX_NOTE = 200;

export function isStatus(value: unknown): value is AttendanceStatus {
  return typeof value === "string" && (STATUSES as readonly string[]).includes(value);
}

/** The next status of a tap: PRESENT, ABSENT, LATE, EXCUSED, LEFT_EARLY and around. */
export function nextStatus(current: AttendanceStatus): AttendanceStatus {
  const i = STATUSES.indexOf(current);
  return STATUSES[(i + 1) % STATUSES.length]!;
}

/** The previous status (Shift+click). */
export function previousStatus(current: AttendanceStatus): AttendanceStatus {
  const i = STATUSES.indexOf(current);
  return STATUSES[(i - 1 + STATUSES.length) % STATUSES.length]!;
}

/** Only LATE carries minutes (the API rejects them with any other status). */
export const takesMinutes = (status: AttendanceStatus): boolean => status === "LATE";

/** A whole number of minutes 1..600 from the text of the input, or undefined when empty or unusable. */
export function parseMinutes(text: string): number | undefined {
  const trimmed = text.trim();
  if (!/^\d{1,4}$/.test(trimmed)) return undefined;
  const n = Number(trimmed);
  return n >= MIN_LATE && n <= MAX_LATE ? n : undefined;
}

/** True when the text is empty or a valid minutes value (shows the field error otherwise). */
export function minutesTextValid(text: string): boolean {
  return text.trim() === "" || parseMinutes(text) !== undefined;
}
