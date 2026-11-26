import { daysInGregorianMonth } from "@school/calendar";
import { isoInZone, parseIso, toIso, weekdayOf, type Weekday } from "./dates";

/**
 * The repeat rule of an event, as the form builds it: the subset of RFC 5545 the API accepts (`Recurrence` in the backend):
 * `FREQ=DAILY|WEEKLY|MONTHLY`, `INTERVAL` 1-99, `BYDAY` (weekly), `BYMONTHDAY` (monthly), and `UNTIL` (an inclusive date in the
 * event's zone, at most 10 years after the start) or `COUNT` 1-1000, or neither (endless). The first occurrence is always
 * the event's own start, so a weekly rule always contains the start's weekday and a monthly rule is on the start's day.
 * The API re-validates everything; these functions only give quick feedback and a readable summary.
 */

export const MAX_INTERVAL = 99;
export const MAX_COUNT = 1000;
export const MAX_YEARS = 10;

export type Frequency = "NONE" | "DAILY" | "WEEKLY" | "MONTHLY";
export type EndMode = "never" | "until" | "count";

/** Index = weekday as in `Weekday` (0 = Sunday). */
export const WEEKDAY_CODES = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"] as const;
export type WeekdayCode = (typeof WEEKDAY_CODES)[number];

/** Chips are shown Monday first (the week of the API's rules). */
export const WEEKDAYS_MONDAY_FIRST: readonly Weekday[] = [1, 2, 3, 4, 5, 6, 0];

export interface RecurrenceDraft {
  freq: Frequency;
  /** null while the field is empty. */
  interval: number | null;
  /** Weekly: the chosen weekdays; the start's weekday is always part of the rule (see `effectiveDays`). */
  byDay: readonly WeekdayCode[];
  end: EndMode;
  /** "yyyy-mm-dd", the last day an occurrence may start on (inclusive, in the event's zone). */
  until: string;
  count: number | null;
}

export function noRecurrence(): RecurrenceDraft {
  return { freq: "NONE", interval: 1, byDay: [], end: "never", until: "", count: 10 };
}

export function weekdayCode(iso: string): WeekdayCode {
  return WEEKDAY_CODES[weekdayOf(iso)]!;
}

/** The weekdays of a weekly rule: the chosen ones and, always, the start's own day. */
export function effectiveDays(draft: Pick<RecurrenceDraft, "byDay">, startIso: string): WeekdayCode[] {
  const set = new Set<WeekdayCode>(draft.byDay);
  set.add(weekdayCode(startIso));
  return WEEKDAYS_MONDAY_FIRST.map((d) => WEEKDAY_CODES[d]!).filter((code) => set.has(code));
}

/** The day of the month of a monthly rule: the start's. */
export function monthDayOf(startIso: string): number {
  return parseIso(startIso)?.day ?? 1;
}

/** The compact form of a date inside a rule: "20271231". */
function compact(iso: string): string {
  return iso.replaceAll("-", "");
}

/** The rule text to send for a draft, or null when the event does not repeat. */
export function buildRule(draft: RecurrenceDraft, startIso: string): string | null {
  if (draft.freq === "NONE") return null;
  const parts = [`FREQ=${draft.freq}`];
  if (draft.interval !== null && draft.interval !== 1) parts.push(`INTERVAL=${draft.interval}`);
  if (draft.freq === "WEEKLY") {
    const days = effectiveDays(draft, startIso);
    // Only the start's weekday: the plain weekly rule says the same.
    if (days.length > 1) parts.push(`BYDAY=${days.join(",")}`);
  }
  if (draft.freq === "MONTHLY") parts.push(`BYMONTHDAY=${monthDayOf(startIso)}`);
  if (draft.end === "until" && draft.until) parts.push(`UNTIL=${compact(draft.until)}`);
  if (draft.end === "count" && draft.count !== null) parts.push(`COUNT=${draft.count}`);
  return parts.join(";");
}

export type RecurrenceProblem = "intervalRange" | "untilRequired" | "untilBeforeStart" | "untilTooFar" | "countRange";

/** `iso` plus whole years, Feb 29 becoming Feb 28 (as `LocalDate.plusYears`). */
export function addYears(iso: string, years: number): string {
  const d = parseIso(iso);
  if (!d) return iso;
  const year = d.year + years;
  return toIso({ year, month: d.month, day: Math.min(d.day, daysInGregorianMonth(year, d.month)) });
}

/** The furthest `until` the API accepts for a start. */
export function untilLimit(startIso: string): string {
  return addYears(startIso, MAX_YEARS);
}

export function recurrenceProblems(draft: RecurrenceDraft, startIso: string): RecurrenceProblem[] {
  if (draft.freq === "NONE") return [];
  const out: RecurrenceProblem[] = [];
  if (draft.interval === null || !Number.isInteger(draft.interval) || draft.interval < 1 || draft.interval > MAX_INTERVAL) out.push("intervalRange");
  if (draft.end === "until") {
    if (!parseIso(draft.until)) out.push("untilRequired");
    else if (draft.until < startIso) out.push("untilBeforeStart");
    else if (draft.until > untilLimit(startIso)) out.push("untilTooFar");
  }
  if (draft.end === "count" && (draft.count === null || !Number.isInteger(draft.count) || draft.count < 1 || draft.count > MAX_COUNT)) out.push("countRange");
  return out;
}

/** True for a repeating draft with no end (no UNTIL, no COUNT). */
export function isEndless(draft: RecurrenceDraft): boolean {
  return draft.freq !== "NONE" && draft.end === "never";
}

const UNTIL_DATE = /^\d{8}$/;
const UNTIL_INSTANT = /^(\d{8})T(\d{2})(\d{2})(\d{2})Z$/;

/**
 * Reads a stored rule (canonical form) back into a draft, or null when it uses more than the builder offers (several month
 * days, ordinals ...): the form then keeps the rule as it is. An UNTIL instant becomes the date it falls on in `timeZone`.
 */
export function parseRule(rule: string | null | undefined, timeZone: string): RecurrenceDraft | null {
  if (!rule) return noRecurrence();
  const draft: RecurrenceDraft = { ...noRecurrence() };
  let freq: Frequency | null = null;
  let byDay: WeekdayCode[] = [];
  let monthDays = 0;
  for (const part of rule.replace(/^RRULE:/i, "").split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) return null;
    const key = part.slice(0, eq).toUpperCase();
    const value = part.slice(eq + 1).toUpperCase();
    switch (key) {
      case "FREQ":
        if (value !== "DAILY" && value !== "WEEKLY" && value !== "MONTHLY") return null;
        freq = value;
        break;
      case "INTERVAL": {
        const n = Number(value);
        if (!Number.isInteger(n)) return null;
        draft.interval = n;
        break;
      }
      case "COUNT": {
        const n = Number(value);
        if (!Number.isInteger(n)) return null;
        draft.end = "count";
        draft.count = n;
        break;
      }
      case "UNTIL": {
        draft.end = "until";
        if (UNTIL_DATE.test(value)) {
          draft.until = `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`;
        } else {
          const m = UNTIL_INSTANT.exec(value);
          if (!m) return null;
          const day = m[1]!;
          const iso = `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}T${m[2]}:${m[3]}:${m[4]}Z`;
          draft.until = isoInZone(iso, timeZone);
        }
        if (!parseIso(draft.until)) return null;
        break;
      }
      case "BYDAY":
        byDay = value.split(",") as WeekdayCode[];
        if (byDay.some((c) => !WEEKDAY_CODES.includes(c))) return null;
        break;
      case "BYMONTHDAY":
        monthDays = value.split(",").length;
        if (monthDays !== 1) return null;
        break;
      case "WKST":
        if (value !== "MO") return null;
        break;
      default:
        return null;
    }
  }
  if (!freq) return null;
  draft.freq = freq;
  draft.byDay = byDay;
  return draft;
}

/** What a rule says, as data: the screens turn it into a sentence in the viewer's language. */
export interface RuleSummary {
  freq: Exclude<Frequency, "NONE">;
  interval: number;
  /** Weekly: the weekdays (Monday first). */
  days: WeekdayCode[];
  /** Monthly: the day of the month. */
  monthDay: number | null;
  end: { kind: "never" } | { kind: "until"; date: string } | { kind: "count"; count: number };
}

/** The summary of a draft for an event starting on `startIso`; null when it does not repeat. */
export function summarize(draft: RecurrenceDraft, startIso: string): RuleSummary | null {
  if (draft.freq === "NONE") return null;
  return {
    freq: draft.freq,
    interval: draft.interval && draft.interval > 0 ? draft.interval : 1,
    days: draft.freq === "WEEKLY" ? effectiveDays(draft, startIso) : [],
    monthDay: draft.freq === "MONTHLY" ? monthDayOf(startIso) : null,
    end:
      draft.end === "until" && draft.until
        ? { kind: "until", date: draft.until }
        : draft.end === "count" && draft.count
          ? { kind: "count", count: draft.count }
          : { kind: "never" },
  };
}

/** The summary of a stored rule (an event's `recurrenceRule`), or null when it is not one the builder reads. */
export function summarizeRule(rule: string | null | undefined, startIso: string, timeZone: string): RuleSummary | null {
  if (!rule) return null;
  const draft = parseRule(rule, timeZone);
  return draft ? summarize(draft, startIso) : null;
}
