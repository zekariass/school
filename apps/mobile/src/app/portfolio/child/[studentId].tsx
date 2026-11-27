import { Stack, useLocalSearchParams } from 'expo-router';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ItemList } from '@/components/portfolio/item-list';
import { Text } from '@/components/ui';
import { useMyChildren } from '@/lib/family/queries';
import { useChildrenItems } from '@/lib/portfolio/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * A guardian's page for one child's portfolio: the approved work the family may see (the API lists only that, and withholds
 * the pictures when consent is missing). Opening an item shows it in full and lets the guardian write an encouraging comment
 * when the API allows it.
 */
export default function ChildPortfolio() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { studentId } = useLocalSearchParams<{ studentId: string }>();
  const children = useMyChildren();
  const child = children.data?.children.find((candidate) => candidate.studentId === studentId);
  const { query, items } = useChildrenItems(studentId ?? null, !!studentId);

  return (
    <>
      <Stack.Screen options={{ title: child ? t('mobile.portfolio.child.titleFor', { name: child.givenName }) : t('mobile.portfolio.child.title') }} />
      <ItemList
        query={query}
        items={items}
        edges={['bottom']}
        header={
          <View style={{ paddingBottom: spacing[2] }}>
            <Text variant="caption">{t('mobile.portfolio.child.hint')}</Text>
          </View>
        }
        empty={{ icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' }, title: t('mobile.portfolio.child.emptyTitle'), body: t('mobile.portfolio.child.emptyBody') }}
      />
    </>
  );
}
