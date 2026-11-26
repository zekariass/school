import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ClassCard } from '@/components/class-card';
import { PlaceholderScreen } from '@/components/placeholder-screen';
import { ProblemBanner } from '@/components/problem-banner';
import { PushPromptCard } from '@/components/push-prompt-card';
import { OfflineNote } from '@/components/sync/offline-note';
import { TabHeader } from '@/components/tab-header';
import { EmptyState, Screen, Text } from '@/components/ui';
import { useMyClasses } from '@/lib/people/queries';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** The developer role preview has no account behind it, so it keeps the placeholder. */
export default function ClassesTab() {
  const { preview } = useSession();
  return preview ? <PlaceholderScreen kind="teacherClasses" /> : <TeacherClasses />;
}

/** "My classes": the classes the signed-in teacher teaches, as cards. */
function TeacherClasses() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const classes = useMyClasses();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);

  async function refresh() {
    setRefreshing(true);
    try {
      await classes.refetch();
      await queryClient.invalidateQueries({ queryKey: ['classes', 'roster'] });
    } finally {
      setRefreshing(false);
    }
  }

  const mine = (classes.data ?? []).filter((cls) => cls.role === 'TEACHER');
  const multiSchool = new Set(mine.map((cls) => cls.schoolId)).size > 1;

  return (
    <Screen
      scroll
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[4] }}>
      <TabHeader />
      <PushPromptCard />
      <Text variant="title">{t('mobile.classes.title')}</Text>
      <OfflineNote fromCache={classes.fromCache} fetchedAt={classes.fetchedAt} />

      {classes.isPending ? (
        <View style={{ paddingVertical: spacing[8] }}>
          <ActivityIndicator color={colors.primary} />
        </View>
      ) : classes.isError && !classes.data ? (
        <ProblemBanner
          error={classes.error}
          fallbackTitle={t('mobile.classes.errorTitle')}
          fallbackBody={t('mobile.classes.errorBody')}
          onRetry={() => void classes.refetch()}
        />
      ) : mine.length === 0 ? (
        <EmptyState
          icon={{ ios: 'person.3.fill', android: 'groups' }}
          title={t('mobile.empty.teacherClasses.title')}
          body={t('mobile.empty.teacherClasses.body')}
        />
      ) : (
        <View style={{ gap: spacing[3] }}>
          {mine.map((cls) => {
            const students = t('mobile.classes.studentCount', { count: cls.studentCount });
            const lines = [
              cls.gradeLevelName,
              students,
              multiSchool ? cls.schoolName : null,
              cls.status === 'ARCHIVED' ? t('mobile.classes.archived') : null,
            ];
            return (
              <ClassCard
                key={cls.id}
                cls={cls}
                lines={lines}
                accessibilityLabel={[cls.name, cls.gradeLevelName, students].filter(Boolean).join(', ')}
                onPress={() => router.push({ pathname: '/class/[classId]', params: { classId: cls.id } })}
              />
            );
          })}
        </View>
      )}
    </Screen>
  );
}
