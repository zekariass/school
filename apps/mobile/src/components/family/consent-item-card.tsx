import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Text } from '@/components/ui';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import type { ConsentItem } from '@/lib/family/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { availableActions, decisionState, mediaOutcome, type ConsentAction } from '@school/validation/family';

export interface ConsentTarget {
  item: ConsentItem;
  child?: { studentId: string; schoolId: string; name: string };
}

/**
 * One consent request: what it is, my decision so far, the full text on demand, and the actions that
 * make sense now. For photos it says plainly what the decision means for the child.
 */
export function ConsentItemCard({
  item,
  child,
  busy,
  onDecide,
}: {
  item: ConsentItem;
  child?: { studentId: string; schoolId: string; name: string };
  busy: boolean;
  onDecide: (action: ConsentAction) => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const formatInstant = useFormatInstant();
  const [reading, setReading] = useState(false);
  const state = decisionState(item);
  const actions = availableActions(item);
  const name = child?.name ?? '';

  return (
    <View
      accessibilityLabel={t(`consent.type.${item.consentType}`)}
      style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, borderWidth: 1, borderColor: colors.border }}>
      <Text variant="label">{t(`consent.type.${item.consentType}`)}</Text>
      <Text tone="muted">{t(`consent.typeHelp.${item.consentType}`, { child: name })}</Text>
      <Text variant="label" tone={state === 'GRANTED' ? 'primary' : state === 'NONE' ? 'muted' : 'destructive'}>
        {t('consent.yourDecision', { decision: t(`consent.state.${state}`) })}
      </Text>
      {item.myDecision ? <Text variant="caption">{t('consent.decidedOn', { date: formatInstant(item.myDecision.decidedAt) })}</Text> : null}
      {item.outdated ? <Text tone="destructive">{t('consent.outdated')}</Text> : null}

      {item.consentType === 'MEDIA_PUBLICATION' && child ? (
        <View style={{ gap: spacing[1] }}>
          <Text variant="label">
            {mediaOutcome(item.effectiveStatus?.status) === 'allowed'
              ? t('consent.mediaAllowed', { child: name })
              : t('consent.mediaBlocked', { child: name })}
          </Text>
          <Text tone="muted">{t('consent.strictest', { child: name })}</Text>
        </View>
      ) : null}

      {item.currentVersion ? (
        <View style={{ gap: spacing[2] }}>
          <Button
            variant="ghost"
            fullWidth={false}
            label={reading ? t('consent.hideText') : t('consent.readText')}
            accessibilityState={{ expanded: reading }}
            onPress={() => setReading(!reading)}
          />
          {reading ? (
            <View style={{ gap: spacing[1] }}>
              <Text variant="caption">
                {t('consent.version', { version: item.currentVersion.versionLabel })} · {item.currentVersion.languageCode}
              </Text>
              <Text selectable>{item.currentVersion.content}</Text>
            </View>
          ) : null}
        </View>
      ) : (
        <Text tone="muted">{t('consent.noPolicy')}</Text>
      )}

      {actions.map((action) => (
        <Button
          key={action}
          variant={action === 'GRANTED' ? 'primary' : 'ghost'}
          label={action === 'GRANTED' ? t('consent.grant') : action === 'DENIED' ? t('consent.deny') : t('consent.withdraw')}
          disabled={busy}
          onPress={() => onDecide(action)}
        />
      ))}
    </View>
  );
}
