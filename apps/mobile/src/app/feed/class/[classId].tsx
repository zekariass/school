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
  const { cls, query: mine } = useMyClass(classId);  const schoolId = schoolParam ?? cls?.schoolId;

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
  // The class switcher: the person's other active classes in the same school (the title is a dropdown when there are any).
  const options = (mine.data ?? [])
    .filter((item) => item.status === 'ACTIVE' && item.schoolId === schoolId)
    .map((item) => ({ value: item.id, label: item.name }));
  const switcher = {
    label: t('mobile.feed.header.switchClass'),
    value: classId,
    options,
    onChange: (next: string) => router.replace({ pathname: '/feed/class/[classId]', params: { classId: next, schoolId } }),
  };
  return (
    <>
      <Stack.Screen options={{ title: cls?.name ?? t('mobile.feed.classFeedTitle') }} />
      <ClassFeed key={classId} schoolId={schoolId} classId={classId} schoolName={cls?.schoolName} className={cls?.name} switcher={switcher} />
    </>
  );
}
