import { formatEthiopianDate } from '@school/calendar';

import { entryTime, type DayEntry } from './bucket';
import { parseIso, weekdayOf, type IsoDate } from './civil';
import type { CalendarPreference } from './format-instant';
import { dayInCalendar } from './grid';
import { monthName, weekdayName } from './names';
import type { OccurrenceTimes } from './occurrence';
import { clockIn, dateIn, zoneLabel, zonesDiffer, zonedParts } from './zone';

/** Wording of the calendar: dates in the chosen calendar and times in the event's own zone. Pure, with the translator passed in. */

export type Translate = (key: string, values?: Record<string, string | number>) => string;

export interface LabelContext {
  t: Translate;
  locale: string;
  calendar: CalendarPreference;
  /** The phone's zone, only to say when an event's zone differs from it (never to read a time). */
  deviceZone: string | null;
}

/** "November 26, 2026" / "Hidar 17, 2019 E.C." in the chosen calendar. */
export function dateText(date: IsoDate, calendar: CalendarPreference, locale: string): string {
  if (!parseIso(date)) return '';
  const shown = dayInCalendar(date, calendar);
  if (calendar === 'ethiopian') return formatEthiopianDate(shown, { language: locale, style: 'medium' });
  return `${monthName('gregorian', shown.month, locale)} ${shown.day}, ${shown.year}`;
}

/** "Thursday, November 26, 2026". */
export function dateHeading(date: IsoDate, calendar: CalendarPreference, locale: string): string {
  return `${weekdayName(weekdayOf(date), locale)}, ${dateText(date, calendar, locale)}`;
}

/** The time line of an entry on its day: "9:00 AM – 10:00 AM", "All day", "Day 2 of 3", with the zone when the phone's differs. */
export function entryTimeText(entry: DayEntry, { t, locale, deviceZone }: LabelContext): string {
  const { item } = entry;
  const time = entryTime(entry);
  const clock = (ms: number) => clockIn(ms, item.timeZone, locale);
  let text: string;
  let at = Date.parse(item.startAt);
  switch (time.kind) {
    case 'allDay':
      return entry.dayCount > 1 ? t('mobile.calendar.time.allDayOf', { day: entry.dayIndex + 1, total: entry.dayCount }) : t('mobile.calendar.time.allDay');
    case 'range':
      text = time.endMs === null ? clock(time.startMs) : t('mobile.calendar.time.range', { start: clock(time.startMs), end: clock(time.endMs) });
      at = time.startMs;
      break;
    case 'from':
      text = t('mobile.calendar.time.from', { time: clock(time.startMs) });
      at = time.startMs;
      break;
    case 'until':
      text = t('mobile.calendar.time.until', { time: clock(time.endMs) });
      at = time.endMs;
      break;
    default:
      return t('mobile.calendar.time.continues');
  }
  return zonesDiffer(item.timeZone, deviceZone, at) ? t('mobile.calendar.time.withZone', { time: text, zone: zoneLabel(item.timeZone) }) : text;
}

/** "Mon" style label of a day for lists: "Mon 26" with the number in the chosen calendar. */
export function dayChip(date: IsoDate, calendar: CalendarPreference, locale: string): { weekday: string; day: number } {
  return { weekday: weekdayName(weekdayOf(date), locale), day: dayInCalendar(date, calendar).day };
}

/**
 * The screen-reader label of a day cell: the date, then what is on it (up to three titles, then how many more).
 * Titles are read as they are; cancelled items say so.
 */
export function daySummaryLabel(date: IsoDate, entries: readonly DayEntry[], { t, calendar, locale }: LabelContext, today: IsoDate): string {
  const parts: string[] = [dateHeading(date, calendar, locale)];
  if (date === today) parts.push(t('mobile.calendar.a11y.today'));
  if (entries.length === 0) {
    parts.push(t('mobile.calendar.a11y.nothing'));
  } else {
    const named = entries.slice(0, 3).map((entry) => (entry.item.status === 'CANCELLED' ? t('mobile.calendar.a11y.cancelledItem', { title: entry.item.title }) : entry.item.title));
    parts.push(named.join(', '));
    if (entries.length > 3) parts.push(t('mobile.calendar.a11y.more', { count: entries.length - 3 }));
  }
  return parts.join('. ');
}

/** The whole time of an event or occurrence for the detail screen, in the event's zone. */
export function eventWhenText(times: OccurrenceTimes, timeZone: string, { t, locale, calendar }: LabelContext): string {
  if (times.allDay && times.startDate && times.endDate) {
    const start = dateText(times.startDate, calendar, locale);
    return times.endDate === times.startDate ? t('mobile.calendar.when.allDayOne', { date: start }) : t('mobile.calendar.when.allDayMany', { start, end: dateText(times.endDate, calendar, locale) });
  }
  if (times.startMs === null) return '';
  const startDate = dateIn(times.startMs, timeZone);
  const startClock = clockIn(times.startMs, timeZone, locale);
  if (times.endMs === null || times.endMs === times.startMs) return t('mobile.calendar.when.timedOne', { date: dateText(startDate, calendar, locale), start: startClock });
  const endDate = zonedParts(times.endMs, timeZone).date;
  const endClock = clockIn(times.endMs, timeZone, locale);
  if (endDate === startDate) return t('mobile.calendar.when.timedSameDay', { date: dateText(startDate, calendar, locale), start: startClock, end: endClock });
  return t('mobile.calendar.when.timedManyDays', {
    startDate: dateText(startDate, calendar, locale),
    start: startClock,
    endDate: dateText(endDate, calendar, locale),
    end: endClock,
  });
}
