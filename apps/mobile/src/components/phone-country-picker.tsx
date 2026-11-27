import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface PickableCountry {
  code: string;
  name: string;
  phoneCountryCode: string;
}

/**
 * Which country a national phone number is read in, as the API sent it (name and calling code). Shown only when more
 * than one country is enabled; a number that starts with "+" needs no choice (D42).
 */
export function PhoneCountryPicker({
  countries,
  value,
  onChange,
  disabled = false,
}: {
  countries: readonly PickableCountry[];
  value: string | undefined;
  onChange: (code: string) => void;
  disabled?: boolean;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="label">{t('auth.phoneCountry')}</Text>
      <View
        accessibilityRole="radiogroup"
        accessibilityLabel={t('auth.phoneCountry')}
        style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {countries.map((country) => {
          const selected = country.code === value;
          const label = `${country.name} (${country.phoneCountryCode})`;
          return (
            <Pressable
              key={country.code}
              accessibilityRole="radio"
              accessibilityState={{ selected, disabled }}
              accessibilityLabel={label}
              disabled={disabled}
              onPress={() => onChange(country.code)}
              style={{
                minHeight: minTouchTarget,
                justifyContent: 'center',
                borderRadius: radii.full,
                paddingHorizontal: spacing[4],
                borderWidth: 1,
                borderColor: selected ? colors.primary : colors.border,
                backgroundColor: selected ? colors.primary : colors.muted,
              }}>
              <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
                {label}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
