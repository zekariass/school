import { addDays, daysBetween } from '@/lib/attendance/dates';
import { isCivilDate } from '@/lib/sync/operations';

/**
 * Plain calendar dates (`YYYY-MM-DD`, Gregorian, no time zone): the keys of the whole calendar. Compared as text, moved with
 * `addDays`. The Ethiopian calendar is display only and is derived from these keys (`grid.ts`), never stored or sent.
 */
export { addDays, daysBetween, isCivilDate };

export type IsoDate = string;

export interface Ymd {
  year: number;
  month: number;
  day: number;
}

export function parseIso(date: string): Ymd | null {
  if (!isCivilDate(date)) return null;
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return { year, month, day };
}

export function toIso({ year, month, day }: Ymd): IsoDate {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** 0 = Sunday ... 6 = Saturday. */
export function weekdayOf(date: IsoDate): number {
  const ymd = parseIso(date);
  if (!ymd) return 0;
  return new Date(Date.UTC(ymd.year, ymd.month - 1, ymd.day)).getUTCDay();
}

/** The dates from `first` to `last`, both included (empty when `last` is before `first`). Bounded (a year and a bit). */
export function eachDay(first: IsoDate, last: IsoDate, max = 400): IsoDate[] {
  const count = daysBetween(first, last);
  if (!(count >= 0)) return [];
  const days: IsoDate[] = [];
  for (let i = 0; i <= Math.min(count, max - 1); i++) days.push(addDays(first, i));
  return days;
}

export const maxDate = (a: IsoDate, b: IsoDate): IsoDate => (a >= b ? a : b);
export const minDate = (a: IsoDate, b: IsoDate): IsoDate => (a <= b ? a : b);

/** The `YYYYMMDD` form the API's `UNTIL=` takes. */
export function compactDate(date: IsoDate): string {
  return date.replace(/-/g, '');
}

/** Back from `YYYYMMDD`; null when it is not a date. */
export function expandCompactDate(text: string): IsoDate | null {
  const match = /^(\d{4})(\d{2})(\d{2})$/.exec(text);
  if (!match) return null;
  const iso = `${match[1]}-${match[2]}-${match[3]}`;
  return isCivilDate(iso) ? iso : null;
}
