import { describe, expect, it } from 'vitest';

import {
  categoriesKey,
  classPointsKey,
  homeCategoriesKey,
  isPersistablePointsQuery,
  studentPointsKey,
  templatesKey,
  timelineKey,
} from './keys';

describe('isPersistablePointsQuery', () => {
  it('keeps only what a teacher needs to award offline', () => {
    expect(isPersistablePointsQuery(categoriesKey('s', 'c'))).toBe(true);
    expect(isPersistablePointsQuery(templatesKey('s', 'c'))).toBe(true);
  });

  it('never persists children\'s totals, timelines, notes or home data', () => {
    expect(isPersistablePointsQuery(classPointsKey('c'))).toBe(false);
    expect(isPersistablePointsQuery(studentPointsKey('s', 'st'))).toBe(false);
    expect(isPersistablePointsQuery(timelineKey('s', 'st', 'HOME'))).toBe(false);
    expect(isPersistablePointsQuery(homeCategoriesKey('s', 'st'))).toBe(false);
  });

  it('does not touch other queries', () => {
    expect(isPersistablePointsQuery(['classes', 'mine'])).toBe(true);
  });
});
