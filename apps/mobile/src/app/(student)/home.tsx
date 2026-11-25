import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ClassCard } from '@/components/class-card';
import { PlaceholderScreen } from '@/components/placeholder-screen';
import { ProblemBanner } from '@/components/problem-banner';
import { PushPromptCard } from '@/components/push-prompt-card';
import { TabHeader } from '@/components/tab-header';
import { EmptyState, Screen, Text } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { teacherNames } from '@/lib/people/classes';
import { useClassTeachers, useMyClasses } from '@/lib/people/queries';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import type { components } from '@school/api-client';

type MyClass = components['schemas']['MyClass'];

/** The developer role preview has no account behind it, so it keeps the placeholder. */
export default function HomeTab() {
  const { preview } = useSession();
  return preview ? <PlaceholderScreen kind="studentHome" /> : <StudentHome />;
}

/** The student's home: their classes as big friendly cards. */
function StudentHome() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const me = useMe();
  const classes = useMyClasses();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([classes.refetch(), queryClient.invalidateQueries({ queryKey: ['classes', 'teachers'] })]);
    } finally {
      setRefreshing(false);
    }
  }

  const mine = (classes.data ?? []).filter((cls) => cls.role === 'STUDENT');
  const name = me.data?.givenName;

  return (
    <Screen
      scroll
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[4] }}>
      <TabHeader />
      <PushPromptCard />
      <Text variant="title">{name ? t('mobile.student.greeting', { name }) : t('mobile.student.greetingNoName')}</Text>

      {classes.isPending ? (
        <View style={{ paddingVertical: spacing[8] }}>
          <ActivityIndicator color={colors.primary} />
        </View>
      ) : classes.isError && !classes.data ? (
        <ProblemBanner
          error={classes.error}
          fallbackTitle={t('mobile.student.errorTitle')}
          fallbackBody={t('mobile.student.errorBody')}
          onRetry={() => void classes.refetch()}
        />
      ) : mine.length === 0 ? (
        <EmptyState
          icon={{ ios: 'star.fill', android: 'star' }}
          title={t('mobile.student.emptyTitle')}
          body={t('mobile.student.emptyBody')}
        />
      ) : (
        <View style={{ gap: spacing[3] }}>
          <Text variant="heading">{t('mobile.student.myClasses')}</Text>
          {mine.map((cls) => (
            <StudentClassCard key={cls.id} cls={cls} />
          ))}
        </View>
      )}
    </Screen>
  );
}

function StudentClassCard({ cls }: { cls: MyClass }) {
  const t = useTranslations();
  const teachers = useClassTeachers(cls.schoolId, cls.id);
  const names = teachers.data ? teacherNames(teachers.data) : '';
  const lines = [names ? t('mobile.student.withTeachers', { names }) : null, cls.gradeLevelName, cls.schoolName];
  return (
    <ClassCard
      cls={cls}
      lines={lines}
      accessibilityLabel={[cls.name, names ? t('mobile.student.withTeachers', { names }) : null].filter(Boolean).join(', ')}
    />
  );
}
