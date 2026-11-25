import { SymbolView } from 'expo-symbols';
import { memo, useEffect, useRef, useState } from 'react';
import { Animated, Easing, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { Text } from '@/components/ui';
import type { Totals } from '@school/classroom-toolkit';
import { formatSigned } from '@/lib/points/categories';
import { chunk, gridLayout, type RosterEntry } from '@/lib/people/roster';
import { useAppTheme } from '@/lib/theme/theme-provider';
import type { Burst } from '@/lib/points/use-class-awards';

export interface PointsGridProps {
  students: readonly RosterEntry[];
  /** Net points per student as shown (null: unknown yet, no bubble). */
  totals: Totals;
  /** Whether the overview arrived: without it a student with no pending award shows no bubble. */
  totalsKnown: boolean;
  selecting: boolean;
  selectedIds: ReadonlySet<string>;
  bursts: Readonly<Record<string, Burst | undefined>>;
  animate: boolean;
  onPress: (student: RosterEntry) => void;
  onLongPress: (student: RosterEntry) => void;
}

const GAP = 12;

/** The signature wall: big avatars with a point bubble each, 3 to 4 per row. */
export function PointsGrid({ students, totals, totalsKnown, selecting, selectedIds, bursts, animate, onPress, onLongPress }: PointsGridProps) {
  const { spacing } = useAppTheme();
  const [width, setWidth] = useState(0);
  const layout = gridLayout(width, { gap: GAP });
  const rows = width > 0 ? chunk(students, layout.columns) : [];

  return (
    <View onLayout={(event) => setWidth(event.nativeEvent.layout.width)} style={{ gap: spacing[4] }}>
      {rows.map((row) => (
        <View key={row[0]!.id} style={{ flexDirection: 'row', gap: GAP }}>
          {row.map((student) => (
            <StudentCell
              key={student.id}
              student={student}
              cellWidth={layout.cellWidth}
              avatarSize={layout.avatarSize}
              total={totals[student.id]}
              totalsKnown={totalsKnown}
              selecting={selecting}
              selected={selectedIds.has(student.id)}
              burst={bursts[student.id]}
              animate={animate}
              onPress={onPress}
              onLongPress={onLongPress}
            />
          ))}
        </View>
      ))}
    </View>
  );
}

interface CellProps {
  student: RosterEntry;
  cellWidth: number;
  avatarSize: number;
  total: number | undefined;
  totalsKnown: boolean;
  selecting: boolean;
  selected: boolean;
  burst: Burst | undefined;
  animate: boolean;
  onPress: (student: RosterEntry) => void;
  onLongPress: (student: RosterEntry) => void;
}

const StudentCell = memo(function StudentCell({
  student,
  cellWidth,
  avatarSize,
  total,
  totalsKnown,
  selecting,
  selected,
  burst,
  animate,
  onPress,
  onLongPress,
}: CellProps) {
  const t = useTranslations();
  const { colors, spacing, minTouchTarget } = useAppTheme();
  const [pop] = useState(() => new Animated.Value(1));
  const [rise] = useState(() => new Animated.Value(0));
  const lastKey = useRef<number | null>(null);

  useEffect(() => {
    if (!burst || burst.key === lastKey.current) return;
    lastKey.current = burst.key;
    if (!animate) return;
    pop.setValue(0.88);
    rise.setValue(0);
    Animated.parallel([
      Animated.spring(pop, { toValue: 1, friction: 4, tension: 160, useNativeDriver: true }),
      Animated.timing(rise, { toValue: 1, duration: 900, easing: Easing.out(Easing.cubic), useNativeDriver: true }),
    ]).start();
  }, [burst, animate, pop, rise]);

  const showBubble = total !== undefined && (totalsKnown || total !== 0);
  const value = total ?? 0;
  const bubbleColor = value < 0 ? colors.pointsNeedsWork : colors.pointsPositive;
  const label = [
    student.name,
    showBubble ? t('mobile.points.grid.totalLabel', { count: value }) : null,
    selecting ? (selected ? t('mobile.points.grid.selected') : t('mobile.points.grid.notSelected')) : null,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <Pressable
      accessibilityRole={selecting ? 'checkbox' : 'button'}
      accessibilityLabel={label}
      accessibilityHint={selecting ? undefined : t('mobile.points.grid.openHint')}
      accessibilityState={selecting ? { checked: selected } : undefined}
      onPress={() => onPress(student)}
      onLongPress={() => onLongPress(student)}
      delayLongPress={350}
      style={({ pressed }) => ({
        width: cellWidth,
        minHeight: minTouchTarget * 2,
        alignItems: 'center',
        gap: spacing[2],
        opacity: pressed ? 0.75 : 1,
      })}>
      <Animated.View style={{ transform: [{ scale: pop }] }}>
        <View
          style={{
            borderRadius: avatarSize / 2 + 4,
            borderWidth: 4,
            borderColor: selected ? colors.primary : 'transparent',
            padding: 0,
          }}>
          <Avatar
            mediaId={student.avatarMediaId}
            name={student.name}
            seed={student.id}
            config={student.avatarConfig}
            size={avatarSize - 8}
            maxInitials={1}
            accessibilityLabel={student.name}
          />
        </View>
        {showBubble ? (
          <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{
              position: 'absolute',
              top: -4,
              right: -6,
              minWidth: 30,
              height: 30,
              paddingHorizontal: 6,
              borderRadius: 15,
              backgroundColor: bubbleColor,
              borderWidth: 2,
              borderColor: colors.background,
              alignItems: 'center',
              justifyContent: 'center',
            }}>
            <Text variant="label" allowFontScaling={false} style={{ color: colors.primaryForeground, fontSize: 14, lineHeight: 18 }}>
              {value < 0 ? formatSigned(value) : String(value)}
            </Text>
          </View>
        ) : null}
        {selecting ? (
          <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{
              position: 'absolute',
              bottom: -2,
              left: -2,
              width: 26,
              height: 26,
              borderRadius: 13,
              backgroundColor: selected ? colors.primary : colors.card,
              borderWidth: 2,
              borderColor: selected ? colors.primary : colors.border,
              alignItems: 'center',
              justifyContent: 'center',
            }}>
            {selected ? (
              <SymbolView name={{ ios: 'checkmark', android: 'check', web: 'check' }} size={14} tintColor={colors.primaryForeground} />
            ) : null}
          </View>
        ) : null}
        {burst && animate ? (
          <Animated.View
            pointerEvents="none"
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              alignItems: 'center',
              opacity: rise.interpolate({ inputRange: [0, 0.15, 0.8, 1], outputRange: [0, 1, 1, 0] }),
              transform: [{ translateY: rise.interpolate({ inputRange: [0, 1], outputRange: [avatarSize * 0.3, -avatarSize * 0.55] }) }],
            }}>
            <Text
              variant="title"
              allowFontScaling={false}
              style={{ color: burst.negative ? colors.pointsNeedsWork : colors.pointsPositive, fontSize: 28, lineHeight: 34 }}>
              {burst.text}
            </Text>
          </Animated.View>
        ) : null}
      </Animated.View>
      <Text variant="label" numberOfLines={2} style={{ textAlign: 'center' }}>
        {student.firstName}
      </Text>
    </Pressable>
  );
});
