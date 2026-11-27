import { describe, expect, it } from 'vitest';

import { STAFF_ATTENDANCE_MANAGE, STAFF_ATTENDANCE_READ, hasStaffAttendance, staffAttendanceSchools, staffRefusal, type StaffAccessInput } from './access';

const SCHOOL_A = '00000000-0000-4000-8000-00000000000a';
const SCHOOL_B = '00000000-0000-4000-8000-00000000000b';

const scope = (type: string, permissions: string[], schoolId?: string, schoolName?: string): NonNullable<StaffAccessInput['scopes']>[number] => ({
  permissions,
  ...(schoolName ? { schoolName } : {}),
  scope: { type, ...(schoolId ? { schoolId } : {}) },
});

describe('who sees the staff roll', () => {
  it('a manager manages, a reader reads, and manage includes read', () => {
    const me = { scopes: [scope('SCHOOL', [STAFF_ATTENDANCE_MANAGE], SCHOOL_A, 'Alpha'), scope('SCHOOL', [STAFF_ATTENDANCE_READ], SCHOOL_B, 'Beta')] };
    expect(staffAttendanceSchools(me)).toEqual([
      { schoolId: SCHOOL_A, schoolName: 'Alpha', canManage: true },
      { schoolId: SCHOOL_B, schoolName: 'Beta', canManage: false },
    ]);
    expect(hasStaffAttendance(me)).toBe(true);
  });

  it('a teacher, a student or anybody without the permissions sees nothing (no teacher view of colleagues)', () => {
    expect(staffAttendanceSchools({ scopes: [] })).toEqual([]);
    expect(staffAttendanceSchools(undefined)).toEqual([]);
    // The attendance permissions of M7 are not the staff permissions.
    expect(hasStaffAttendance({ scopes: [scope('SCHOOL', ['school.attendance.manage', 'school.attendance.read', 'class.manage'], SCHOOL_A, 'Alpha')] })).toBe(false);
  });

  it('only a grant that names a school counts (campus and platform grants are left to the web)', () => {
    expect(hasStaffAttendance({ scopes: [scope('CAMPUS', [STAFF_ATTENDANCE_MANAGE], SCHOOL_A), scope('PLATFORM', [STAFF_ATTENDANCE_MANAGE])] })).toBe(false);
  });

  it('merges several grants at one school and sorts by name', () => {
    const me = {
      scopes: [
        scope('SCHOOL', [STAFF_ATTENDANCE_READ], SCHOOL_B, 'Beta'),
        scope('SCHOOL', [STAFF_ATTENDANCE_READ], SCHOOL_A, 'Alpha'),
        scope('SCHOOL', [STAFF_ATTENDANCE_MANAGE], SCHOOL_A, 'Alpha'),
      ],
    };
    expect(staffAttendanceSchools(me).map((school) => [school.schoolName, school.canManage])).toEqual([
      ['Alpha', true],
      ['Beta', false],
    ]);
  });
});

describe('what a refusal says', () => {
  it('tells apart "switched off", "feature off", "not allowed" and "not there", from the API\'s stable code', () => {
    expect(staffRefusal(403, 'staff_attendance.disabled')).toBe('disabled');
    expect(staffRefusal(403, 'attendance.feature_disabled')).toBe('featureOff');
    expect(staffRefusal(403, 'staff_attendance.access_denied')).toBe('denied');
    expect(staffRefusal(404, 'school.not_found')).toBe('notFound');
    expect(staffRefusal(500, undefined)).toBeNull();
    expect(staffRefusal(undefined, undefined)).toBeNull();
  });
});
