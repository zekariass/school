import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { Pressable, ScrollView, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { addDays, canGoEarlier, canGoLater } from '@/lib/attendance/dates';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface DateSwitcherProps {
  /** The roll's school-local date. */
  date: string;
  /** The school's today (from the school's zone, not the phone's). */
  today: string;
  onDate: (date: string) => void;
  slots: readonly string[];
  slot: string;
  onSlot: (slot: string) => void;
}

/** "Wednesday 25 November, today": the day arrows stop at what the API allows (never the future). */
export function DateSwitcher({ date, today, onDate, slots, slot, onSlot }: DateSwitcherProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const format = useFormatCivilDate();
  const slotLabel = useSlotLabel();
  const earlier = canGoEarlier(date, today);
  const later = canGoLater(date, today);

  return (
    <View style={{ gap: spacing[2] }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
        <Arrow label={t('mobile.attendance.date.previous')} symbol="chevron.left" material="chevron_left" enabled={earlier} onPress={() => onDate(addDays(date, -1))} />
        <View style={{ flex: 1, alignItems: 'center' }} accessible accessibilityRole="header" accessibilityLabel={date === today ? t('mobile.attendance.date.todayLabel', { date: format(date) }) : format(date)}>
          <Text variant="heading" numberOfLines={2} style={{ textAlign: 'center' }}>
            {format(date)}
          </Text>
          {date === today ? <Text variant="caption">{t('mobile.attendance.date.today')}</Text> : null}
        </View>
        <Arrow label={t('mobile.attendance.date.next')} symbol="chevron.right" material="chevron_right" enabled={later} onPress={() => onDate(addDays(date, 1))} />
      </View>
      {date !== today ? (
        <Pressable
          accessibilityRole="button"
          onPress={() => onDate(today)}
          style={{ alignSelf: 'center', minHeight: minTouchTarget, justifyContent: 'center', paddingHorizontal: spacing[3] }}>
          <Text variant="label" tone="primary">
            {t('mobile.attendance.date.backToToday')}
          </Text>
        </Pressable>
      ) : null}
      {slots.length > 1 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: spacing[2] }} accessibilityRole="radiogroup" accessibilityLabel={t('mobile.attendance.slot.label')}>
          {slots.map((value) => (
            <Pressable
              key={value}
              accessibilityRole="radio"
              accessibilityState={{ selected: value === slot }}
              onPress={() => onSlot(value)}
              style={{
                minHeight: minTouchTarget,
                justifyContent: 'center',
                paddingHorizontal: spacing[4],
                borderRadius: radii.full,
                backgroundColor: value === slot ? colors.primary : colors.muted,
              }}>
              <Text variant="label" style={{ color: value === slot ? colors.primaryForeground : colors.foreground }}>
                {slotLabel(value)}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      ) : null}
    </View>
  );
}

/** DAY, AM, PM and P1..P8 in words. */
export function useSlotLabel(): (slot: string) => string {
  const t = useTranslations();
  return (slot) => {
    if (slot === 'DAY') return t('mobile.attendance.slot.DAY');
    if (slot === 'AM') return t('mobile.attendance.slot.AM');
    if (slot === 'PM') return t('mobile.attendance.slot.PM');
    const period = /^P([1-8])$/.exec(slot);
    return period ? t('mobile.attendance.slot.period', { number: period[1]! }) : slot;
  };
}

function Arrow({ label, symbol, material, enabled, onPress }: { label: string; symbol: string; material: string; enabled: boolean; onPress: () => void }) {
  const { colors, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !enabled }}
      disabled={!enabled}
      hitSlop={4}
      onPress={onPress}
      style={{ width: minTouchTarget + 8, height: minTouchTarget + 8, alignItems: 'center', justifyContent: 'center', opacity: enabled ? 1 : 0.3 }}>
      <SymbolView name={{ ios: symbol, android: material, web: material } as SymbolViewProps['name']} size={26} tintColor={colors.primary} />
    </Pressable>
  );
}
