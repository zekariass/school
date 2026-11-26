import { useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { ClassFeed, SchoolFeed } from '@/components/feed/class-feed';
import { ProblemBanner } from '@/components/problem-banner';
import { TabHeader } from '@/components/tab-header';
import { EmptyState, Screen, Text } from '@/components/ui';
import { useMyClasses } from '@/lib/people/queries';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The class feed of a student who signed in with a class code (or a phone): the class's posts and stories, and the school's.
 * Reacting, commenting and sharing show only where the API allows them (comments by students are off unless the school
 * switched them on).
 */
export default function StudentFeedTab() {
  const t = useTranslations();
  const { preview } = useSession();
  if (preview) return <EmptyState icon={{ ios: 'photo.on.rectangle.angled', android: 'photo_library' }} title={t('mobile.feed.tab')} body={t('mobile.feed.empty.classBody')} />;
  return <StudentFeed />;
}

function StudentFeed() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const classes = useMyClasses();
  const mine = (classes.data ?? []).filter((cls) => cls.role === 'STUDENT' && cls.status === 'ACTIVE');
  const [pick, setPick] = useState<string | null>(null);
  const current = mine.find((cls) => cls.id === pick) ?? mine[0];
  const showSchool = pick === 'school';

  if (classes.isPending) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing[8] }}>
          <ActivityIndicator color={colors.primary} />
        </View>
      </Screen>
    );
  }
  if (classes.isError && !classes.data) {
    return (
      <Screen>
        <ProblemBanner error={classes.error} fallbackTitle={t('mobile.student.errorTitle')} fallbackBody={t('mobile.student.errorBody')} onRetry={() => void classes.refetch()} />
      </Screen>
    );
  }
  if (!current) {
    return (
      <Screen>
        <EmptyState icon={{ ios: 'star.fill', android: 'star' }} title={t('mobile.student.emptyTitle')} body={t('mobile.student.emptyBody')} />
      </Screen>
    );
  }

  const before = (
    <View style={{ gap: spacing[3] }}>
      <TabHeader />
      <Text variant="title">{t('mobile.feed.tab')}</Text>
      <Chips
        accessibilityLabel={t('mobile.feed.whichFeed')}
        value={showSchool ? 'school' : current.id}
        onChange={setPick}
        chips={[...mine.map((cls) => ({ value: cls.id, label: cls.name })), { value: 'school', label: t('mobile.feed.schoolWide') }]}
      />
    </View>
  );
  return showSchool ? (
    <SchoolFeed key="school" schoolId={current.schoolId} before={before} edges={['top']} />
  ) : (
    <ClassFeed key={current.id} schoolId={current.schoolId} classId={current.id} before={before} edges={['top']} />
  );
}
