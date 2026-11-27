import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { STATUS_LABEL } from '@/lib/portfolio/labels';
import type { PortfolioStatus } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/** The status of an item as a small pill: the word carries the meaning, the colour only helps. */
export function StatusBadge({ status }: { status: PortfolioStatus }) {
  const t = useTranslations();
  const { colors, radii } = useAppTheme();
  const tone =
    status === 'APPROVED'
      ? colors.success
      : status === 'CHANGES_REQUESTED' || status === 'REJECTED'
        ? colors.destructive
        : status === 'SUBMITTED'
          ? colors.warning
          : colors.mutedForeground;
  return (
    <View style={{ alignSelf: 'flex-start', borderRadius: radii.full, borderWidth: 1, borderColor: tone, paddingHorizontal: 10, paddingVertical: 2 }}>
      <Text variant="caption" style={{ color: tone }}>
        {t(STATUS_LABEL[status])}
      </Text>
    </View>
  );
}
