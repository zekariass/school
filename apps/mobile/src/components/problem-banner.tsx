import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text } from './ui';

export interface ProblemBannerProps {
  error: unknown;
  /** Shown when the failure has no server-provided problem (offline, timeout, 5xx without a body). */
  fallbackTitle: string;
  fallbackBody: string;
  onRetry?: () => void;
}

/**
 * Renders a failed API call honestly: the server's localized problem title/detail and
 * stable code when it sent one, otherwise the given fallback copy.
 */
export function ProblemBanner({ error, fallbackTitle, fallbackBody, onRetry }: ProblemBannerProps) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const problem = problemOf(error);

  return (
    <View
      accessibilityRole="alert"
      accessibilityLiveRegion="polite"
      style={{
        gap: spacing[2],
        padding: spacing[4],
        borderRadius: radii.xl,
        borderWidth: 1,
        borderColor: colors.destructive,
        backgroundColor: colors.muted,
      }}>
      <Text variant="heading" tone="destructive">
        {problem?.title ?? fallbackTitle}
      </Text>
      <Text>{problem?.detail ?? fallbackBody}</Text>
      {problem?.code ? (
        <Text variant="caption" selectable>
          {t('status.errorCode', { code: problem.code })}
        </Text>
      ) : null}
      {onRetry ? <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={onRetry} /> : null}
    </View>
  );
}
