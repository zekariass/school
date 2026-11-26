import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { syncEngine, usePendingOperations } from '@/lib/sync/runtime';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * "3 waiting to send": everything this teacher queued for the class (marks and awards). Tap to try now. Hidden
 * when nothing waits.
 */
export function QueueIndicator({ classId }: { classId: string }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const waiting = usePendingOperations().filter((item) => item.classId === classId).length;
  if (waiting === 0) return null;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('mobile.sync.waiting', { count: waiting })}
      accessibilityHint={t('mobile.sync.retryHint')}
      onPress={() => void syncEngine.flush(true)}
      style={{
        alignSelf: 'flex-start',
        minHeight: minTouchTarget,
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[2],
        paddingHorizontal: spacing[4],
        borderRadius: radii.full,
        backgroundColor: colors.muted,
        borderWidth: 1,
        borderColor: colors.warning,
      }}>
      <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: colors.warning }} />
      <Text variant="label">{t('mobile.sync.waiting', { count: waiting })}</Text>
    </Pressable>
  );
}
