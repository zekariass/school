import { addDays, compactDate, expandCompactDate, parseIso, weekdayOf, type IsoDate } from './civil';
import { dateIn } from './zone';

/**
 * The repeat builder: the subset of RFC 5545 the API takes (`FREQ=DAILY|WEEKLY|MONTHLY`, `INTERVAL` 1..99, `BYDAY` for weekly,
 * `BYMONTHDAY` for monthly, `UNTIL` as a date in the event's zone or `COUNT` 1..1000; the first occurrence is the event's own
 * start, and the rule must match it). Pure: state in, rule text out, and back. The API stays the authority.
 */

export const MAX_INTERVAL = 99;
export const MAX_COUNT = 1000;
/** UNTIL may be at most this many years after the first day. */
export const MAX_UNTIL_YEARS = 10;

export type RepeatFreq = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type RepeatEnd = 'never' | 'until' | 'count';

export interface RepeatState {
  freq: RepeatFreq;
  interval: number;
  /** Weekly: weekdays, 0 = Sunday. */
  weekdays: number[];
  /** Monthly: days of the month, 1..31. */
  monthDays: number[];
  end: RepeatEnd;
  /** The last day when `end` is `until` (inclusive, in the event's zone). */
  until: IsoDate;
  count: number;
}

const DAY_CODES = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'] as const;
/** The API writes weekdays Monday first. */
const CANONICAL_ORDER = [1, 2, 3, 4, 5, 6, 0] as const;

export const NO_REPEAT: RepeatState = { freq: 'NONE', interval: 1, weekdays: [], monthDays: [], end: 'never', until: '', count: 10 };

/** A fresh state for a start date: not repeating, with the start's weekday and day of month ready as the defaults. */
export function defaultRepeat(startDate: IsoDate): RepeatState {
  return {
    ...NO_REPEAT,
    weekdays: [weekdayOf(startDate)],
    monthDays: [parseIso(startDate)?.day ?? 1],
    until: addDays(startDate, 90),
  };
}

export function repeats(state: RepeatState): boolean {
  return state.freq !== 'NONE';
}

/** The rule text the API takes, or null when the event does not repeat. Parts in the API's canonical order. */
export function buildRule(state: RepeatState): string | null {
  if (state.freq === 'NONE') return null;
  const parts = [`FREQ=${state.freq}`];
  if (state.interval !== 1) parts.push(`INTERVAL=${state.interval}`);
  if (state.freq === 'WEEKLY' && state.weekdays.length > 0) {
    parts.push(`BYDAY=${CANONICAL_ORDER.filter((day) => state.weekdays.includes(day)).map((day) => DAY_CODES[day]).join(',')}`);
  }
  if (state.freq === 'MONTHLY' && state.monthDays.length > 0) {
    parts.push(`BYMONTHDAY=${[...new Set(state.monthDays)].sort((a, b) => a - b).join(',')}`);
  }
  if (state.end === 'until' && state.until) parts.push(`UNTIL=${compactDate(state.until)}`);
  if (state.end === 'count') parts.push(`COUNT=${state.count}`);
  return parts.join(';');
}

/**
 * The state of a rule the API returned (canonical form), for editing. `timeZone` turns an UNTIL given as an instant into the
 * date in the event's zone. Null for a rule outside the subset.
 */
export function parseRule(rule: string | null | undefined, startDate: IsoDate, timeZone: string): RepeatState | null {
  if (!rule || !rule.trim()) return defaultRepeat(startDate);
  const state = defaultRepeat(startDate);
  state.end = 'never';
  const seen = new Set<string>();
  for (const part of rule.trim().replace(/^RRULE:/i, '').split(';')) {
    const eq = part.indexOf('=');
    if (eq <= 0) return null;
    const key = part.slice(0, eq).toUpperCase();
    const value = part.slice(eq + 1).toUpperCase();
    if (seen.has(key)) return null;
    seen.add(key);
    switch (key) {
      case 'FREQ':
        if (value !== 'DAILY' && value !== 'WEEKLY' && value !== 'MONTHLY') return null;
        state.freq = value;
        break;
      case 'INTERVAL': {
        const n = Number(value);
        if (!Number.isInteger(n) || n < 1 || n > MAX_INTERVAL) return null;
        state.interval = n;
        break;
      }
      case 'COUNT': {
        const n = Number(value);
        if (!Number.isInteger(n) || n < 1 || n > MAX_COUNT) return null;
        state.end = 'count';
        state.count = n;
        break;
      }
      case 'UNTIL': {
        const instant = /^(\d{8})T(\d{2})(\d{2})(\d{2})Z$/.exec(value);
        const date = instant
          ? dateIn(Date.UTC(Number(value.slice(0, 4)), Number(value.slice(4, 6)) - 1, Number(value.slice(6, 8)), Number(instant[2]), Number(instant[3]), Number(instant[4])), timeZone)
          : expandCompactDate(value);
        if (!date || (value.length !== 8 && !instant)) return null;
        state.end = 'until';
        state.until = date;
        break;
      }
      case 'BYDAY': {
        const days = value.split(',').map((code) => (DAY_CODES as readonly string[]).indexOf(code));
        if (days.length === 0 || days.some((day) => day < 0)) return null;
        state.weekdays = days;
        break;
      }
      case 'BYMONTHDAY': {
        const days = value.split(',').map(Number);
        if (days.length === 0 || days.some((day) => !Number.isInteger(day) || day < 1 || day > 31)) return null;
        state.monthDays = days;
        break;
      }
      case 'WKST':
        if (value !== 'MO') return null;
        break;
      default:
        return null;
    }
  }
  return state.freq === 'NONE' ? null : state;
}

export type RepeatProblem =
  | 'interval'
  | 'weekdaysNone'
  | 'weekdayStart'
  | 'monthDaysNone'
  | 'monthDayStart'
  | 'untilMissing'
  | 'untilBeforeStart'
  | 'untilTooFar'
  | 'count';

/** Why the repeat cannot be sent (the API still decides), as codes that map to `mobile.calendar.form.problem.repeat.*` keys. */
export function repeatProblems(state: RepeatState, startDate: IsoDate): RepeatProblem[] {
  if (state.freq === 'NONE') return [];
  const problems: RepeatProblem[] = [];
  if (!Number.isInteger(state.interval) || state.interval < 1 || state.interval > MAX_INTERVAL) problems.push('interval');
  if (state.freq === 'WEEKLY') {
    if (state.weekdays.length === 0) problems.push('weekdaysNone');
    else if (!state.weekdays.includes(weekdayOf(startDate))) problems.push('weekdayStart');
  }
  if (state.freq === 'MONTHLY') {
    if (state.monthDays.length === 0) problems.push('monthDaysNone');
    else if (!state.monthDays.includes(parseIso(startDate)?.day ?? 0)) problems.push('monthDayStart');
  }
  if (state.end === 'until') {
    if (!state.until || !parseIso(state.until)) problems.push('untilMissing');
    else if (state.until < startDate) problems.push('untilBeforeStart');
    else if (state.until > addYears(startDate, MAX_UNTIL_YEARS)) problems.push('untilTooFar');
  }
  if (state.end === 'count' && (!Number.isInteger(state.count) || state.count < 1 || state.count > MAX_COUNT)) problems.push('count');
  return problems;
}

/** `date` plus whole years (29 February moves to 28 February in a common year). */
export function addYears(date: IsoDate, years: number): IsoDate {
  const ymd = parseIso(date);
  if (!ymd) return date;
  const year = ymd.year + years;
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
  const day = ymd.month === 2 && ymd.day === 29 && !leap ? 28 : ymd.day;
  return `${String(year).padStart(4, '0')}-${String(ymd.month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/**
 * Keeps the rule matching the start while the start date is edited: a weekly rule with only the old start's weekday (or a monthly
 * one with only its day of the month) follows the new start. Anything the person chose themselves is left alone.
 */
export function followStart(state: RepeatState, oldStart: IsoDate, newStart: IsoDate): RepeatState {
  if (state.freq === 'NONE') return state;
  let next = state;
  const oldWeekday = weekdayOf(oldStart);
  if (state.weekdays.length === 1 && state.weekdays[0] === oldWeekday) next = { ...next, weekdays: [weekdayOf(newStart)] };
  const oldDay = parseIso(oldStart)?.day;
  if (state.monthDays.length === 1 && state.monthDays[0] === oldDay) next = { ...next, monthDays: [parseIso(newStart)?.day ?? 1] };
  return next;
}

/** Whether a day of the month is missing from some months (the rule skips those months). */
export function skipsShortMonths(state: RepeatState): boolean {
  return state.freq === 'MONTHLY' && state.monthDays.some((day) => day >= 29);
}

export interface DescribeOptions {
  /** Catalog lookup (`mobile.calendar.repeat.*`). */
  t: (key: string, values?: Record<string, string | number>) => string;
  weekdayName: (weekday: number) => string;
  /** A date in the person's calendar, e.g. "31 December 2027". */
  formatDate: (date: IsoDate) => string;
}

function joinList(items: readonly string[], t: DescribeOptions['t']): string {
  if (items.length <= 1) return items[0] ?? '';
  const head = items.slice(0, -1).join(', ');
  return t('mobile.calendar.repeat.and', { first: head, last: items[items.length - 1]! });
}

/** The repeat in plain words: "Every 2 weeks on Monday and Wednesday, until 31 December 2027". Empty when it does not repeat. */
export function describeRepeat(state: RepeatState, { t, weekdayName, formatDate }: DescribeOptions): string {
  if (state.freq === 'NONE') return '';
  const many = state.interval > 1;
  let text: string;
  if (state.freq === 'DAILY') {
    text = many ? t('mobile.calendar.repeat.dailyEvery', { interval: state.interval }) : t('mobile.calendar.repeat.daily');
  } else if (state.freq === 'WEEKLY') {
    const days = CANONICAL_ORDER.filter((day) => state.weekdays.includes(day)).map(weekdayName);
    text = many
      ? t('mobile.calendar.repeat.weeklyEvery', { interval: state.interval, days: joinList(days, t) })
      : t('mobile.calendar.repeat.weekly', { days: joinList(days, t) });
  } else {
    const days = [...new Set(state.monthDays)].sort((a, b) => a - b).map(String);
    text = many
      ? t('mobile.calendar.repeat.monthlyEvery', { interval: state.interval, days: joinList(days, t) })
      : t('mobile.calendar.repeat.monthly', { days: joinList(days, t) });
  }
  if (state.end === 'until' && state.until) return t('mobile.calendar.repeat.withUntil', { rule: text, date: formatDate(state.until) });
  if (state.end === 'count') return t('mobile.calendar.repeat.withCount', { rule: text, count: state.count });
  return text;
}
