import { describe, expect, it } from 'vitest';

import { ethiopianMonthName, formatEthiopianDate } from './format';

const newYear2018 = { year: 2018, month: 1, day: 1 };

describe('formatEthiopianDate', () => {
  it('formats in Amharic', () => {
    expect(formatEthiopianDate(newYear2018, { language: 'am', style: 'long' })).toBe('መስከረም 1 ቀን 2018 ዓ.ም.');
    expect(formatEthiopianDate(newYear2018, { language: 'am-ET' })).toBe('መስከረም 1, 2018');
  });

  it('formats in English transliteration', () => {
    expect(formatEthiopianDate(newYear2018, { style: 'long' })).toBe('Meskerem 1, 2018 E.C.');
    expect(formatEthiopianDate({ year: 2015, month: 13, day: 6 })).toBe('Pagume 6, 2015');
  });

  it('formats numerically as day/month/year', () => {
    expect(formatEthiopianDate({ year: 2018, month: 3, day: 14 }, { style: 'numeric' })).toBe('14/3/2018');
  });

  it('falls back to English names for other UI languages', () => {
    expect(ethiopianMonthName(5, 'om')).toBe('Tir');
  });

  it('rejects invalid months', () => {
    expect(() => ethiopianMonthName(14)).toThrow(RangeError);
  });
});
