import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { problemKind } from '@/lib/calendar/problems';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * A failed action, in the API's own localized words (`problem.detail`: the 409, 422 and 429 of the calendar all carry one), kept
 * next to what failed. Offline and bodiless failures get the catalog's wording. The form or screen stays as it was.
 */
export function InlineError({ error }: { error: unknown }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  if (!error) return null;
  const problem = problemOf(error);
  const kind = problemKind(error);
  const text = problem?.detail ?? (kind === 'offline' ? t('mobile.calendar.offlineAction') : t('mobile.calendar.actionFailed'));
  return (
    <View
      accessibilityRole="alert"
      accessibilityLiveRegion="polite"
      style={{ padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.destructive, backgroundColor: colors.muted, gap: spacing[1] }}>
      <Text tone="destructive">{text}</Text>
      {problem?.code ? (
        <Text variant="caption" selectable>
          {t('status.errorCode', { code: problem.code })}
        </Text>
      ) : null}
    </View>
  );
}
