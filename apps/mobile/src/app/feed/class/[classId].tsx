import { Stack, router, useLocalSearchParams } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ClassFeed } from '@/components/feed/class-feed';
import { Button, EmptyState } from '@/components/ui';
import { useMyClass } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** A class feed for its teachers and its students (parents see their children's classes in the home feed). */
export default function ClassFeedScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { classId, schoolId: schoolParam } = useLocalSearchParams<{ classId: string; schoolId?: string }>();
  const { cls, query: mine } = useMyClass(classId);
  const schoolId = schoolParam ?? cls?.schoolId;

  if (!schoolId || !classId) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.background, padding: spacing[5] }}>
        {mine.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : (
          <EmptyState icon={{ ios: 'questionmark.circle', android: 'help' }} title={t('mobile.classes.notFoundTitle')} body={t('mobile.classes.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => router.back()} />
          </EmptyState>
        )}
      </View>
    );
  }
  return (
    <>
      <Stack.Screen options={{ title: cls?.name ?? t('mobile.feed.classFeedTitle') }} />
      <ClassFeed schoolId={schoolId} classId={classId} />
    </>
  );
}
