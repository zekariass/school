import { addDays, DEFAULT_WEEK_START, diffDays, monthBounds, shiftMonth, toDisplay, weekdayOf, weekOf, type CalendarSystem, type Weekday } from "./dates";

export type CalendarView = "month" | "week" | "agenda";
export const CALENDAR_VIEWS: readonly CalendarView[] = ["month", "week", "agenda"];

export interface DayCell {
  /** The Gregorian date, "yyyy-mm-dd": the key of everything else. */
  iso: string;
  /** Day number in the display calendar (the Ethiopian day in the Ethiopian view). */
  day: number;
  /** Month (1-based) and year in the display calendar. */
  month: number;
  year: number;
  weekday: Weekday;
  /** False for the days of the neighbouring months that fill the first and last row. */
  inMonth: boolean;
  isToday: boolean;
}

function cell(iso: string, system: CalendarSystem, today: string, inMonth: boolean): DayCell {
  const d = toDisplay(iso, system);
  return { iso, day: d.day, month: d.month, year: d.year, weekday: weekdayOf(iso), inMonth, isToday: iso === today };
}

export interface MonthGrid {
  year: number;
  month: number;
  weeks: DayCell[][];
}

/**
 * The weeks that show the month (of the display calendar) holding `anchor`: whole weeks from `weekStart`, the days of the
 * neighbouring months included (flagged `inMonth: false`). The Ethiopian 13th month (5 or 6 days) fills one or two rows.
 */
export function monthGrid(anchor: string, system: CalendarSystem, today: string, weekStart: Weekday = DEFAULT_WEEK_START): MonthGrid {
  const bounds = monthBounds(anchor, system);
  const lead = (weekdayOf(bounds.first) - weekStart + 7) % 7;
  const rows = Math.ceil((lead + bounds.days) / 7);
  const start = addDays(bounds.first, -lead);
  const weeks: DayCell[][] = [];
  for (let r = 0; r < rows; r++) {
    weeks.push(
      Array.from({ length: 7 }, (_, c) => {
        const iso = addDays(start, r * 7 + c);
        return cell(iso, system, today, iso >= bounds.first && iso <= bounds.last);
      }),
    );
  }
  return { year: bounds.year, month: bounds.month, weeks };
}

/** The week holding `anchor` as one row; every day belongs to the "month" of the view (`inMonth` is true). */
export function weekGrid(anchor: string, system: CalendarSystem, today: string, weekStart: Weekday = DEFAULT_WEEK_START): DayCell[] {
  return weekOf(anchor, weekStart).map((iso) => cell(iso, system, today, true));
}

export interface DateRange {
  /** First and last visible day, inclusive (Gregorian ISO dates). */
  from: string;
  to: string;
}

/** The days a view shows: the grid's first to last cell (month), the week, or the month (agenda). */
export function visibleRange(view: CalendarView, anchor: string, system: CalendarSystem, weekStart: Weekday = DEFAULT_WEEK_START): DateRange {
  if (view === "week") {
    const days = weekOf(anchor, weekStart);
    return { from: days[0]!, to: days[6]! };
  }
  const bounds = monthBounds(anchor, system);
  if (view === "agenda") return { from: bounds.first, to: bounds.last };
  const lead = (weekdayOf(bounds.first) - weekStart + 7) % 7;
  const rows = Math.ceil((lead + bounds.days) / 7);
  const from = addDays(bounds.first, -lead);
  return { from, to: addDays(from, rows * 7 - 1) };
}

/** The anchor after "previous" (-1) or "next" (+1): a week, or a month of the display calendar. */
export function shiftAnchor(view: CalendarView, anchor: string, system: CalendarSystem, delta: -1 | 1): string {
  return view === "week" ? addDays(anchor, 7 * delta) : shiftMonth(anchor, system, delta);
}

/** The API refuses windows longer than this many days. */
export const MAX_WINDOW_DAYS = 366;

export interface FetchWindow {
  /** Instants (UTC, ISO) for `GET /calendar?from&to`: [from, to). */
  from: string;
  to: string;
}

/**
 * The window to fetch for a visible range: from UTC midnight of the day before the first visible day to UTC midnight of
 * two days after the last. The slack covers every time zone (an event is shown on its own days, in its own zone, up to
 * 14 hours away from UTC), and a window is never longer than a year.
 */
export function fetchWindow(range: DateRange): FetchWindow {
  const from = addDays(range.from, -1);
  const to = addDays(range.to, 2);
  if (diffDays(from, to) > MAX_WINDOW_DAYS) throw new RangeError("The calendar window is longer than a year");
  return { from: `${from}T00:00:00Z`, to: `${to}T00:00:00Z` };
}
