import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { formatCivilDate } from '@/lib/calendar/format-instant';
import { canStepDate, clockText, scheduleProblem, stepDate, stepMinutes, usableZone, type SchedulePick } from '@/lib/feed/schedule';
import { useClock } from '@/lib/feed/use-clock';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * Picks the date and time of a scheduled post in the SCHOOL's time zone: the zone is named under the picker and the phone's own
 * zone never enters. Steppers instead of a native picker, because a native picker always speaks the phone's zone.
 */
export function SchedulePicker({ value, timeZone, onChange }: { value: SchedulePick; timeZone: string; onChange: (value: SchedulePick) => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const now = useClock(15_000);
  const problem = scheduleProblem(value, timeZone, now);
  const zone = usableZone(timeZone);

  return (
    <View style={{ gap: spacing[2], padding: spacing[4], borderRadius: radii.xl, backgroundColor: colors.card, borderWidth: 1, borderColor: colors.border }}>
      <Text variant="label">{t('mobile.feed.schedule.title')}</Text>
      <Stepper
        label={t('mobile.feed.schedule.date')}
        value={formatCivilDate(value.date, { locale, calendar })}
        previousLabel={t('mobile.feed.schedule.dayBefore')}
        nextLabel={t('mobile.feed.schedule.dayAfter')}
        canPrevious={canStepDate(value, -1, zone, now)}
        canNext={canStepDate(value, 1, zone, now)}
        onPrevious={() => onChange(stepDate(value, -1))}
        onNext={() => onChange(stepDate(value, 1))}
      />
      <Stepper
        label={t('mobile.feed.schedule.time')}
        value={clockText(value.minutes)}
        previousLabel={t('mobile.feed.schedule.earlier')}
        nextLabel={t('mobile.feed.schedule.later')}
        canPrevious
        canNext
        onPrevious={() => onChange(stepMinutes(value, -1, 15))}
        onNext={() => onChange(stepMinutes(value, 1, 15))}
      />
      <Text variant="caption">{t('mobile.feed.schedule.zone', { zone: zone.replace(/_/g, ' ') })}</Text>
      {problem ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {problem === 'past' ? t('mobile.feed.schedule.past') : problem === 'too_far' ? t('mobile.feed.schedule.tooFar') : t('mobile.feed.schedule.invalid')}
        </Text>
      ) : null}
    </View>
  );
}

function Stepper({
  label,
  value,
  previousLabel,
  nextLabel,
  canPrevious,
  canNext,
  onPrevious,
  onNext,
}: {
  label: string;
  value: string;
  previousLabel: string;
  nextLabel: string;
  canPrevious: boolean;
  canNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  const { spacing } = useAppTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }} accessible={false}>
      <Text variant="caption" style={{ width: 52 }}>
        {label}
      </Text>
      <StepButton label={previousLabel} ios="chevron.left" android="chevron_left" disabled={!canPrevious} onPress={onPrevious} />
      <Text variant="label" style={{ flex: 1, textAlign: 'center' }} accessibilityLiveRegion="polite">
        {value}
      </Text>
      <StepButton label={nextLabel} ios="chevron.right" android="chevron_right" disabled={!canNext} onPress={onNext} />
    </View>
  );
}

function StepButton({ label, ios, android, disabled, onPress }: { label: string; ios: string; android: string; disabled: boolean; onPress: () => void }) {
  const { colors, minTouchTarget, radii } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={{ width: minTouchTarget, height: minTouchTarget, borderRadius: radii.full, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.muted, opacity: disabled ? 0.4 : 1 }}>
      <SymbolView name={{ ios, android, web: android } as never} size={20} tintColor={colors.primary} />
    </Pressable>
  );
}
