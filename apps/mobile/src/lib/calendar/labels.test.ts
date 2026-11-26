import { createTranslator } from 'use-intl/core';
import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import { bucketItems } from './bucket';
import { dateHeading, dateText, daySummaryLabel, entryTimeText, eventWhenText, type LabelContext } from './labels';
import { itemStyle, isCancelled, opensDetail } from './item-style';
import { occurrenceTimes } from './occurrence';
import type { CalendarEventView, CalendarItem } from './types';

const ADDIS = 'Africa/Addis_Ababa';

function context(over: Partial<LabelContext> = {}): LabelContext {
  const t = createTranslator({ locale: 'en', messages: messages.en as never }) as unknown as LabelContext['t'];
  return { t: (key, values) => t(key, values), locale: 'en', calendar: 'gregorian', deviceZone: ADDIS, ...over };
}

function item(over: Partial<CalendarItem>): CalendarItem {
  return {
    id: '5b3b9f5e-0f3c-4a58-9c63-3f1a4e2d7a10',
    source: 'EVENT',
    kind: 'SCHOOL_EVENT',
    title: 'Sports day',
    allDay: false,
    startAt: '2026-12-07T06:00:00Z',
    endAt: '2026-12-07T07:30:00Z',
    timeZone: ADDIS,
    status: 'SCHEDULED',
    ...over,
  };
}

const RANGE = { first: '2026-12-01', last: '2026-12-13' };
const entryOf = (it: CalendarItem, date: string) => bucketItems([it], RANGE).get(date)![0]!;

describe('dates in the chosen calendar', () => {
  it('writes the same day in both calendars', () => {
    expect(dateText('2026-11-26', 'gregorian', 'en')).toBe('November 26, 2026');
    expect(dateText('2026-11-26', 'ethiopian', 'en')).toBe('Hidar 17, 2019');
    expect(dateText('2026-11-26', 'ethiopian', 'am')).toBe('ኅዳር 17, 2019');
    expect(dateHeading('2026-11-26', 'gregorian', 'en')).toBe('Thursday, November 26, 2026');
    expect(dateHeading('2027-09-11', 'ethiopian', 'en')).toBe('Saturday, Pagume 6, 2019');
    expect(dateText('not a date', 'gregorian', 'en')).toBe('');
  });
});

describe('the time of an entry', () => {
  it('shows all-day items by their dates and timed items in the zone of the event', () => {
    const ctx = context();
    expect(entryTimeText(entryOf(item({}), '2026-12-07'), ctx)).toMatch(/^9:00\s?AM to 10:30\s?AM$/i);
    const holiday = item({ source: 'HOLIDAY', allDay: true, startDate: '2026-12-07', endDate: '2026-12-09' });
    expect(entryTimeText(entryOf(holiday, '2026-12-08'), ctx)).toBe('All day, day 2 of 3');
    expect(entryTimeText(entryOf(item({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }), '2026-12-07'), ctx)).toBe('All day');
  });
  it('says so when the zone of the event differs from the phone', () => {
    const text = entryTimeText(entryOf(item({}), '2026-12-07'), context({ deviceZone: 'America/New_York' }));
    expect(text).toMatch(/Africa\/Addis Ababa time\)$/);
    expect(text).toMatch(/9:00/);
    // The phone in another zone with the same clock (Nairobi) says nothing.
    expect(entryTimeText(entryOf(item({}), '2026-12-07'), context({ deviceZone: 'Africa/Nairobi' }))).not.toMatch(/time\)/);
    expect(entryTimeText(entryOf(item({}), '2026-12-07'), context({ deviceZone: null }))).not.toMatch(/time\)/);
  });
  it('describes an item that runs past midnight', () => {
    const overnight = item({ startAt: '2026-12-07T15:00:00Z', endAt: '2026-12-09T03:00:00Z' });
    expect(entryTimeText(entryOf(overnight, '2026-12-07'), context())).toMatch(/^From 6:00\s?PM$/i);
    expect(entryTimeText(entryOf(overnight, '2026-12-08'), context())).toBe('All day');
    expect(entryTimeText(entryOf(overnight, '2026-12-09'), context())).toMatch(/^Until 6:00\s?AM$/i);
  });
});

describe('labels for screen readers', () => {
  it('reads the date, then the titles, then how many more', () => {
    const entries = bucketItems([item({ title: 'A' }), item({ title: 'B' }), item({ title: 'C' }), item({ title: 'D', status: 'CANCELLED' })], RANGE).get('2026-12-07')!;
    expect(daySummaryLabel('2026-12-07', entries, context(), '2026-12-01')).toBe('Monday, December 7, 2026. A, B, C. and 1 more');
    expect(daySummaryLabel('2026-12-07', [], context(), '2026-12-07')).toBe('Monday, December 7, 2026. Today. Nothing planned');
    const cancelled = bucketItems([item({ title: 'D', status: 'CANCELLED' })], RANGE).get('2026-12-07')!;
    expect(daySummaryLabel('2026-12-07', cancelled, context(), '2026-12-01')).toContain('D, cancelled');
  });
});

describe('the whole time of an event', () => {
  const base: CalendarEventView = {
    id: '5b3b9f5e-0f3c-4a58-9c63-3f1a4e2d7a10',
    scope: 'SCHOOL',
    eventType: 'SCHOOL_EVENT',
    title: 'Sports day',
    timeZone: ADDIS,
    status: 'SCHEDULED',
    allDay: false,
    startAt: '2026-12-07T06:00:00Z',
    endAt: '2026-12-07T07:30:00Z',
  };
  it('writes timed, multi-day and all-day events', () => {
    expect(eventWhenText(occurrenceTimes(base, null), ADDIS, context())).toMatch(/^December 7, 2026, 9:00\s?AM to 10:30\s?AM$/i);
    expect(eventWhenText(occurrenceTimes({ ...base, endAt: '2026-12-08T07:30:00Z' }, null), ADDIS, context())).toMatch(/^December 7, 2026, 9:00\s?AM to December 8, 2026, 10:30\s?AM$/i);
    expect(eventWhenText(occurrenceTimes({ ...base, allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }, null), ADDIS, context())).toBe('December 7, 2026, all day');
    expect(eventWhenText(occurrenceTimes({ ...base, allDay: true, startDate: '2026-12-07', endDate: '2026-12-09' }, null), ADDIS, context())).toBe('December 7, 2026 to December 9, 2026, all day');
  });
  it('writes the date of the event in the Ethiopian calendar when asked', () => {
    expect(eventWhenText(occurrenceTimes(base, null), ADDIS, context({ calendar: 'ethiopian' }))).toMatch(/^Hidar 28, 2019, 9:00/i);
  });
  it('shows an event on its own day even when the phone is on the other side of the date line', () => {
    // 22:00Z is 01:00 the next day in Addis: the date is the event's, whatever the phone says.
    const late = { ...base, startAt: '2026-12-07T22:00:00Z', endAt: '2026-12-07T23:00:00Z' };
    expect(eventWhenText(occurrenceTimes(late, null), ADDIS, context())).toMatch(/^December 8, 2026, 1:00\s?AM/i);
  });
});

describe('how items look', () => {
  it('gives every source an icon and a name, never colour alone', () => {
    for (const source of ['EVENT', 'HOLIDAY', 'ACADEMIC_PERIOD', 'PERSONAL_EVENT']) {
      const style = itemStyle({ source, kind: 'OTHER' });
      expect(style.labelKey).toMatch(/^mobile\.calendar\.kind\./);
      expect(style.icon.ios).toBeTruthy();
      expect(style.icon.android).toBeTruthy();
    }
    expect(itemStyle({ source: 'ACADEMIC_PERIOD', kind: 'YEAR_START' }).labelKey).toBe('mobile.calendar.kind.yearStart');
    expect(itemStyle({ source: 'EVENT', kind: 'PARENT_MEETING' }).labelKey).toBe('mobile.calendar.kind.parentMeeting');
    expect(itemStyle({ source: 'EVENT', kind: 'EXAM' }).labelKey).not.toBe(itemStyle({ source: 'EVENT', kind: 'CLASS_EVENT' }).labelKey);
  });
  it('knows cancelled items and which ones open a detail screen', () => {
    expect(isCancelled({ status: 'CANCELLED' })).toBe(true);
    expect(isCancelled({ status: 'SCHEDULED' })).toBe(false);
    expect(opensDetail({ source: 'EVENT', eventId: 'e' })).toBe(true);
    expect(opensDetail({ source: 'PERSONAL_EVENT', eventId: 'e' })).toBe(true);
    expect(opensDetail({ source: 'HOLIDAY' })).toBe(false);
    expect(opensDetail({ source: 'EVENT' })).toBe(false);
  });
});
