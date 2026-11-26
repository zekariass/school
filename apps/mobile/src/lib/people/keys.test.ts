import { describe, expect, it } from 'vitest';

import { classKey, isPersistablePeopleQuery, myClassesKey, rosterKey, studentKey, studentSearchKey } from './keys';

describe('what the offline cache may keep', () => {
  it('keeps classes and teachers, but not rosters (children\'s names live in the owner-scoped cache table)', () => {
    expect(isPersistablePeopleQuery(myClassesKey)).toBe(true);
    expect(isPersistablePeopleQuery(classKey('c'))).toBe(true);
    expect(isPersistablePeopleQuery(rosterKey('c'))).toBe(false);
  });

  it('never keeps student records or school-wide student searches', () => {
    expect(isPersistablePeopleQuery(studentKey('school', 'student'))).toBe(false);
    expect(isPersistablePeopleQuery(studentSearchKey('school', 'abe'))).toBe(false);
  });

  it('leaves other queries alone', () => {
    expect(isPersistablePeopleQuery(['me'])).toBe(true);
  });
});
