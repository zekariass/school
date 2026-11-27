import { router } from 'expo-router';
import { useTranslations } from 'use-intl';

import { Button } from '@/components/ui';
import { staffAttendanceSchools } from '@/lib/attendance/staff/access';
import { useMe } from '@/lib/auth/use-me';

/**
 * "Staff attendance" for people who hold a staff-attendance permission at a school (from `GET /me`; the API checks
 * again). Nothing is shown to anybody else, a teacher included: there is no teacher view of colleagues' records.
 */
export function StaffAttendanceButton() {
  const t = useTranslations();
  const me = useMe();
  const schools = staffAttendanceSchools(me.data);
  if (schools.length === 0) return null;
  const single = schools.length === 1 ? schools[0] : undefined;
  return (
    <Button
      variant="ghost"
      label={single?.schoolName ? t('mobile.attendance.staff.entryNamed', { school: single.schoolName }) : t('mobile.attendance.staff.entry')}
      onPress={() => router.push(single ? { pathname: '/staff-attendance', params: { schoolId: single.schoolId } } : '/staff-attendance')}
    />
  );
}
