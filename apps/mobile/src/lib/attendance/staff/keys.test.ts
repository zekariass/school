import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import { STAFF_ATTENDANCE_ROOT, isPersistableStaffAttendanceQuery, staffDayKey, staffDaysKey, staffRecordKey, staffSchoolKey } from './keys';

describe('staff attendance query keys', () => {
  it('every key sits under one private root', () => {
    for (const key of [staffSchoolKey('s'), staffDayKey('s', '2026-11-25'), staffDaysKey('s'), staffRecordKey('s', 'r')]) {
      expect(key[0]).toBe(STAFF_ATTENDANCE_ROOT);
    }
  });

  it('a school prefix reaches every loaded day of the school', () => {
    expect(staffDayKey('s', '2026-11-25').slice(0, 3)).toEqual([...staffDaysKey('s')]);
  });

  it('keeps staff names, statuses and notes out of the persisted cache', () => {
    expect(isPersistableStaffAttendanceQuery(staffDayKey('s', '2026-11-25'))).toBe(false);
    expect(isPersistableStaffAttendanceQuery(staffRecordKey('s', 'r'))).toBe(false);
    expect(isPersistableStaffAttendanceQuery(staffSchoolKey('s'))).toBe(false);
    expect(isPersistableStaffAttendanceQuery(['classes', 'mine'])).toBe(true);
  });

  it('and the one predicate the app persists with refuses them too', () => {
    expect(isPersistableQuery(staffDayKey('s', '2026-11-25'))).toBe(false);
    expect(isPersistableQuery(staffRecordKey('s', 'r'))).toBe(false);
    expect(isPersistableQuery(staffSchoolKey('s'))).toBe(false);
  });
});
