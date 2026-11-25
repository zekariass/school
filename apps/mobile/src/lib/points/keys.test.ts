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
  it('persists no points query at all: what a teacher needs offline sits in the explicit cache table', () => {
    expect(isPersistablePointsQuery(categoriesKey('s', 'c'))).toBe(false);
    expect(isPersistablePointsQuery(templatesKey('s', 'c'))).toBe(false);
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
