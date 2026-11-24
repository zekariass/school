import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react';
import { Appearance, Platform, useColorScheme } from 'react-native';

import { colors, minTouchTarget, radii, shadows, spacing, type SemanticColors } from '@school/design-tokens';

import { usePreferences } from '@/lib/preferences/preferences-provider';
import { resolveScheme, type ThemePreference } from '@/lib/theme/preference';
import { textStyleFor, type TextStyleSpec, type TextVariant } from '@/lib/theme/typography';

export interface AppTheme {
  scheme: 'light' | 'dark';
  colors: SemanticColors;
  radii: typeof radii;
  spacing: typeof spacing;
  minTouchTarget: number;
  /** CSS box-shadow of raised surfaces (RN supports `boxShadow` strings). */
  cardShadow: string;
  text: (variant: TextVariant) => TextStyleSpec;
  preference: ThemePreference;
  setPreference: (preference: ThemePreference) => void;
}

const ThemeContext = createContext<AppTheme | null>(null);

const PLATFORM = Platform.OS === 'ios' ? 'ios' : 'android';

export function AppThemeProvider({ children }: { children: ReactNode }) {
  const { themePreference, setThemePreference, locale } = usePreferences();
  const systemScheme = useColorScheme();

  // Make native chrome (alerts, keyboards, native tab bar) follow the in-app choice.
  useEffect(() => {
    Appearance.setColorScheme(themePreference === 'system' ? 'unspecified' : themePreference);
  }, [themePreference]);

  const scheme = resolveScheme(themePreference, systemScheme);

  const theme = useMemo<AppTheme>(
    () => ({
      scheme,
      colors: colors[scheme],
      radii,
      spacing,
      minTouchTarget,
      cardShadow: shadows.card,
      text: (variant) => textStyleFor(variant, locale, PLATFORM),
      preference: themePreference,
      setPreference: setThemePreference,
    }),
    [scheme, locale, themePreference, setThemePreference],
  );

  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useAppTheme(): AppTheme {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error('useAppTheme must be used inside <AppThemeProvider>');
  return theme;
}
