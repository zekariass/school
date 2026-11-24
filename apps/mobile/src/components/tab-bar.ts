import { useAppTheme } from '@/lib/theme/theme-provider';

/** Shared NativeTabs styling props from the theme. */
export function useTabBarProps() {
  const { colors, text } = useAppTheme();
  return {
    tintColor: colors.primary,
    backgroundColor: colors.card,
    indicatorColor: colors.accent,
    labelStyle: {
      default: { color: colors.mutedForeground, fontFamily: text('label').fontFamily },
      selected: { color: colors.primary, fontFamily: text('label').fontFamily },
    },
  } as const;
}
