import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import type { FeedStudentTag } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from '../avatar';
import { Text } from '../ui';

import { Tint } from './tint';

/**
 * The students of an achievement: a round picture (a placeholder with initials when there is none) with a name chip under each,
 * exactly the tags the API sent for this viewer (it leaves out children the viewer may not know). The pictures stay in memory.
 */
export function StudentRow({ tags }: { tags: readonly FeedStudentTag[] }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  if (tags.length === 0) return null;
  return (
    <View
      accessibilityRole="summary"
      accessibilityLabel={t('mobile.feed.achievement.students', { names: tags.map((tag) => tag.displayName).join(', ') })}
      style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: spacing[4], paddingVertical: spacing[1] }}>
      {tags.map((tag) => (
        <View key={tag.studentId} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ width: 84, alignItems: 'center', gap: spacing[2] }}>
          <View style={{ padding: 3, borderRadius: radii.full, borderWidth: 3, borderColor: colors.primary }}>
            <Avatar mediaId={tag.avatar?.id} name={tag.displayName} seed={tag.studentId} size={56} maxInitials={1} memoryOnly />
          </View>
          <View style={{ maxWidth: 84, paddingHorizontal: spacing[3], paddingVertical: 2, borderRadius: radii.full, overflow: 'hidden' }}>
            <Tint color={colors.primary} opacity={0.16} />
            <Text variant="label" numberOfLines={1} style={{ textAlign: 'center' }}>
              {tag.displayName}
            </Text>
          </View>
        </View>
      ))}
    </View>
  );
}
