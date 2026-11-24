import { describe, expect, it } from 'vitest';

import {
  civilDateInTimeZone,
  daysInEthiopianMonth,
  ethiopianToGregorian,
  gregorianToEthiopian,
  gregorianToJdn,
  instantToEthiopian,
  isEthiopianLeapYear,
  isGregorianLeapYear,
  isValidEthiopianDate,
  jdnToEthiopian,
  jdnToGregorian,
} from './convert';

describe('Gregorian JDN', () => {
  it('matches known Julian Day Numbers', () => {
    expect(gregorianToJdn({ year: 2000, month: 1, day: 1 })).toBe(2451545);
    expect(gregorianToJdn({ year: 1970, month: 1, day: 1 })).toBe(2440588);
  });
});

describe('Ethiopian New Year (Enkutatash)', () => {
  // 1 Meskerem falls on 11 September, or 12 September when the next Gregorian year
  // is a leap year (valid for 1901–2098).
  for (let gYear = 1901; gYear <= 2098; gYear++) {
    const expectedDay = isGregorianLeapYear(gYear + 1) ? 12 : 11;
    it(`1 Meskerem ${gYear - 7} = ${expectedDay} Sep ${gYear}`, () => {
      expect(ethiopianToGregorian({ year: gYear - 7, month: 1, day: 1 })).toEqual({
        year: gYear,
        month: 9,
        day: expectedDay,
      });
    });
  }

  it('maps the 2018 E.C. new year', () => {
    expect(gregorianToEthiopian({ year: 2025, month: 9, day: 11 })).toEqual({ year: 2018, month: 1, day: 1 });
  });
});

describe('Pagume and leap years', () => {
  it('has 6 Pagume days in a leap year (Y % 4 === 3)', () => {
    expect(isEthiopianLeapYear(2015)).toBe(true);
    expect(daysInEthiopianMonth(2015, 13)).toBe(6);
    expect(ethiopianToGregorian({ year: 2015, month: 13, day: 6 })).toEqual({ year: 2023, month: 9, day: 11 });
  });

  it('maps Gregorian dates onto the leap day and the following new year (regression)', () => {
    expect(gregorianToEthiopian({ year: 2023, month: 9, day: 11 })).toEqual({ year: 2015, month: 13, day: 6 });
    expect(gregorianToEthiopian({ year: 2023, month: 9, day: 12 })).toEqual({ year: 2016, month: 1, day: 1 });
    expect(gregorianToEthiopian({ year: 2024, month: 1, day: 1 })).toEqual({ year: 2016, month: 4, day: 22 });
  });

  it('has 5 Pagume days otherwise', () => {
    expect(isEthiopianLeapYear(2017)).toBe(false);
    expect(isValidEthiopianDate({ year: 2017, month: 13, day: 6 })).toBe(false);
    expect(ethiopianToGregorian({ year: 2017, month: 13, day: 5 })).toEqual({ year: 2025, month: 9, day: 10 });
  });
});

describe('round trips', () => {
  it('Gregorian → Ethiopian → Gregorian is identity for every day 1900–2100', () => {
    const start = gregorianToJdn({ year: 1900, month: 1, day: 1 });
    const end = gregorianToJdn({ year: 2100, month: 12, day: 31 });
    for (let jdn = start; jdn <= end; jdn++) {
      const g = jdnToGregorian(jdn);
      const e = gregorianToEthiopian(g);
      expect(isValidEthiopianDate(e)).toBe(true);
      expect(ethiopianToGregorian(e)).toEqual(g);
    }
  });

  it('consecutive days advance the Ethiopian date by exactly one day', () => {
    const start = gregorianToJdn({ year: 2020, month: 1, day: 1 });
    for (let jdn = start; jdn < start + 3 * 366; jdn++) {
      const a = jdnToEthiopian(jdn);
      const b = jdnToEthiopian(jdn + 1);
      const rolledMonth = b.day === 1 && (b.month === a.month + 1 || (b.month === 1 && a.month === 13));
      expect(b.day === a.day + 1 || rolledMonth).toBe(true);
    }
  });
});

describe('validation', () => {
  it('rejects invalid input', () => {
    expect(() => gregorianToEthiopian({ year: 2025, month: 2, day: 30 })).toThrow(RangeError);
    expect(() => ethiopianToGregorian({ year: 2018, month: 14, day: 1 })).toThrow(RangeError);
    expect(() => ethiopianToGregorian({ year: 2018, month: 1, day: 31 })).toThrow(RangeError);
  });
});

describe('timezone-aware instants', () => {
  it('uses the civil date in the given timezone', () => {
    // 2025-09-10T22:30Z is already 11 Sep 01:30 in Addis Ababa (UTC+3) → new year.
    const instant = new Date('2025-09-10T22:30:00Z');
    expect(civilDateInTimeZone(instant, 'UTC')).toEqual({ year: 2025, month: 9, day: 10 });
    expect(instantToEthiopian(instant, 'Africa/Addis_Ababa')).toEqual({ year: 2018, month: 1, day: 1 });
    expect(instantToEthiopian(instant, 'UTC')).toEqual({ year: 2017, month: 13, day: 5 });
  });
});
