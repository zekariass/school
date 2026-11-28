import { Stack, useLocalSearchParams } from 'expo-router';
import { useTranslations } from 'use-intl';

import { SchoolFeed } from '@/components/feed/class-feed';
import { useMe } from '@/lib/auth/use-me';

/** The school-wide feed for members of the school (the school-wide composer shows for those the API allows). */
export default function SchoolFeedScreen() {
  const t = useTranslations();
  const { schoolId } = useLocalSearchParams<{ schoolId: string }>();
  const me = useMe();
  const schoolName = me.data?.memberships.find((membership) => membership.schoolId === schoolId)?.schoolName;
  return (
    <>
      <Stack.Screen options={{ title: schoolName ?? t('mobile.feed.schoolFeedTitle') }} />
      <SchoolFeed schoolId={schoolId!} schoolName={schoolName} />
    </>
  );
}
