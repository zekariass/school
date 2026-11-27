import { Stack, useLocalSearchParams } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ItemList } from '@/components/portfolio/item-list';
import { Text } from '@/components/ui';
import { useMyClass } from '@/lib/people/queries';
import { useReviewQueue } from '@/lib/portfolio/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Work waiting for a teacher's decision in one class, oldest first. The API lists only what the caller reviews (a subject
 * teacher sees their subject's items) and answers 403 to anybody else, which is shown in its own words. Tapping an item opens
 * it, where the decision is made.
 */
export default function ReviewQueue() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { classId, schoolId: schoolParam } = useLocalSearchParams<{ classId: string; schoolId?: string }>();
  const { cls, query: mine } = useMyClass(classId);
  const schoolId = schoolParam ?? cls?.schoolId;
  const { query, items } = useReviewQueue(schoolId, classId);

  if (!schoolId) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center', padding: spacing[5] }}>
        {mine.isPending ? <ActivityIndicator color={colors.primary} /> : <Text>{t('mobile.classes.notFoundBody')}</Text>}
      </View>
    );
  }
  return (
    <>
      <Stack.Screen options={{ title: cls?.name ? t('mobile.portfolio.queue.titleFor', { name: cls.name }) : t('mobile.portfolio.queue.title') }} />
      <ItemList
        query={query}
        items={items}
        showStudent
        edges={['bottom']}
        header={
          <Text variant="caption" style={{ paddingBottom: spacing[2] }}>
            {t('mobile.portfolio.queue.hint')}
          </Text>
        }
        empty={{ icon: { ios: 'checkmark.seal', android: 'verified' }, title: t('mobile.portfolio.queue.emptyTitle'), body: t('mobile.portfolio.queue.emptyBody') }}
      />
    </>
  );
}
