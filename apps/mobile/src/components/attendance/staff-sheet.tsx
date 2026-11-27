import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useRef, useState } from 'react';
import { ActivityIndicator, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Sheet, Text, TextField } from '@/components/ui';
import {
  STAFF_NOTE_MAX,
  STAFF_REASON_MAX,
  STAFF_STATUSES,
  checkMark,
  correctionFormFor,
  correctionRequest,
  emptyMarkForm,
  markInput,
  type CorrectionForm,
  type MarkForm,
  type StaffEntry,
  type StaffStatus,
} from '@/lib/attendance/staff/model';
import { useCorrectStaff, useMarkStaffDay, useStaffRecord } from '@/lib/attendance/staff/queries';
import { formatInstant } from '@/lib/calendar/format-instant';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { newOpId } from '@/lib/points/op-id';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { staffStatusStyle } from './staff-status';

export interface StaffSheetProps {
  /** The staff member opened (null: closed). */
  entry: StaffEntry | null;
  schoolId: string;
  date: string;
  /** The school's zone: times of the history are shown in it. */
  timeZone: string;
  canManage: boolean;
  onClose: () => void;
  /** After a write: a short message for the screen ("Saved."). */
  onDone: (message: 'saved' | 'exists') => void;
}

const QUICK_MINUTES = [5, 10, 15, 30] as const;

/**
 * One staff member on the day. No record yet: a manager marks it (marking only creates). A record: its history and, for
 * a manager, a correction with a required reason (append-only). Read-only holders see the record and its history.
 */
export function StaffSheet(props: StaffSheetProps) {
  const { entry, onClose } = props;
  // Keyed by staff member and record version: opening another one, or a refreshed record, starts from its own values.
  return entry ? (
    <Sheet visible onClose={onClose} title={entry.displayName}>
      <StaffSheetBody key={`${entry.staffId}|${entry.record?.id ?? 'new'}|${entry.record?.version ?? 0}`} {...props} entry={entry} />
    </Sheet>
  ) : null;
}

function StaffSheetBody({ entry, schoolId, date, timeZone, canManage, onClose, onDone }: StaffSheetProps & { entry: StaffEntry }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const record = entry.record;
  return (
    <View style={{ gap: spacing[4] }}>
      {entry.jobTitle ? <Text tone="muted">{entry.jobTitle}</Text> : null}
      {record ? (
        <>
          <RecordSummary entry={entry} />
          {canManage ? <CorrectionFormView entry={entry} schoolId={schoolId} onClose={onClose} onDone={onDone} /> : null}
          <History schoolId={schoolId} recordId={record.id} timeZone={timeZone} />
        </>
      ) : canManage ? (
        <MarkFormView entry={entry} schoolId={schoolId} date={date} onClose={onClose} onDone={onDone} />
      ) : (
        <Text tone="muted">{t('mobile.attendance.staff.notMarked')}</Text>
      )}
    </View>
  );
}

function RecordSummary({ entry }: { entry: StaffEntry }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const record = entry.record!;
  const look = staffStatusStyle(record.status);
  return (
    <View style={{ gap: spacing[1] }}>
      <Text variant="caption">{t('mobile.attendance.staff.sheet.recorded')}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
        <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={22} tintColor={look.accent(colors)} />
        <Text variant="label">{t(look.labelKey)}</Text>
        {record.status === 'LATE' && record.minutesLate ? <Text tone="muted">{t('mobile.attendance.staff.minutesLate', { minutes: record.minutesLate })}</Text> : null}
      </View>
      {record.note ? <Text>{record.note}</Text> : null}
    </View>
  );
}

function StatusChoice({ value, onChange }: { value: StaffStatus; onChange: (status: StaffStatus) => void }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={t('mobile.attendance.staff.sheet.status')} style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
      {STAFF_STATUSES.map((status) => {
        const look = staffStatusStyle(status);
        const accent = look.accent(colors);
        const selected = status === value;
        return (
          <Pressable
            key={status}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            accessibilityLabel={t(look.labelKey)}
            onPress={() => onChange(status)}
            style={{
              minHeight: minTouchTarget,
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing[2],
              paddingHorizontal: spacing[4],
              borderRadius: radii.full,
              borderWidth: 2,
              borderColor: selected ? accent : colors.border,
              backgroundColor: selected ? colors.muted : colors.card,
            }}>
            <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={22} tintColor={accent} />
            <Text variant="label">{t(look.labelKey)}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

function MinutesField({ form, onChange }: { form: MarkForm; onChange: (minutes: string) => void }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const check = checkMark(form);
  if (form.status !== 'LATE') return null;
  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="label">{t('mobile.attendance.staff.sheet.minutesLate')}</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {QUICK_MINUTES.map((value) => (
          <Pressable
            key={value}
            accessibilityRole="button"
            accessibilityState={{ selected: form.minutes === String(value) }}
            accessibilityLabel={t('mobile.attendance.staff.sheet.minutesOption', { minutes: value })}
            onPress={() => onChange(String(value))}
            style={{
              minHeight: minTouchTarget,
              minWidth: minTouchTarget + 8,
              alignItems: 'center',
              justifyContent: 'center',
              paddingHorizontal: spacing[3],
              borderRadius: radii.full,
              borderWidth: 2,
              borderColor: form.minutes === String(value) ? colors.warning : colors.border,
              backgroundColor: colors.card,
            }}>
            <Text variant="label">{value}</Text>
          </Pressable>
        ))}
      </View>
      <TextField
        label={t('mobile.attendance.staff.sheet.minutesCustom')}
        value={form.minutes}
        onChangeText={(text) => onChange(text.replace(/[^0-9]/g, '').slice(0, 3))}
        keyboardType="number-pad"
        maxLength={3}
        error={check.minutesInvalid ? t('mobile.attendance.staff.sheet.minutesError') : undefined}
      />
    </View>
  );
}

function MarkFormView({ entry, schoolId, date, onClose, onDone }: { entry: StaffEntry; schoolId: string; date: string; onClose: () => void; onDone: StaffSheetProps['onDone'] }) {
  const t = useTranslations();
  const [form, setForm] = useState<MarkForm>(emptyMarkForm);
  const mark = useMarkStaffDay(schoolId, date);
  const check = checkMark(form);

  async function save() {
    const input = markInput(entry.staffId, form);
    if (!input) return;
    try {
      const result = await mark.mutateAsync({ records: [input] });
      // Somebody marked this person meanwhile: their record stays (a change is a correction).
      onDone(result.outcomes.some((outcome) => outcome.outcome === 'EXISTS') ? 'exists' : 'saved');
      onClose();
    } catch {
      // Shown below from `mark.error`; the form keeps what was typed.
    }
  }

  return (
    <View style={{ gap: 16 }}>
      <StatusChoice value={form.status} onChange={(status) => setForm({ ...form, status })} />
      <MinutesField form={form} onChange={(minutes) => setForm({ ...form, minutes })} />
      <TextField
        label={t('mobile.attendance.staff.sheet.note')}
        hint={t('mobile.attendance.staff.sheet.noteHint')}
        value={form.note}
        onChangeText={(note) => setForm({ ...form, note })}
        maxLength={STAFF_NOTE_MAX}
        multiline
        autoCapitalize="sentences"
      />
      {mark.isError ? <ProblemBanner error={mark.error} fallbackTitle={t('mobile.attendance.staff.errors.saveTitle')} fallbackBody={t('mobile.attendance.staff.errors.saveBody')} /> : null}
      <Button label={t('mobile.attendance.staff.sheet.save')} loading={mark.isPending} disabled={!check.valid} onPress={() => void save()} />
    </View>
  );
}

function CorrectionFormView({ entry, schoolId, onClose, onDone }: { entry: StaffEntry; schoolId: string; onClose: () => void; onDone: StaffSheetProps['onDone'] }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const record = entry.record!;
  const [form, setForm] = useState<CorrectionForm>(() => correctionFormFor(record));
  const [tried, setTried] = useState(false);
  const correct = useCorrectStaff(schoolId);
  // One key per correction: a retry after a lost answer is the same correction, a changed form is a new one.
  const key = useRef<{ form: string; id: string } | null>(null);
  const checked = correctionRequest(record, form);

  async function submit() {
    setTried(true);
    if (!checked.ok) return;
    const fingerprint = JSON.stringify(checked.request);
    if (key.current?.form !== fingerprint) key.current = { form: fingerprint, id: newOpId() };
    try {
      await correct.mutateAsync({ recordId: record.id, body: checked.request, idempotencyKey: key.current.id });
      onDone('saved');
      onClose();
    } catch {
      // Shown below (a clash with another manager is the API's own 409 text).
    }
  }

  const problem = tried && !checked.ok ? checked.problem : null;
  return (
    <View style={{ gap: spacing[4] }}>
      <View style={{ gap: spacing[1] }}>
        <Text variant="heading">{t('mobile.attendance.staff.correct.title')}</Text>
        <Text variant="caption">{t('mobile.attendance.staff.correct.hint')}</Text>
      </View>
      <StatusChoice value={form.status} onChange={(status) => setForm({ ...form, status })} />
      <MinutesField form={form} onChange={(minutes) => setForm({ ...form, minutes })} />
      <TextField
        label={t('mobile.attendance.staff.sheet.note')}
        hint={t('mobile.attendance.staff.sheet.noteHint')}
        value={form.note}
        onChangeText={(note) => setForm({ ...form, note })}
        maxLength={STAFF_NOTE_MAX}
        multiline
        autoCapitalize="sentences"
      />
      <TextField
        label={t('mobile.attendance.staff.correct.reason')}
        hint={t('mobile.attendance.staff.correct.reasonHint')}
        value={form.reason}
        onChangeText={(reason) => setForm({ ...form, reason })}
        maxLength={STAFF_REASON_MAX}
        multiline
        autoCapitalize="sentences"
        error={problem === 'reason' ? t('mobile.attendance.staff.correct.reasonMissing') : problem === 'unchanged' ? t('mobile.attendance.staff.correct.unchanged') : undefined}
      />
      {correct.isError ? <ProblemBanner error={correct.error} fallbackTitle={t('mobile.attendance.staff.errors.saveTitle')} fallbackBody={t('mobile.attendance.staff.errors.saveBody')} /> : null}
      <Button label={t('mobile.attendance.staff.correct.submit')} loading={correct.isPending} onPress={() => void submit()} />
    </View>
  );
}

function History({ schoolId, recordId, timeZone }: { schoolId: string; recordId: string; timeZone: string }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const detail = useStaffRecord(schoolId, recordId);
  const label = (status: StaffStatus) => t(staffStatusStyle(status).labelKey);

  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="heading">{t('mobile.attendance.staff.history.title')}</Text>
      {detail.isPending ? <ActivityIndicator color={colors.primary} /> : null}
      {detail.isError ? <ProblemBanner error={detail.error} fallbackTitle={t('mobile.attendance.staff.errors.loadTitle')} fallbackBody={t('mobile.attendance.staff.errors.loadBody')} onRetry={() => void detail.refetch()} /> : null}
      {detail.data && detail.data.corrections.length === 0 ? <Text tone="muted">{t('mobile.attendance.staff.history.empty')}</Text> : null}
      {detail.data?.corrections.map((correction) => (
        <Text key={correction.id}>
          {t('mobile.attendance.staff.history.line', {
            when: formatInstant(correction.correctedAt, { locale, calendar, timeZone }),
            from: label(correction.oldStatus),
            to: label(correction.newStatus),
            reason: correction.reason,
          })}
        </Text>
      ))}
    </View>
  );
}
