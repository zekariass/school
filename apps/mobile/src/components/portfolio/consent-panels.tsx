import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { namedBlocked, type GateProblem } from '@/lib/portfolio/gate';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text } from '../ui';

type Consent = Extract<GateProblem, { kind: 'consent_missing' }>;

/**
 * The consent gate, explained, and nothing the author typed or chose is lost when the API says no.
 * - `own`: the writer (a student, or a teacher capturing) can change the item: remove tags, or ask the school to record consent.
 * - `review`: a reviewer cannot change the pictures: the way out is to ask for changes or to lower the audience.
 */
export function ConsentPanel({
  gate,
  known,
  mode,
  onRemoveTags,
}: {
  gate: Consent | null;
  known: ReadonlyMap<string, string>;
  mode: 'own' | 'review';
  onRemoveTags?: () => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  if (!gate) return null;
  const { named, unnamed } = namedBlocked(gate.students, known);
  const who = [...named, ...(unnamed > 0 ? [t('mobile.portfolio.gate.unnamed', { count: unnamed })] : [])].join(', ');
  return (
    <View
      accessibilityRole="alert"
      accessibilityLiveRegion="assertive"
      style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.warning, backgroundColor: colors.muted }}>
      <View style={{ flexDirection: 'row', gap: spacing[2], alignItems: 'center' }}>
        <SymbolView name={{ ios: 'exclamationmark.shield.fill', android: 'shield', web: 'shield' }} size={22} tintColor={colors.warning} />
        <Text variant="heading" style={{ flex: 1 }}>
          {t('mobile.portfolio.gate.title')}
        </Text>
      </View>
      <Text>{who ? t('mobile.portfolio.gate.body', { names: who }) : t('mobile.portfolio.gate.bodyNoNames')}</Text>
      <Text variant="caption">{mode === 'review' ? t('mobile.portfolio.gate.waysReview') : t('mobile.portfolio.gate.waysOwn')}</Text>
      {mode === 'own' && onRemoveTags && gate.students.length > 0 ? <Button label={t('mobile.portfolio.gate.removeTags')} variant="secondary" onPress={onRemoveTags} /> : null}
    </View>
  );
}

/** "I confirm that no child without permission appears in these pictures": the writer's statement, audited by the API. */
export function ConfirmPanel({ checked, onChange, highlight }: { checked: boolean; onChange: (value: boolean) => void; highlight: boolean }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  return (
    <View style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, borderWidth: highlight ? 2 : 1, borderColor: highlight ? colors.warning : colors.border, backgroundColor: colors.card }}>
      <Text variant="label">{t('mobile.portfolio.confirm.title')}</Text>
      <Text variant="caption">{t('mobile.portfolio.confirm.why')}</Text>
      <Pressable
        accessibilityRole="checkbox"
        accessibilityState={{ checked }}
        accessibilityLabel={t('mobile.portfolio.confirm.statement')}
        onPress={() => onChange(!checked)}
        style={{ flexDirection: 'row', gap: spacing[3], alignItems: 'center', minHeight: 48 }}>
        <SymbolView
          name={checked ? { ios: 'checkmark.square.fill', android: 'check_box', web: 'check_box' } : { ios: 'square', android: 'check_box_outline_blank', web: 'check_box_outline_blank' }}
          size={28}
          tintColor={checked ? colors.primary : colors.mutedForeground}
        />
        <Text style={{ flex: 1 }}>{t('mobile.portfolio.confirm.statement')}</Text>
      </Pressable>
      {highlight && !checked ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {t('mobile.portfolio.confirm.required')}
        </Text>
      ) : null}
    </View>
  );
}
