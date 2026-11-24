export type ThemePreference = 'system' | 'light' | 'dark';

export const THEME_PREFERENCES: readonly ThemePreference[] = ['system', 'light', 'dark'];

/** kv-store key of the saved theme preference. */
export const THEME_STORAGE_KEY = 'school.theme';

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark';
}

/** Resolves the preference against the device's color scheme. */
export function resolveScheme(
  preference: ThemePreference,
  systemScheme: 'light' | 'dark' | null | undefined,
): 'light' | 'dark' {
  if (preference === 'system') return systemScheme === 'dark' ? 'dark' : 'light';
  return preference;
}
