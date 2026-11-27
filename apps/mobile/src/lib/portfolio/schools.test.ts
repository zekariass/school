import { describe, expect, it } from 'vitest';

import { schoolsOf } from './schools';

describe('schoolsOf', () => {
  const memberships = [
    { type: 'STUDENT', schoolId: 'a', schoolName: 'Alpha' },
    { type: 'TEACHER', schoolId: 'b', schoolName: 'Beta' },
    { type: 'STUDENT', schoolId: 'a', schoolName: 'Alpha' },
    { type: 'STUDENT', schoolId: 'c', schoolName: 'Gamma' },
  ];
  it('lists each school of the type once, in order', () => {
    expect(schoolsOf(memberships, 'STUDENT')).toEqual([
      { id: 'a', name: 'Alpha' },
      { id: 'c', name: 'Gamma' },
    ]);
    expect(schoolsOf(memberships, 'TEACHER')).toEqual([{ id: 'b', name: 'Beta' }]);
  });
  it('is empty without memberships', () => {
    expect(schoolsOf(undefined, 'STUDENT')).toEqual([]);
    expect(schoolsOf([], 'STUDENT')).toEqual([]);
  });
});
