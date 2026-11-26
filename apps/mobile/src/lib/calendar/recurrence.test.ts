import { createTranslator } from 'use-intl/core';
import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import { dateText } from './labels';
import { weekdayName } from './names';
import {
  addYears,
  buildRule,
  defaultRepeat,
  describeRepeat,
  followStart,
  NO_REPEAT,
  parseRule,
  repeatProblems,
  repeats,
  skipsShortMonths,
  type RepeatState,
} from './recurrence';

const ADDIS = 'Africa/Addis_Ababa';
// 2026-12-07 is a Monday.
const START = '2026-12-07';

function state(over: Partial<RepeatState>): RepeatState {
  return { ...defaultRepeat(START), ...over };
}

describe('building the rule', () => {
  it('builds nothing for an event that does not repeat', () => {
    expect(buildRule(NO_REPEAT)).toBeNull();
    expect(repeats(NO_REPEAT)).toBe(false);
  });
  it('builds the API subset in its canonical order', () => {
    expect(buildRule(state({ freq: 'DAILY' }))).toBe('FREQ=DAILY');
    expect(buildRule(state({ freq: 'DAILY', interval: 3, end: 'count', count: 5 }))).toBe('FREQ=DAILY;INTERVAL=3;COUNT=5');
    // Weekdays are written Monday first whatever order they were chosen in.
    expect(buildRule(state({ freq: 'WEEKLY', weekdays: [0, 3, 1] }))).toBe('FREQ=WEEKLY;BYDAY=MO,WE,SU');
    expect(buildRule(state({ freq: 'WEEKLY', interval: 2, weekdays: [1], end: 'until', until: '2027-12-31' }))).toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;UNTIL=20271231');
    expect(buildRule(state({ freq: 'MONTHLY', monthDays: [20, 5, 5] }))).toBe('FREQ=MONTHLY;BYMONTHDAY=5,20');
    expect(buildRule(state({ freq: 'MONTHLY', end: 'never' }))).toBe('FREQ=MONTHLY;BYMONTHDAY=7');
  });
  it('round-trips the rules the API returns', () => {
    for (const rule of ['FREQ=DAILY', 'FREQ=WEEKLY;BYDAY=MO,WE', 'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;UNTIL=20271231', 'FREQ=MONTHLY;BYMONTHDAY=5,20;COUNT=12']) {
      const parsed = parseRule(rule, START, ADDIS);
      expect(parsed).not.toBeNull();
      expect(buildRule(parsed!)).toBe(rule);
    }
  });
  it('reads an UNTIL given as an instant in the event zone', () => {
    // 21:00Z on 31 December is already 1 January in Addis Ababa.
    const parsed = parseRule('FREQ=DAILY;UNTIL=20271231T210000Z', START, ADDIS);
    expect(parsed).toMatchObject({ end: 'until', until: '2028-01-01' });
    expect(parseRule('FREQ=DAILY;UNTIL=20271231T210000Z', START, 'America/New_York')).toMatchObject({ until: '2027-12-31' });
  });
  it('refuses rules outside the subset', () => {
    for (const rule of ['FREQ=YEARLY', 'FREQ=WEEKLY;BYDAY=2MO', 'FREQ=DAILY;INTERVAL=0', 'FREQ=DAILY;INTERVAL=100', 'FREQ=DAILY;COUNT=1001', 'FREQ=DAILY;BYSETPOS=1', 'INTERVAL=2', 'FREQ=DAILY;FREQ=WEEKLY', 'FREQ=DAILY;UNTIL=20271331', 'FREQ=DAILY;WKST=SU']) {
      expect(parseRule(rule, START, ADDIS), rule).toBeNull();
    }
  });
  it('gives the default state when there is no rule', () => {
    expect(parseRule(null, START, ADDIS)).toEqual(defaultRepeat(START));
  });
});

describe('the start of the series', () => {
  it('defaults to the weekday and the day of the month of the start', () => {
    expect(defaultRepeat(START)).toMatchObject({ freq: 'NONE', weekdays: [1], monthDays: [7], until: '2027-03-07' });
  });
  it('follows a change of the start when the person did not choose their own days', () => {
    expect(followStart(state({ freq: 'WEEKLY', weekdays: [1] }), START, '2026-12-09').weekdays).toEqual([3]);
    expect(followStart(state({ freq: 'MONTHLY', monthDays: [7] }), START, '2026-12-20').monthDays).toEqual([20]);
    // Days the person picked stay.
    expect(followStart(state({ freq: 'WEEKLY', weekdays: [1, 3] }), START, '2026-12-09').weekdays).toEqual([1, 3]);
    expect(followStart(NO_REPEAT, START, '2026-12-09')).toBe(NO_REPEAT);
  });
});

describe('validation against the API limits', () => {
  it('has no problems for a good rule, or for none', () => {
    expect(repeatProblems(NO_REPEAT, START)).toEqual([]);
    expect(repeatProblems(state({ freq: 'WEEKLY', weekdays: [1, 3], interval: 2, end: 'count', count: 10 }), START)).toEqual([]);
  });
  it('wants an interval of 1 to 99 and a count of 1 to 1000', () => {
    expect(repeatProblems(state({ freq: 'DAILY', interval: 0 }), START)).toEqual(['interval']);
    expect(repeatProblems(state({ freq: 'DAILY', interval: 100 }), START)).toEqual(['interval']);
    expect(repeatProblems(state({ freq: 'DAILY', end: 'count', count: 1001 }), START)).toEqual(['count']);
    expect(repeatProblems(state({ freq: 'DAILY', end: 'count', count: 1000 }), START)).toEqual([]);
  });
  it('wants a weekly rule to include the weekday of the start and a monthly one the day of the month', () => {
    expect(repeatProblems(state({ freq: 'WEEKLY', weekdays: [] }), START)).toEqual(['weekdaysNone']);
    expect(repeatProblems(state({ freq: 'WEEKLY', weekdays: [2] }), START)).toEqual(['weekdayStart']);
    expect(repeatProblems(state({ freq: 'MONTHLY', monthDays: [] }), START)).toEqual(['monthDaysNone']);
    expect(repeatProblems(state({ freq: 'MONTHLY', monthDays: [8] }), START)).toEqual(['monthDayStart']);
  });
  it('wants the last day between the start and ten years after it', () => {
    expect(repeatProblems(state({ freq: 'DAILY', end: 'until', until: '' }), START)).toEqual(['untilMissing']);
    expect(repeatProblems(state({ freq: 'DAILY', end: 'until', until: '2026-12-06' }), START)).toEqual(['untilBeforeStart']);
    expect(repeatProblems(state({ freq: 'DAILY', end: 'until', until: '2036-12-07' }), START)).toEqual([]);
    expect(repeatProblems(state({ freq: 'DAILY', end: 'until', until: '2036-12-08' }), START)).toEqual(['untilTooFar']);
    expect(addYears('2028-02-29', 1)).toBe('2029-02-28');
    expect(addYears('2028-02-29', 4)).toBe('2032-02-29');
  });
  it('knows when a monthly rule skips short months', () => {
    expect(skipsShortMonths(state({ freq: 'MONTHLY', monthDays: [31] }))).toBe(true);
    expect(skipsShortMonths(state({ freq: 'MONTHLY', monthDays: [28] }))).toBe(false);
    expect(skipsShortMonths(state({ freq: 'WEEKLY', monthDays: [31] }))).toBe(false);
  });
});

describe('the rule in plain words', () => {
  const translate = (locale: 'en' | 'am') => {
    const t = createTranslator({ locale, messages: messages[locale] as never }) as unknown as (key: string, values?: Record<string, string | number>) => string;
    return {
      t: (key: string, values?: Record<string, string | number>) => t(key, values),
      weekdayName: (weekday: number) => weekdayName(weekday, locale),
      formatDate: (date: string) => dateText(date, 'gregorian', locale),
    };
  };
  const en = translate('en');
  const say = (over: Partial<RepeatState>) => describeRepeat(state(over), en);

  it('says nothing when it does not repeat', () => {
    expect(say({ freq: 'NONE' })).toBe('');
  });
  it('describes daily, weekly and monthly rules, with the interval and the end', () => {
    expect(say({ freq: 'DAILY' })).toBe('Every day');
    expect(say({ freq: 'DAILY', interval: 3 })).toBe('Every 3 days');
    expect(say({ freq: 'WEEKLY', weekdays: [1] })).toBe('Every week on Monday');
    expect(say({ freq: 'WEEKLY', weekdays: [3, 1] })).toBe('Every week on Monday and Wednesday');
    expect(say({ freq: 'WEEKLY', weekdays: [0, 1, 3], interval: 2 })).toBe('Every 2 weeks on Monday, Wednesday and Sunday');
    expect(say({ freq: 'MONTHLY', monthDays: [5, 20] })).toBe('Every month on day 5 and 20');
    expect(say({ freq: 'MONTHLY', monthDays: [1], interval: 3 })).toBe('Every 3 months on day 1');
    expect(say({ freq: 'WEEKLY', weekdays: [1], end: 'until', until: '2027-12-31' })).toBe('Every week on Monday, until December 31, 2027');
    expect(say({ freq: 'DAILY', end: 'count', count: 10 })).toBe('Every day, 10 times');
  });
  it('has the same sentences in Amharic', () => {
    const am = translate('am');
    expect(describeRepeat(state({ freq: 'DAILY' }), am)).toBe('በየቀኑ');
    expect(describeRepeat(state({ freq: 'WEEKLY', weekdays: [1, 3] }), am)).toBe('በየሳምንቱ ሰኞ እና ረቡዕ');
  });
});
