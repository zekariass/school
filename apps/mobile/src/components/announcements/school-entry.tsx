import { router } from 'expo-router';
import { useTranslations } from 'use-intl';

import { useAnnouncementCapabilities } from '@/lib/announcements/queries';

import { Button } from '../ui';

/**
 * "My announcements" of a school, for a teacher or a manager who may compose there (from the capabilities; the API checks
 * again). Nothing is shown to a teacher the school does not allow to announce, or while the answer is not known.
 */
export function SchoolAnnouncementsButton({ schoolId, schoolName }: { schoolId: string; schoolName?: string | undefined }) {
  const t = useTranslations();
  const caps = useAnnouncementCapabilities(schoolId);
  if (!caps.data?.canCompose) return null;
  return (
    <Button
      variant="ghost"
      label={schoolName ? t('mobile.announcements.schoolEntryNamed', { school: schoolName }) : t('mobile.announcements.schoolEntry')}
      onPress={() => router.push({ pathname: '/announcements/mine', params: { schoolId } })}
    />
  );
}

/** "New announcement" for one class, on the class screen: shown when the capabilities list the class (or the person is a manager). */
export function ClassAnnouncementButton({ schoolId, classId }: { schoolId: string; classId: string }) {
  const t = useTranslations();
  const caps = useAnnouncementCapabilities(schoolId);
  const data = caps.data;
  if (!data?.canCompose || !(data.schoolWide || data.classes.some((cls) => cls.id === classId))) return null;
  return (
    <Button
      variant="secondary"
      label={t('mobile.announcements.newForClass')}
      onPress={() => router.push({ pathname: '/announcements/compose', params: { schoolId, classId } })}
    />
  );
}
