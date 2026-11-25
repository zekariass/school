const HH_MM = /^([01]\d|2[0-3]):([0-5]\d)$/;

export function isValidTime(value: string): boolean {
  return HH_MM.test(value);
}

/**
 * Turns what a person types ("2200", "22", "6:30", "22:00") into "HH:mm", or null when it is
 * not a time of day.
 */
export function normalizeTime(input: string): string | null {
  const text = input.trim();
  let hours: string;
  let minutes: string;
  const colon = /^(\d{1,2}):(\d{2})$/.exec(text);
  if (colon) {
    hours = colon[1]!;
    minutes = colon[2]!;
  } else if (/^\d{1,4}$/.test(text)) {
    hours = text.length <= 2 ? text : text.slice(0, -2);
    minutes = text.length <= 2 ? '00' : text.slice(-2);
  } else {
    return null;
  }
  const value = `${hours.padStart(2, '0')}:${minutes}`;
  return isValidTime(value) ? value : null;
}

export interface QuietHoursDraft {
  start: string;
  end: string;
}

export type QuietHoursPayload =
  | { ok: true; quietHoursStart?: string; quietHoursEnd?: string }
  | { ok: false; reason: 'incomplete' | 'invalid' };

/** The PUT body for the typed values: both times, or neither (clears quiet hours). */
export function quietHoursPayload(draft: QuietHoursDraft): QuietHoursPayload {
  const start = draft.start.trim();
  const end = draft.end.trim();
  if (!start && !end) return { ok: true };
  if (!start || !end) return { ok: false, reason: 'incomplete' };
  const s = normalizeTime(start);
  const e = normalizeTime(end);
  if (!s || !e) return { ok: false, reason: 'invalid' };
  return { ok: true, quietHoursStart: s, quietHoursEnd: e };
}
