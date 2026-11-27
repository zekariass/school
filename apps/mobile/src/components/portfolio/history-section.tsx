import { useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { AUTHOR_ROLE_LABEL } from '@/lib/portfolio/labels';
import { historyLabelKey, needsConnection } from '@/lib/portfolio/model';
import { useHistory } from '@/lib/portfolio/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text } from '../ui';

/**
 * The status history (what happened to the item, and the reviewers' notes), for the student and the reviewers only. It is
 * fetched when asked for: the API answers 403 to everybody else who can see the item, and that is explained, not hidden.
 */
export function HistorySection({ schoolId, itemId }: { schoolId: string; itemId: string }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const format = useFormatInstant();
  const [open, setOpen] = useState(false);
  const history = useHistory(schoolId, itemId, open);

  if (!open) return <Button variant="ghost" label={t('mobile.portfolio.history.show')} onPress={() => setOpen(true)} />;
  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.portfolio.history.title')}</Text>
      {history.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : history.isError ? (
        <Text tone="destructive" accessibilityLiveRegion="polite">
          {needsConnection(history.error) ? t('mobile.portfolio.offline.short') : (problemOf(history.error)?.detail ?? t('mobile.portfolio.history.failed'))}
        </Text>
      ) : (
        history.data.map((entry) => {
          const who = entry.actorName?.trim() || t(AUTHOR_ROLE_LABEL[entry.actorRole]);
          return (
            <View key={entry.id} accessible style={{ gap: 2, padding: spacing[3], borderRadius: radii.xl, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.card }}>
              <Text variant="label">{t(historyLabelKey(entry))}</Text>
              <Text variant="caption">{`${who} · ${format(entry.createdAt)}`}</Text>
              {entry.note ? <Text>{entry.note}</Text> : null}
            </View>
          );
        })
      )}
    </View>
  );
}
