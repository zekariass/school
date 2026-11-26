import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import {
  CALENDAR_ROOT,
  capabilitiesKey,
  classChoicesKey,
  eventKey,
  isPersistableCalendarQuery,
  itemsKey,
  sameFilter,
  sourcesParam,
  type CalendarFilter,
} from './keys';

const FILTER: CalendarFilter = { schoolId: 's', studentId: null, sources: ['HOLIDAY', 'EVENT'] };
const ALL_KEYS = [itemsKey(FILTER, { first: '2026-11-01', last: '2026-12-06' }), eventKey('e'), capabilitiesKey('s'), classChoicesKey('s')];

describe('persistence of calendar queries', () => {
  it('keeps every calendar query out of the persisted cache: titles of parent meetings and who answered are memory only', () => {
    for (const key of ALL_KEYS) {
      expect(key[0]).toBe(CALENDAR_ROOT);
      expect(isPersistableCalendarQuery(key)).toBe(false);
      // ... also through the one predicate the query provider really uses
      expect(isPersistableQuery(key)).toBe(false);
    }
  });
  it('does not touch other queries', () => {
    expect(isPersistableCalendarQuery(['classes', 'mine'])).toBe(true);
    expect(isPersistableQuery(['classes', 'mine'])).toBe(true);
  });
  it('still keeps the other private roots out', () => {
    for (const root of ['feed', 'announcements', 'notifications', 'media-url']) expect(isPersistableQuery([root, 'x'])).toBe(false);
  });
});

describe('window keys', () => {
  it('writes the sources in a fixed order, so the same choice is one cache entry', () => {
    expect(sourcesParam(['PERSONAL_EVENT', 'EVENT'])).toBe('EVENT,PERSONAL_EVENT');
    expect(sourcesParam(['ACADEMIC_PERIOD', 'HOLIDAY', 'EVENT', 'PERSONAL_EVENT'])).toBe('EVENT,PERSONAL_EVENT,HOLIDAY,ACADEMIC_PERIOD');
    const a = itemsKey({ ...FILTER, sources: ['EVENT', 'HOLIDAY'] }, { first: 'a', last: 'b' });
    const b = itemsKey({ ...FILTER, sources: ['HOLIDAY', 'EVENT'] }, { first: 'a', last: 'b' });
    expect(a).toEqual(b);
  });
  it('tells a change of days from a change of filter', () => {
    const march = itemsKey(FILTER, { first: '2026-03-01', last: '2026-04-05' });
    const april = itemsKey(FILTER, { first: '2026-04-01', last: '2026-05-03' });
    expect(sameFilter(march, april)).toBe(true);
    expect(sameFilter(march, itemsKey({ ...FILTER, studentId: 'child' }, { first: '2026-03-01', last: '2026-04-05' }))).toBe(false);
    expect(sameFilter(march, itemsKey({ ...FILTER, schoolId: null }, { first: '2026-03-01', last: '2026-04-05' }))).toBe(false);
    expect(sameFilter(march, itemsKey({ ...FILTER, sources: ['EVENT'] }, { first: '2026-03-01', last: '2026-04-05' }))).toBe(false);
  });
});
