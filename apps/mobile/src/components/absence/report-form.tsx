import { randomUUID } from 'expo-crypto';
import { useRef, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Sheet, Text, TextField } from '@/components/ui';
import {
  absenceDateBounds,
  initialAbsenceForm,
  MAX_NOTE_LENGTH,
  REASON_CODES,
  validateAbsenceForm,
  withFromDate,
  type AbsenceForm,
  type AbsenceFormError,
} from '@/lib/absence/form';
import { useReportAbsence } from '@/lib/absence/queries';
import { localDateIn } from '@/lib/attendance/dates';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { DateStepper } from './date-stepper';

export interface AbsenceReportSheetProps {
  visible: boolean;
  schoolId: string;
  studentId: string;
  childName: string;
  onClose: () => void;
  onSent: () => void;
}

const ERROR_KEY: Record<AbsenceFormError, `mobile.absence.form.errors.${AbsenceFormError}`> = {
  order: 'mobile.absence.form.errors.order',
  tooLong: 'mobile.absence.form.errors.tooLong',
  tooEarly: 'mobile.absence.form.errors.tooEarly',
  tooFar: 'mobile.absence.form.errors.tooFar',
  noteTooLong: 'mobile.absence.form.errors.noteTooLong',
};

/** A parent tells the school ahead of time that the child will be away: dates, a reason and an optional note. */
export function AbsenceReportSheet({ visible, schoolId, studentId, childName, onClose, onSent }: AbsenceReportSheetProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  // The device's day: the school's own day is judged by the API (the form allows a day of slack).
  const [today] = useState(() => localDateIn(Intl.DateTimeFormat().resolvedOptions().timeZone, Date.now()));
  const [form, setForm] = useState<AbsenceForm>(() => initialAbsenceForm(today));
  const [attempted, setAttempted] = useState(false);
  // One key per report: a retry after a dropped answer files it once.
  const key = useRef(randomUUID());
  const send = useReportAbsence(schoolId, studentId);
  const bounds = absenceDateBounds(today);
  const errors = validateAbsenceForm(form, today);

  async function submit() {
    setAttempted(true);
    if (errors.length > 0) return;
    try {
      await send.mutateAsync({ form, idempotencyKey: key.current });
    } catch {
      return; // shown below with the API's own words
    }
    key.current = randomUUID();
    setForm(initialAbsenceForm(today));
    setAttempted(false);
    send.reset();
    onSent();
    onClose();
  }

  return (
    <Sheet visible={visible} onClose={onClose} title={t('mobile.absence.form.title', { name: childName })}>
      <DateStepper label={t('mobile.absence.form.from')} value={form.fromDate} min={bounds.min} max={bounds.max} onChange={(date) => setForm((current) => withFromDate(current, date))} />
      <DateStepper label={t('mobile.absence.form.to')} value={form.toDate} min={form.fromDate} max={bounds.max} onChange={(date) => setForm((current) => ({ ...current, toDate: date }))} />

      <View style={{ gap: spacing[2] }}>
        <Text variant="label">{t('mobile.absence.form.reason')}</Text>
        <View accessibilityRole="radiogroup" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
          {REASON_CODES.map((code) => {
            const selected = form.reasonCode === code;
            return (
              <Pressable
                key={code}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                onPress={() => setForm((current) => ({ ...current, reasonCode: code }))}
                style={{
                  minHeight: minTouchTarget,
                  justifyContent: 'center',
                  paddingHorizontal: spacing[4],
                  borderRadius: radii.full,
                  backgroundColor: selected ? colors.primary : colors.muted,
                }}>
                <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
                  {t(`mobile.absence.reason.${code}`)}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      <TextField
        label={t('mobile.absence.form.note')}
        hint={t('mobile.absence.form.noteHint', { name: childName })}
        value={form.note}
        onChangeText={(note) => setForm((current) => ({ ...current, note }))}
        maxLength={MAX_NOTE_LENGTH}
        multiline
      />

      {attempted && errors.length > 0 ? (
        <View accessibilityRole="alert" style={{ gap: spacing[1] }}>
          {errors.map((error) => (
            <Text key={error} tone="destructive">
              {t(ERROR_KEY[error])}
            </Text>
          ))}
        </View>
      ) : null}
      {send.isError ? <ProblemBanner error={send.error} fallbackTitle={t('mobile.absence.form.failedTitle')} fallbackBody={t('mobile.absence.form.failedBody')} /> : null}

      <Button label={t('mobile.absence.form.submit')} loading={send.isPending} onPress={() => void submit()} />
      <Button variant="ghost" label={t('common.cancel')} onPress={onClose} />
    </Sheet>
  );
}
