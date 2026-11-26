import { memo } from 'react';
import { Pressable, View } from 'react-native';

import type { DayEntry } from '@/lib/calendar/bucket';
import type { IsoDate } from '@/lib/calendar/civil';
import type { DayCell } from '@/lib/calendar/grid';
import { itemStyle, isCancelled } from '@/lib/calendar/item-style';
import { daySummaryLabel, type LabelContext } from '@/lib/calendar/labels';
import { weekdayName, weekdayShort } from '@/lib/calendar/names';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * The month as a grid of days (weeks as rows). Each day shows its number in the chosen calendar, a ring on today, a fill on the
 * selected day and up to three small marks for what is on it. The marks are only a hint: a screen reader gets the date and the
 * titles in the day's label, and the day's list below the grid names every item with its icon and kind.
 */
export function MonthGrid({
  rows,
  weekdays,
  entries,
  selected,
  today,
  context,
  onSelect,
}: {
  rows: readonly (readonly DayCell[])[];
  weekdays: readonly number[];
  entries: ReadonlyMap<IsoDate, readonly DayEntry[]>;
  selected: IsoDate;
  today: IsoDate;
  context: LabelContext;
  onSelect: (date: IsoDate) => void;
}) {
  const { spacing } = useAppTheme();
  return (
    <View accessibilityRole="grid" style={{ gap: spacing[1] }}>
      <View style={{ flexDirection: 'row' }} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {weekdays.map((weekday) => (
          <View key={weekday} style={{ flex: 1, alignItems: 'center', paddingVertical: spacing[1] }}>
            <Text variant="caption" accessibilityLabel={weekdayName(weekday, context.locale)}>
              {weekdayShort(weekday, context.locale)}
            </Text>
          </View>
        ))}
      </View>
      {rows.map((row) => (
        <View key={row[0]!.date} style={{ flexDirection: 'row', gap: 2 }}>
          {row.map((cell) => (
            <DayButton key={cell.date} cell={cell} entries={entries.get(cell.date) ?? EMPTY} selected={cell.date === selected} isToday={cell.date === today} context={context} today={today} onSelect={onSelect} />
          ))}
        </View>
      ))}
    </View>
  );
}

const EMPTY: readonly DayEntry[] = [];

const DayButton = memo(function DayButton({
  cell,
  entries,
  selected,
  isToday,
  context,
  today,
  onSelect,
}: {
  cell: DayCell;
  entries: readonly DayEntry[];
  selected: boolean;
  isToday: boolean;
  context: LabelContext;
  today: IsoDate;
  onSelect: (date: IsoDate) => void;
}) {
  const { colors, radii, minTouchTarget } = useAppTheme();
  const marks = entries.filter((entry) => !isCancelled(entry.item)).slice(0, 3);
  const dim = !cell.inMonth;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={daySummaryLabel(cell.date, entries, context, today)}
      accessibilityState={{ selected }}
      onPress={() => onSelect(cell.date)}
      style={{
        flex: 1,
        minHeight: minTouchTarget + 12,
        alignItems: 'center',
        justifyContent: 'flex-start',
        paddingTop: 4,
        gap: 3,
        borderRadius: radii.lg,
        backgroundColor: selected ? colors.primary : 'transparent',
        borderWidth: isToday ? 2 : 0,
        borderColor: colors.primary,
        opacity: dim && !selected ? 0.5 : 1,
      }}>
      <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
        {cell.day}
      </Text>
      <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ flexDirection: 'row', gap: 3, height: 7 }}>
        {marks.map((entry) => (
          <View key={entry.key} style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: selected ? colors.primaryForeground : colors[itemStyle(entry.item).tone] }} />
        ))}
      </View>
    </Pressable>
  );
});
