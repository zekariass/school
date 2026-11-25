import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { RELATIONSHIP_TYPES, type RelationshipType } from '@school/validation/guardians';

/** "You are the child's…": one of the relationship types, as large tap targets that wrap. */
export function RelationshipPicker({
  value,
  onChange,
  label,
}: {
  value: RelationshipType;
  onChange: (value: RelationshipType) => void;
  label: string;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="label">{label}</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={label} style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {RELATIONSHIP_TYPES.map((type) => {
          const selected = type === value;
          const text = t(`family.relationship.${type}`);
          return (
            <Pressable
              key={type}
              accessibilityRole="radio"
              accessibilityState={{ selected }}
              accessibilityLabel={text}
              onPress={() => onChange(type)}
              style={{
                minHeight: minTouchTarget,
                paddingHorizontal: spacing[4],
                borderRadius: radii.full,
                justifyContent: 'center',
                backgroundColor: selected ? colors.primary : colors.muted,
              }}>
              <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
                {text}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
