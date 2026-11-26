import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { namedBlocked, type GateProblem } from '@/lib/feed/gate';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text } from '../ui';

/**
 * The consent gate, explained. Nothing the author typed or chose is lost when the API says no:
 * - consent missing: the students the school has no media consent for, and the two ways out (remove their tag, or ask the
 *   school to record consent and try again);
 * - photos with nobody tagged: the explicit statement that no student without consent appears in them.
 */
export function GatePanel({
  gate,
  known,
  onRemoveTags,
}: {
  gate: Extract<GateProblem, { kind: 'consent_missing' }> | null;
  known: ReadonlyMap<string, string>;
  onRemoveTags: () => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  if (!gate) return null;
  const { named, unnamed } = namedBlocked(gate.students, known);
  const who = [...named, ...(unnamed > 0 ? [t('mobile.feed.gate.unnamed', { count: unnamed })] : [])].join(', ');
  return (
    <View
      accessibilityRole="alert"
      accessibilityLiveRegion="assertive"
      style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.warning, backgroundColor: colors.muted }}>
      <View style={{ flexDirection: 'row', gap: spacing[2], alignItems: 'center' }}>
        <SymbolView name={{ ios: 'exclamationmark.shield.fill', android: 'shield', web: 'shield' }} size={22} tintColor={colors.warning} />
        <Text variant="heading" style={{ flex: 1 }}>
          {t('mobile.feed.gate.title')}
        </Text>
      </View>
      <Text>{who ? t('mobile.feed.gate.body', { names: who }) : t('mobile.feed.gate.bodyNoNames')}</Text>
      <Text variant="caption">{t('mobile.feed.gate.ways')}</Text>
      <Button label={t('mobile.feed.gate.removeTags')} variant="secondary" onPress={onRemoveTags} />
    </View>
  );
}

/** "I confirm that no student without media consent appears in these photos": the author's statement, audited by the API. */
export function ConfirmNoUnconsented({
  checked,
  onChange,
  highlight,
  statement,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  /** The API asked for it (422): draw attention. */
  highlight: boolean;
  statement?: string;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  return (
    <View style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, borderWidth: highlight ? 2 : 1, borderColor: highlight ? colors.warning : colors.border, backgroundColor: colors.card }}>
      <Text variant="label">{t('mobile.feed.confirm.title')}</Text>
      <Text variant="caption">{t('mobile.feed.confirm.why')}</Text>
      <Pressable
        accessibilityRole="checkbox"
        accessibilityState={{ checked }}
        accessibilityLabel={statement ?? t('mobile.feed.confirm.statement')}
        onPress={() => onChange(!checked)}
        style={{ flexDirection: 'row', gap: spacing[3], alignItems: 'center', minHeight: 48 }}>
        <SymbolView
          name={checked ? { ios: 'checkmark.square.fill', android: 'check_box', web: 'check_box' } : { ios: 'square', android: 'check_box_outline_blank', web: 'check_box_outline_blank' }}
          size={28}
          tintColor={checked ? colors.primary : colors.mutedForeground}
        />
        <Text style={{ flex: 1 }}>{statement ?? t('mobile.feed.confirm.statement')}</Text>
      </Pressable>
      {highlight && !checked ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {t('mobile.feed.confirm.required')}
        </Text>
      ) : null}
    </View>
  );
}
