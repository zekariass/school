import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { styleOf } from '@/components/attendance/status-style';
import { useSlotLabel } from '@/components/attendance/date-switcher';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { resolveConflict } from '@/lib/attendance/conflicts';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { useClassTeachers, useMyClass, useRoster } from '@/lib/people/queries';
import { newOpId } from '@/lib/points/op-id';
import { requestFlush, syncEngine, useAttentionOperations } from '@/lib/sync/runtime';
import type { ConflictEntry, QueuedAttendance } from '@/lib/sync/types';
import { isAttendanceOp } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Attendance that needs the teacher: a colleague marked a student differently while this phone was offline (keep
 * theirs or use mine), the school refused marks for good (the API's own reason is shown), or marks waited too long.
 * Nothing here is ever resent on its own: "use mine" makes a NEW operation that names the record just seen.
 */
export default function AttendanceIssues() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const roster = useRoster(cls?.schoolId, classId);
  const teachers = useClassTeachers(cls?.schoolId, classId);
  const attention = useAttentionOperations();

  const operations = useMemo(() => attention.filter((op): op is QueuedAttendance => isAttendanceOp(op) && op.classId === classId), [attention, classId]);
  const studentNames = useMemo(() => new Map((roster.data ?? []).map((student) => [student.id, student.firstName || student.name])), [roster.data]);
  const teacherNames = useMemo(() => new Map((teachers.data ?? []).map((teacher) => [teacher.teacherId, teacher.displayName])), [teachers.data]);

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[4] }}>
      <Stack.Screen options={{ title: t('mobile.attendance.issues.title') }} />
      {operations.length === 0 ? (
        <EmptyState icon={{ ios: 'checkmark.circle', android: 'check_circle' }} title={t('mobile.attendance.issues.emptyTitle')} body={t('mobile.attendance.issues.emptyBody')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      ) : (
        operations.map((op) => <IssueCard key={op.opId} op={op} studentNames={studentNames} teacherNames={teacherNames} className={cls?.name ?? ''} />)
      )}
    </Screen>
  );
}

function IssueCard({ op, studentNames, teacherNames, className }: { op: QueuedAttendance; studentNames: Map<string, string>; teacherNames: Map<string, string>; className: string }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const format = useFormatCivilDate();
  const slotLabel = useSlotLabel();
  const [notice, setNotice] = useState<string | null>(null);
  const when = `${format(op.payload.date)} · ${slotLabel(op.payload.slot)}`;
  const who = (id: string) => studentNames.get(id) ?? t('mobile.attendance.issues.unknownStudent');

  async function settle(ids: readonly string[], choice: 'keep-theirs' | 'use-mine') {
    const resolution = resolveConflict(op, ids, choice, newOpId());
    if (resolution.resend) {
      const result = await syncEngine.enqueue(resolution.resend);
      if (result === 'full') {
        setNotice(t('mobile.attendance.errors.full'));
        return;
      }
      requestFlush(0);
    }
    await syncEngine.updateConflict(op.opId, resolution.remaining);
    setNotice(null);
  }

  if (op.status === 'CONFLICT' && op.conflict) {
    const entries = op.conflict.entries;
    const allIds = entries.map((entry) => entry.studentId);
    return (
      <Card>
        <Text variant="heading">{t('mobile.attendance.issues.conflictTitle')}</Text>
        <Text variant="caption">{`${className} · ${when}`}</Text>
        <Text>{t('mobile.attendance.issues.conflictBody')}</Text>
        {entries.map((entry) => (
          <ConflictEntryView key={entry.studentId} entry={entry} student={who(entry.studentId)} teacher={(entry.stored.recordedBy && teacherNames.get(entry.stored.recordedBy)) || t('mobile.attendance.issues.unknownTeacher')} onSettle={(choice) => void settle([entry.studentId], choice)} />
        ))}
        {entries.length > 1 ? (
          <View style={{ gap: spacing[2] }}>
            <Button variant="ghost" label={t('mobile.attendance.issues.keepAllTheirs')} onPress={() => void settle(allIds, 'keep-theirs')} />
            <Button variant="ghost" label={t('mobile.attendance.issues.useAllMine')} onPress={() => void settle(allIds, 'use-mine')} />
          </View>
        ) : null}
        {notice ? <Text tone="destructive">{notice}</Text> : null}
      </Card>
    );
  }

  const names = op.payload.records.slice(0, 3).map((record) => who(record.studentId)).join(', ') + (op.payload.records.length > 3 ? ` +${op.payload.records.length - 3}` : '');
  const expired = op.status === 'EXPIRED';
  return (
    <Card accessibilityRole="alert">
      <Text variant="heading" tone="destructive">
        {expired ? t('mobile.attendance.issues.expiredTitle', { class: className, date: format(op.payload.date) }) : t('mobile.attendance.issues.rejectedTitle', { class: className, date: format(op.payload.date) })}
      </Text>
      <Text variant="caption">{`${when} · ${names}`}</Text>
      <Text>{expired ? t('mobile.attendance.issues.expiredBody') : op.problem?.detail || t('mobile.attendance.issues.rejectedBody')}</Text>
      <View style={{ gap: spacing[2] }}>
        <Button
          label={t('mobile.attendance.issues.openRoll')}
          onPress={() => {
            void syncEngine.dismiss(op.opId);
            router.replace({ pathname: '/class/[classId]/attendance', params: { classId: op.classId, date: op.payload.date, slot: op.payload.slot } });
          }}
        />
        <Button variant="ghost" label={t('mobile.attendance.issues.dismiss')} onPress={() => void syncEngine.dismiss(op.opId)} />
      </View>
    </Card>
  );
}

function ConflictEntryView({ entry, student, teacher, onSettle }: { entry: ConflictEntry; student: string; teacher: string; onSettle: (choice: 'keep-theirs' | 'use-mine') => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const theirs = styleOf(entry.stored.status);
  const mine = styleOf(entry.submitted.status);
  const minutes = (value: number | null) => (value ? ` (${t('mobile.attendance.hint.minutesLate', { minutes: value })})` : '');
  const theirsText = `${t(theirs.labelKey)}${minutes(entry.stored.minutesLate)}`;
  const mineText = `${t(mine.labelKey)}${minutes(entry.submitted.minutesLate)}`;
  return (
    <View
      accessible
      style={{ gap: spacing[2], padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.muted }}>
      <Text variant="label">{t('mobile.attendance.issues.theirs', { teacher, student, status: theirsText })}</Text>
      <Text>{t('mobile.attendance.issues.mine', { status: mineText })}</Text>
      <View style={{ flexDirection: 'row', gap: spacing[2] }}>
        <View style={{ flex: 1 }}>
          <Button variant="ghost" label={t('mobile.attendance.issues.keepTheirs')} accessibilityLabel={t('mobile.attendance.issues.keepTheirsFor', { student })} onPress={() => onSettle('keep-theirs')} />
        </View>
        <View style={{ flex: 1 }}>
          <Button label={t('mobile.attendance.issues.useMine')} accessibilityLabel={t('mobile.attendance.issues.useMineFor', { student })} onPress={() => onSettle('use-mine')} />
        </View>
      </View>
    </View>
  );
}
