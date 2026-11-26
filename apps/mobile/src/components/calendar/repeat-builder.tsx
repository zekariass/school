import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { Button, Sheet, Text } from '@/components/ui';
import { addDays, type IsoDate } from '@/lib/calendar/civil';
import { dateText, type LabelContext } from '@/lib/calendar/labels';
import { weekdayName, weekdayShort } from '@/lib/calendar/names';
import { weekdayOrder } from '@/lib/calendar/grid';
import {
  describeRepeat,
  MAX_COUNT,
  MAX_INTERVAL,
  MAX_UNTIL_YEARS,
  repeatProblems,
  skipsShortMonths,
  type RepeatEnd,
  type RepeatFreq,
  type RepeatState,
} from '@/lib/calendar/recurrence';
import { useLooseT } from '@/lib/calendar/use-labels';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Stepper } from './stepper';
import { ToggleChips } from './toggle-chips';

/**
 * The repeat of an event, built from the API's subset: every day, week (on chosen weekdays) or month (on chosen days), every 1 to 99
 * times, never ending, until a date or after a number of times. A plain sentence says what it will do; the same sentence the
 * detail screen shows afterwards.
 */
export function RepeatBuilder({ value, onChange, startDate, context, disabledReason }: { value: RepeatState; onChange: (next: RepeatState) => void; startDate: IsoDate; context: LabelContext; disabledReason?: string | undefined }) {
  const t = useTranslations();
  const tx = useLooseT();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const [days, setDays] = useState(false);
  const problems = repeatProblems(value, startDate);
  const summary = describeRepeat(value, {
    t: context.t,
    weekdayName: (weekday) => weekdayName(weekday, context.locale),
    formatDate: (date) => dateText(date, context.calendar, context.locale),
  });
  const set = (patch: Partial<RepeatState>) => onChange({ ...value, ...patch });
  const unit = value.freq === 'DAILY' ? t('mobile.calendar.repeat.unitDays') : value.freq === 'WEEKLY' ? t('mobile.calendar.repeat.unitWeeks') : t('mobile.calendar.repeat.unitMonths');

  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="label">{t('mobile.calendar.repeat.title')}</Text>
      {disabledReason ? <Text variant="caption">{disabledReason}</Text> : null}
      <Chips<RepeatFreq>
        accessibilityLabel={t('mobile.calendar.repeat.title')}
        value={value.freq}
        onChange={(freq) => set({ freq })}
        chips={[
          { value: 'NONE', label: t('mobile.calendar.repeat.none') },
          { value: 'DAILY', label: t('mobile.calendar.repeat.every.day') },
          { value: 'WEEKLY', label: t('mobile.calendar.repeat.every.week') },
          { value: 'MONTHLY', label: t('mobile.calendar.repeat.every.month') },
        ]}
      />
      {value.freq !== 'NONE' ? (
        <>
          <Stepper
            label={t('mobile.calendar.repeat.interval', { unit })}
            value={String(value.interval)}
            labels={{ smallBack: t('mobile.calendar.repeat.less'), smallForward: t('mobile.calendar.repeat.more'), largeBack: t('mobile.calendar.repeat.lessTen'), largeForward: t('mobile.calendar.repeat.moreTen') }}
            onStep={(direction, size) => set({ interval: Math.min(MAX_INTERVAL, Math.max(1, value.interval + direction * (size === 2 ? 10 : 1))) })}
          />

          {value.freq === 'WEEKLY' ? (
            <ToggleChips<`${number}`>
              accessibilityLabel={t('mobile.calendar.repeat.weekdays')}
              selected={value.weekdays.map((day) => `${day}` as const)}
              onChange={(next) => set({ weekdays: next.map(Number) })}
              chips={weekdayOrder(1).map((day) => ({ value: `${day}` as const, label: weekdayShort(day, context.locale) }))}
            />
          ) : null}

          {value.freq === 'MONTHLY' ? (
            <View style={{ gap: spacing[2] }}>
              <Text>{t('mobile.calendar.repeat.monthDays', { days: [...value.monthDays].sort((a, b) => a - b).join(', ') })}</Text>
              <Button variant="ghost" fullWidth={false} label={t('mobile.calendar.repeat.chooseDays')} onPress={() => setDays(true)} />
              {skipsShortMonths(value) ? <Text variant="caption">{t('mobile.calendar.repeat.shortMonths')}</Text> : null}
            </View>
          ) : null}

          <Chips<RepeatEnd>
            accessibilityLabel={t('mobile.calendar.repeat.endLabel')}
            value={value.end}
            onChange={(end) => set({ end, ...(end === 'until' && !value.until ? { until: addDays(startDate, 90) } : {}) })}
            chips={[
              { value: 'never', label: t('mobile.calendar.repeat.end.never') },
              { value: 'until', label: t('mobile.calendar.repeat.end.until') },
              { value: 'count', label: t('mobile.calendar.repeat.end.count') },
            ]}
          />
          {value.end === 'until' ? (
            <Stepper
              label={t('mobile.calendar.repeat.untilLabel')}
              value={value.until ? dateText(value.until, context.calendar, context.locale) : ''}
              labels={{ smallBack: t('mobile.calendar.stepper.dayBefore'), smallForward: t('mobile.calendar.stepper.dayAfter'), largeBack: t('mobile.calendar.stepper.weekBefore'), largeForward: t('mobile.calendar.stepper.weekAfter') }}
              onStep={(direction, size) => set({ until: addDays(value.until || startDate, direction * (size === 2 ? 7 : 1)) })}
            />
          ) : null}
          {value.end === 'count' ? (
            <Stepper
              label={t('mobile.calendar.repeat.countLabel')}
              value={String(value.count)}
              labels={{ smallBack: t('mobile.calendar.repeat.less'), smallForward: t('mobile.calendar.repeat.more'), largeBack: t('mobile.calendar.repeat.lessTen'), largeForward: t('mobile.calendar.repeat.moreTen') }}
              onStep={(direction, size) => set({ count: Math.min(MAX_COUNT, Math.max(1, value.count + direction * (size === 2 ? 10 : 1))) })}
            />
          ) : null}

          <View accessible accessibilityLabel={`${t('mobile.calendar.repeat.summaryLabel')}: ${summary}`} style={{ padding: spacing[3], borderRadius: radii.lg, backgroundColor: colors.muted, gap: 2 }}>
            <Text variant="caption">{t('mobile.calendar.repeat.summaryLabel')}</Text>
            <Text>{summary}</Text>
          </View>
          <Text variant="caption">{t('mobile.calendar.repeat.limits', { interval: MAX_INTERVAL, count: MAX_COUNT, years: MAX_UNTIL_YEARS })}</Text>
          {problems.map((problem) => (
            <Text key={problem} variant="caption" style={{ color: colors.destructive }} accessibilityLiveRegion="polite">
              {tx(`mobile.calendar.form.problem.repeat.${problem}`, { interval: MAX_INTERVAL, count: MAX_COUNT, years: MAX_UNTIL_YEARS })}
            </Text>
          ))}
        </>
      ) : null}

      <Sheet visible={days} onClose={() => setDays(false)} title={t('mobile.calendar.repeat.chooseDays')}>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }} accessibilityRole="menu">
          {Array.from({ length: 31 }, (_, i) => i + 1).map((day) => {
            const on = value.monthDays.includes(day);
            return (
              <Pressable
                key={day}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: on }}
                accessibilityLabel={String(day)}
                onPress={() => {
                  const next = on ? value.monthDays.filter((d) => d !== day) : [...value.monthDays, day];
                  if (next.length > 0) set({ monthDays: next });
                }}
                style={{
                  width: minTouchTarget,
                  height: minTouchTarget,
                  borderRadius: radii.full,
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderWidth: 1,
                  borderColor: on ? colors.primary : colors.border,
                  backgroundColor: on ? colors.primary : colors.card,
                }}>
                <Text variant="label" style={{ color: on ? colors.primaryForeground : colors.foreground }}>
                  {day}
                </Text>
              </Pressable>
            );
          })}
        </View>
        <Button label={t('common.done')} onPress={() => setDays(false)} />
      </Sheet>
    </View>
  );
}
