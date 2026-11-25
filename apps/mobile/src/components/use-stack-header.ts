import { useAppTheme } from '@/lib/theme/theme-provider';

/** Header options shared by the stacks nested in the tab groups (same look as the root stack). */
export function useStackHeader() {
  const { colors, text } = useAppTheme();
  return {
    headerShown: true,
    headerTintColor: colors.primary,
    headerStyle: { backgroundColor: colors.background },
    headerTitleStyle: { color: colors.foreground, fontFamily: text('heading').fontFamily },
    headerBackButtonDisplayMode: 'minimal',
    headerShadowVisible: false,
    contentStyle: { backgroundColor: colors.background },
  } as const;
}
