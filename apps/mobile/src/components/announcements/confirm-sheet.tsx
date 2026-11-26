import { SymbolView } from 'expo-symbols';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { CONFIRM_PART } from '@/lib/announcements/labels';
import type { Confirmation } from '@/lib/announcements/composer';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ProblemBanner } from '../problem-banner';
import { Button, Sheet, Text } from '../ui';

/**
 * "Send to N people?" before anything leaves: how many people, who, when (a school-time text for a schedule), that an
 * acknowledgement is asked for, and for urgent and safety types that they ignore quiet hours and muted notification settings.
 */
export function ConfirmSheet({
  visible,
  confirmation,
  when,
  busy,
  problem,
  onConfirm,
  onClose,
}: {
  visible: boolean;
  confirmation: Confirmation | null;
  /** The school-time text of a schedule ("5 Dec, 07:30 (Africa/Addis_Ababa)"). */
  when: string | null;
  busy: boolean;
  problem: unknown;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  if (!confirmation) return null;
  const parts = confirmation.parts.map((part) => t(CONFIRM_PART[part.role], { count: part.count })).join(', ');
  return (
    <Sheet visible={visible} onClose={onClose} title={confirmation.scheduled ? t('mobile.announcements.confirm.scheduleTitle') : t('mobile.announcements.confirm.publishTitle')}>
      <Text variant="heading">{t('mobile.announcements.confirm.reach', { count: confirmation.total })}</Text>
      {parts ? <Text tone="muted">{parts}</Text> : null}
      {confirmation.scheduled && when ? <Text>{t('mobile.announcements.confirm.when', { when })}</Text> : null}
      {confirmation.requiresAcknowledgement ? <Text>{t('mobile.announcements.confirm.ack')}</Text> : null}
      {confirmation.urgent ? (
        <View
          accessibilityRole="alert"
          style={{ flexDirection: 'row', gap: spacing[3], padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.destructive }}>
          <SymbolView name={{ ios: 'exclamationmark.triangle.fill', android: 'warning', web: 'warning' }} size={24} tintColor={colors.destructive} />
          <Text style={{ flex: 1 }}>{t('mobile.announcements.confirm.urgent')}</Text>
        </View>
      ) : null}
      <Text variant="caption">{t('mobile.announcements.confirm.cannotUndo')}</Text>
      {problem ? <ProblemBanner error={problem} fallbackTitle={t('mobile.announcements.compose.failedTitle')} fallbackBody={t('mobile.announcements.compose.failedBody')} /> : null}
      <Button label={confirmation.scheduled ? t('mobile.announcements.confirm.schedule') : t('mobile.announcements.confirm.send')} loading={busy} onPress={onConfirm} />
      <Button variant="ghost" label={t('mobile.announcements.confirm.keepEditing')} disabled={busy} onPress={onClose} />
    </Sheet>
  );
}
