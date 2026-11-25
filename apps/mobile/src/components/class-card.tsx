import { SymbolView } from 'expo-symbols';
import type { ReactNode } from 'react';
import { Pressable, View } from 'react-native';

import { classColor, classGlyph, type MyClassLike } from '@/lib/people/classes';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Card, Text } from './ui';

export interface ClassBadgeProps {
  cls: Pick<MyClassLike, 'id' | 'name' | 'color' | 'icon'>;
  size?: number;
}

/** The class's coloured badge with its icon (or first letter). Decorative: the name is read next to it. */
export function ClassBadge({ cls, size = 64 }: ClassBadgeProps) {
  return (
    <View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        backgroundColor: classColor(cls),
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <Text variant="title" allowFontScaling={false} style={{ color: '#FFFFFF', fontSize: size * 0.46, lineHeight: size * 0.7 }}>
        {classGlyph(cls)}
      </Text>
    </View>
  );
}

export interface ClassCardProps {
  cls: Pick<MyClassLike, 'id' | 'name' | 'color' | 'icon'>;
  /** Lines under the name (grade, student count, teachers). */
  lines: readonly (string | null | undefined)[];
  onPress?: () => void;
  accessibilityLabel: string;
  trailing?: ReactNode;
}

/** A big, friendly class card for the student home and the teacher's class list. */
export function ClassCard({ cls, lines, onPress, accessibilityLabel, trailing }: ClassCardProps) {
  const { colors, spacing } = useAppTheme();
  const content = (
    <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[4], padding: spacing[4] }}>
      <ClassBadge cls={cls} />
      <View style={{ flex: 1, gap: 2 }}>
        <Text variant="heading" numberOfLines={2}>
          {cls.name}
        </Text>
        {lines.filter(Boolean).map((line) => (
          <Text key={line} tone="muted" variant="caption" numberOfLines={2}>
            {line}
          </Text>
        ))}
      </View>
      {trailing ??
        (onPress ? (
          <SymbolView
            name={{ ios: 'chevron.right', android: 'chevron_right', web: 'chevron_right' }}
            size={18}
            tintColor={colors.mutedForeground}
          />
        ) : null)}
    </Card>
  );
  if (!onPress) {
    return (
      <View accessible accessibilityLabel={accessibilityLabel}>
        {content}
      </View>
    );
  }
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel} onPress={onPress} style={({ pressed }) => ({ opacity: pressed ? 0.85 : 1 })}>
      {content}
    </Pressable>
  );
}
