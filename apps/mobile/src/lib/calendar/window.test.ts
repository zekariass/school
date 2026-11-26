import { describe, expect, it } from 'vitest';

import { rangeFor } from './grid';
import { fetchWindow, MAX_WINDOW_DAYS, windowFits } from './window';

describe('the fetch window of a visible range', () => {
  it('pads the visible days by 14 hours so an item of the first or last day in any zone is included', () => {
    expect(fetchWindow({ first: '2026-11-23', last: '2026-11-29' })).toEqual({
      from: '2026-11-22T10:00:00.000Z',
      to: '2026-11-30T14:00:00.000Z',
    });
  });
  it('covers the whole day of the first and last date in the extreme zones', () => {
    const window = fetchWindow({ first: '2026-11-23', last: '2026-11-23' })!;
    // The 23rd starts at 2026-11-22T10:00Z in UTC+14 and ends at 2026-11-24T12:00Z in UTC-12.
    expect(Date.parse(window.from)).toBeLessThanOrEqual(Date.parse('2026-11-22T10:00:00Z'));
    expect(Date.parse(window.to)).toBeGreaterThanOrEqual(Date.parse('2026-11-24T12:00:00Z'));
  });
  it('is short enough for a month grid and an agenda page, and refused beyond the API limit', () => {
    expect(windowFits(rangeFor('month', '2026-11-26', 'gregorian', 1))).toBe(true);
    expect(windowFits(rangeFor('month', '2026-09-26', 'ethiopian', 1))).toBe(true);
    expect(windowFits(rangeFor('agenda', '2026-11-26', 'gregorian', 1, 30))).toBe(true);
    const window = fetchWindow({ first: '2026-01-01', last: '2026-12-31' });
    expect(window).toBeNull();
    expect(MAX_WINDOW_DAYS).toBe(366);
  });
  it('is empty for an empty range', () => {
    expect(fetchWindow({ first: '2026-11-30', last: '2026-11-23' })).toBeNull();
  });
  it('stays at most 366 days for a month grid', () => {
    const window = fetchWindow(rangeFor('month', '2026-11-26', 'gregorian', 1))!;
    expect((Date.parse(window.to) - Date.parse(window.from)) / 86_400_000).toBeLessThanOrEqual(MAX_WINDOW_DAYS);
  });
});
