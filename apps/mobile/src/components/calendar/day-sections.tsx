import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import type { DayEntry } from '@/lib/calendar/bucket';
import type { IsoDate } from '@/lib/calendar/civil';
import { dateHeading, type LabelContext } from '@/lib/calendar/labels';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';
import { ItemRow } from './item-row';

/** One day with its items: a heading (the date in the chosen calendar, "Today" when it is) and a row per item. */
export function DaySection({
  date,
  entries,
  context,
  today,
  showEmpty = true,
  compact = false,
}: {
  date: IsoDate;
  entries: readonly DayEntry[];
  context: LabelContext;
  today: IsoDate;
  showEmpty?: boolean;
  compact?: boolean;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  if (entries.length === 0 && !showEmpty) return null;
  const heading = dateHeading(date, context.calendar, context.locale);
  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="heading" style={{ fontSize: 17, lineHeight: 24 }} accessibilityLabel={date === today ? `${heading}. ${t('mobile.calendar.a11y.today')}` : heading}>
        {date === today ? t('mobile.calendar.todayHeading', { date: heading }) : heading}
      </Text>
      {entries.length === 0 ? (
        <Text variant="caption" style={{ color: colors.mutedForeground }}>
          {t('mobile.calendar.nothingThisDay')}
        </Text>
      ) : (
        entries.map((entry) => <ItemRow key={entry.key} entry={entry} context={context} compact={compact} />)
      )}
    </View>
  );
}

/** The seven days of a week, one after the other. */
export function WeekList({
  days,
  entries,
  context,
  today,
}: {
  days: readonly IsoDate[];
  entries: ReadonlyMap<IsoDate, readonly DayEntry[]>;
  context: LabelContext;
  today: IsoDate;
}) {
  const { spacing } = useAppTheme();
  return (
    <View style={{ gap: spacing[4] }}>
      {days.map((date) => (
        <DaySection key={date} date={date} entries={entries.get(date) ?? []} context={context} today={today} compact />
      ))}
    </View>
  );
}

/** The days that have something, in order (an agenda). */
export function AgendaList({
  days,
  entries,
  context,
  today,
}: {
  days: readonly IsoDate[];
  entries: ReadonlyMap<IsoDate, readonly DayEntry[]>;
  context: LabelContext;
  today: IsoDate;
}) {
  const { spacing } = useAppTheme();
  return (
    <View style={{ gap: spacing[4] }}>
      {days.map((date) => (
        <DaySection key={date} date={date} entries={entries.get(date) ?? []} context={context} today={today} showEmpty={false} />
      ))}
    </View>
  );
}
