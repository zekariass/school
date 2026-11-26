import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * A notice above the composer: who can read the conversation, why it is read-only, office hours. Spoken by screen readers
 * as it appears (polite live region), dismissible when it is only information about one message.
 */
export function Banner({
  tone = 'info',
  icon,
  title,
  body,
  onDismiss,
}: {
  tone?: 'info' | 'warning' | 'safety';
  icon: { ios: string; android: string };
  title?: string;
  body: string;
  onDismiss?: () => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const accent = tone === 'warning' ? colors.warning : tone === 'safety' ? colors.primary : colors.info;
  return (
    <View
      accessible
      accessibilityRole="summary"
      accessibilityLiveRegion="polite"
      accessibilityLabel={title ? `${title}. ${body}` : body}
      style={{ flexDirection: 'row', gap: spacing[3], padding: spacing[3], marginHorizontal: spacing[4], marginTop: spacing[2], borderRadius: radii.xl, backgroundColor: colors.card, borderWidth: 1, borderColor: accent }}>
      <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as never} size={20} tintColor={accent} />
      <View style={{ flex: 1, gap: 2 }}>
        {title ? <Text variant="label">{title}</Text> : null}
        <Text variant="caption" style={{ color: colors.foreground }}>
          {body}
        </Text>
      </View>
      {onDismiss ? (
        <Pressable accessibilityRole="button" accessibilityLabel={t('common.done')} onPress={onDismiss} hitSlop={10}>
          <SymbolView name={{ ios: 'xmark', android: 'close', web: 'close' }} size={14} tintColor={colors.mutedForeground} />
        </Pressable>
      ) : null}
    </View>
  );
}
