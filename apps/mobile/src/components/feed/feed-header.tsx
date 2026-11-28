import { useIsFocused, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { useMe } from '@/lib/auth/use-me';
import { isSwitchable } from '@/lib/feed/header';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from '../avatar';
import { NotificationBell } from '../notification-bell';
import { Sheet, Text } from '../ui';

export interface HeaderSwitcher {
  /** Names the choice for screen readers and the sheet ("Show posts about", "Which feed"). */
  label: string;
  options: readonly { value: string; label: string }[];
  value: string;
  onChange: (value: string) => void;
}

export interface FeedHeaderProps {
  /** The small line above the title (the school, and the class for a child). */
  eyebrow?: string | null | undefined;
  title: string;
  /** Names the round logo (the school's initials; the school's logo is not part of the API yet). */
  schoolName?: string | null | undefined;
  /** The title becomes a dropdown (class or child switcher) when there is more than one option. */
  switcher?: HeaderSwitcher | undefined;
  /** Pad for the status bar (tab screens, which have no native header). */
  insetTop?: boolean;
  /** The user's picture and the bell: not in the developer preview, which has no account. */
  withAccount?: boolean;
}

/**
 * The band at the top of a feed: the school's round logo, the school above the class (or child), a dropdown to switch, the
 * notification bell and the user's picture, on the primary colour with soft round shapes and a curved bottom edge. Every
 * colour comes from the theme, so it follows light and dark and a change of the brand colour.
 */
export function FeedHeader({ eyebrow, title, schoolName, switcher, insetTop = false, withAccount = true }: FeedHeaderProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget, scheme } = useAppTheme();
  const insets = useSafeAreaInsets();
  const focused = useIsFocused();
  const me = useMe();
  const [open, setOpen] = useState(false);
  const switchable = !!switcher && isSwitchable(switcher.options);
  const onBand = colors.primaryForeground;

  const titleText = (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[1] }}>
      <Text variant="title" numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7} style={{ color: onBand, flexShrink: 1 }}>
        {title}
      </Text>
      {switchable ? <SymbolView name={{ ios: 'chevron.down', android: 'expand_more', web: 'expand_more' }} size={18} tintColor={onBand} /> : null}
    </View>
  );

  return (
    <View>
      {/* Pulling the list down shows the band's colour, not a gap. */}
      <View pointerEvents="none" style={{ position: 'absolute', top: -1000, left: 0, right: 0, height: 1000, backgroundColor: colors.primary }} />
      {insetTop && focused ? <StatusBar style={scheme === 'dark' ? 'dark' : 'light'} /> : null}
      <View
        style={{
          backgroundColor: colors.primary,
          overflow: 'hidden',
          paddingTop: (insetTop ? insets.top : 0) + spacing[3],
          paddingBottom: spacing[10],
          paddingHorizontal: spacing[5],
          borderBottomLeftRadius: radii['2xl'],
          borderBottomRightRadius: radii['2xl'],
        }}>
        <Shapes color={onBand} />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
          <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ borderRadius: radii.lg, overflow: 'hidden' }}>
            <Avatar name={schoolName ?? title} seed={schoolName ?? title} size={44} maxInitials={2} />
          </View>
          <View style={{ flex: 1 }}>
            {eyebrow ? (
              <Text variant="caption" numberOfLines={1} style={{ color: onBand }}>
                {eyebrow}
              </Text>
            ) : null}
            {switchable ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`${switcher.label}: ${title}`}
                onPress={() => setOpen(true)}
                hitSlop={6}
                style={{ minHeight: minTouchTarget, justifyContent: 'center' }}>
                {titleText}
              </Pressable>
            ) : (
              <View accessibilityRole="header" style={{ minHeight: minTouchTarget, justifyContent: 'center' }}>
                {titleText}
              </View>
            )}
          </View>
          {withAccount ? (
            <>
              <NotificationBell onBand />
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('mobile.feed.header.profile')}
                onPress={() => router.push('/settings')}
                hitSlop={4}
                style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
                <View style={{ borderRadius: radii.full, borderWidth: 2, borderColor: onBand }}>
                  <Avatar mediaId={me.data?.avatar?.id} name={me.data?.displayName ?? ''} seed={me.data?.id} size={36} maxInitials={1} memoryOnly />
                </View>
              </Pressable>
            </>
          ) : null}
        </View>
      </View>
      {switchable ? (
        <Sheet visible={open} onClose={() => setOpen(false)} title={switcher.label}>
          <View accessibilityRole="radiogroup" accessibilityLabel={switcher.label} style={{ gap: spacing[1] }}>
            {switcher.options.map((option) => {
              const selected = option.value === switcher.value;
              return (
                <Pressable
                  key={option.value}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  accessibilityLabel={option.label}
                  onPress={() => {
                    setOpen(false);
                    if (!selected) switcher.onChange(option.value);
                  }}
                  style={({ pressed }) => ({
                    minHeight: minTouchTarget,
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: spacing[3],
                    paddingHorizontal: spacing[3],
                    borderRadius: radii.lg,
                    backgroundColor: pressed ? colors.muted : 'transparent',
                  })}>
                  <Text variant="label" style={{ flex: 1 }}>
                    {option.label}
                  </Text>
                  {selected ? <SymbolView name={{ ios: 'checkmark', android: 'check', web: 'check' }} size={18} tintColor={colors.primary} /> : null}
                </Pressable>
              );
            })}
          </View>
        </Sheet>
      ) : null}
    </View>
  );
}

/** Two soft clouds made of circles, in the band's text colour at low strength: decoration only, hidden from screen readers. */
function Shapes({ color }: { color: string }) {
  const circle = (size: number, top: number, end: number) => (
    <View style={{ position: 'absolute', top, end, width: size, height: size, borderRadius: size / 2, backgroundColor: color, opacity: 0.12 }} />
  );
  return (
    <View pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}>
      {circle(120, -40, 60)}
      {circle(80, 10, 20)}
      {circle(60, 60, 130)}
    </View>
  );
}
