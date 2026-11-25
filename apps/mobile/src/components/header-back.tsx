import { router } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { Pressable } from 'react-native';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Back button for the first screen of a stack that sits above the tabs: the stack itself has nothing
 * to go back to, so it leaves through the parent navigator (or, when the app was opened straight onto
 * this screen by a notification, through the home route).
 */
export function HeaderBack() {
  const t = useTranslations();
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('common.back')}
      hitSlop={8}
      onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))}
      style={{ width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}>
      <SymbolView name={{ ios: 'chevron.left', android: 'arrow_back', web: 'arrow_back' }} size={22} tintColor={colors.primary} />
    </Pressable>
  );
}
