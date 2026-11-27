import { describe, expect, it } from 'vitest';

import { classGroupsKey, classRollsKey, classSubjectsKey, isPersistableAttendanceQuery, rollKey } from './keys';

describe('attendance query keys', () => {
  it('a roll key sits under the class prefix, so one update reaches every date and slot', () => {
    expect(rollKey('c1', '2026-11-25', 'DAY').slice(0, 3)).toEqual([...classRollsKey('c1')]);
    expect(rollKey('c1', null, null)).toEqual(['attendance', 'roll', 'c1', 'today', 'first', 'class']);
  });

  it('two slots, and a group of a slot, are different queries (switching never shows another roll)', () => {
    expect(rollKey('c1', '2026-11-25', 'P1')).not.toEqual(rollKey('c1', '2026-11-25', 'P2'));
    expect(rollKey('c1', '2026-11-25', 'P1', 'g1')).not.toEqual(rollKey('c1', '2026-11-25', 'P1'));
    expect(rollKey('c1', '2026-11-25', 'P1', 'g1').slice(0, 3)).toEqual([...classRollsKey('c1')]);
  });

  it('keeps every attendance query out of the persisted cache', () => {
    expect(isPersistableAttendanceQuery(rollKey('c1', null, null))).toBe(false);
    expect(isPersistableAttendanceQuery(classSubjectsKey('c1'))).toBe(false);
    expect(isPersistableAttendanceQuery(classGroupsKey('c1'))).toBe(false);
    expect(isPersistableAttendanceQuery(['attendance', 'history', 's', 'st'])).toBe(false);
    expect(isPersistableAttendanceQuery(['classes', 'mine'])).toBe(true);
  });
});
