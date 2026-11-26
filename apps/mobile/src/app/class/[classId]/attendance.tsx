import { useNetworkState } from 'expo-network';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, RefreshControl, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { DateSwitcher } from '@/components/attendance/date-switcher';
import { RollRow } from '@/components/attendance/roll-row';
import { StudentSheet } from '@/components/attendance/student-sheet';
import { styleOf } from '@/components/attendance/status-style';
import { ProblemBanner } from '@/components/problem-banner';
import { QueueIndicator } from '@/components/sync/queue-indicator';
import { Button, Card, EmptyState, Sheet, Text, TextField } from '@/components/ui';
import { localDateIn } from '@/lib/attendance/dates';
import {
  countStatuses,
  editability,
  effectiveStudents,
  excusedByHint,
  rollTrust,
  type EffectiveStudent,
} from '@/lib/attendance/roll-model';
import { useRoll } from '@/lib/attendance/use-roll';
import { useRollEditor } from '@/lib/attendance/use-roll-editor';
import { formatInstant } from '@/lib/calendar/format-instant';
import { useMe } from '@/lib/auth/use-me';
import { useMyClass } from '@/lib/people/queries';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAgeText } from '@/lib/sync/age-text';
import { isCivilDate } from '@/lib/sync/operations';
import { syncEngine, useAttentionOperations, usePendingOperations } from '@/lib/sync/runtime';
import { isAttendanceOp, type AttendanceStatus } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The teacher's quick roll. Everybody starts unmarked and one confirmed tap marks them all present; after that a tap
 * on a student cycles Present, Absent, Late, Excused, Left early. Every change is saved on the phone first and sent
 * as soon as the network allows, so it works in airplane mode, and the screen always says what is waiting.
 */
export default function ClassAttendance() {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const insets = useSafeAreaInsets();
  const { locale, calendar } = usePreferences();
  const ageText = useAgeText();
  const params = useLocalSearchParams<{ classId: string; date?: string; slot?: string }>();
  const { classId } = params;
  const { cls } = useMyClass(classId);
  const schoolId = cls?.schoolId;
  const me = useMe();
  const network = useNetworkState();

  // Opened from "Open the roll" of an attendance problem: that day and slot, else the school's today.
  const [date, setDate] = useState<string | null>(params.date && isCivilDate(params.date) ? params.date : null);
  const [slot, setSlot] = useState<string | null>(params.slot && params.slot.length <= 10 ? params.slot : null);
  const [now, setNow] = useState(() => Date.now());
  const [refreshing, setRefreshing] = useState(false);
  const [sheetFor, setSheetFor] = useState<string | null>(null);
  const [reasonOpen, setReasonOpen] = useState(false);
  const [reasonDraft, setReasonDraft] = useState('');
  // The edit that was asked for while the reason of an override was still missing: it goes through with the reason.
  const waitingForReason = useRef<
    { kind: 'tap'; student: EffectiveStudent } | { kind: 'set'; student: EffectiveStudent; status: AttendanceStatus; extra?: { minutesLate?: number | null; note?: string } } | { kind: 'all' } | null
  >(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const query = useRoll({ schoolId, classId, date, slot });
  const { roll } = query;
  const pending = usePendingOperations();
  const attention = useAttentionOperations();

  const students = useMemo(() => (roll ? effectiveStudents(roll, pending) : []), [roll, pending]);
  const counts = useMemo(() => countStatuses(students), [students]);
  const trust = roll && query.fetchedAt !== undefined ? rollTrust(query.fetchedAt, now, roll.synthetic, query.fromCache) : 'fresh';
  const mode = roll ? editability(roll, trust, now) : ({ kind: 'stale' } as const);
  const editor = useRollEditor({ roll, ownerId: me.data?.id, editability: mode });
  const readOnly = mode.kind === 'locked' || mode.kind === 'stale';

  const issues = attention.filter((op) => isAttendanceOp(op) && op.classId === classId).length;
  const sheetStudent = students.find((student) => student.studentId === sheetFor) ?? null;
  const today = roll ? localDateIn(roll.timeZone, now) : null;
  const offline = network.isConnected === false;

  const { tap, set } = editor;

  const askReason = useCallback((action: NonNullable<typeof waitingForReason.current>) => {
    waitingForReason.current = action;
    setReasonOpen(true);
  }, []);

  const onTap = useCallback(
    async (student: EffectiveStudent) => {
      const outcome = await tap(student);
      if (outcome === 'needs-reason') askReason({ kind: 'tap', student });
      else if (outcome === 'full') setNotice(t('mobile.attendance.errors.full'));
      else if (outcome === 'invalid') setNotice(t('mobile.attendance.errors.invalid'));
      else setNotice(null);
    },
    [tap, askReason, t],
  );
  const onChoose = useCallback(
    async (student: EffectiveStudent, status: AttendanceStatus) => {
      const outcome = await set(student, status);
      if (outcome === 'needs-reason') askReason({ kind: 'set', student, status });
    },
    [set, askReason],
  );
  const onDetails = useCallback(
    (student: EffectiveStudent) => {
      if (!readOnly) setSheetFor(student.studentId);
    },
    [readOnly],
  );

  /** The reason was given: it is kept for the next edits too, and the waiting edit goes through now. */
  function submitReason() {
    const reason = reasonDraft.trim();
    if (reason.length === 0) return;
    editor.setOverrideReason(reason);
    setReasonOpen(false);
    const action = waitingForReason.current;
    waitingForReason.current = null;
    if (!action) return;
    if (action.kind === 'tap') void tap(action.student, reason);
    else if (action.kind === 'set') void set(action.student, action.status, action.extra, reason);
    else void editor.markAllPresent(students, reason);
  }

  function confirmMarkAll() {
    const unmarked = counts.unmarked;
    if (unmarked === 0) return;
    const excused = excusedByHint(students);
    const body = [
      t('mobile.attendance.markAll.body', { count: unmarked }),
      excused > 0 ? t('mobile.attendance.markAll.excused', { count: excused }) : null,
    ]
      .filter(Boolean)
      .join('\n\n');
    Alert.alert(t('mobile.attendance.markAll.title'), body, [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('mobile.attendance.markAll.confirm'),
        onPress: () =>
          void editor.markAllPresent(students).then((result) => {
            if (result.outcome === 'needs-reason') askReason({ kind: 'all' });
            else if (result.outcome === 'full') setNotice(t('mobile.attendance.errors.full'));
          }),
      },
    ]);
  }

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([query.refetch(), syncEngine.flush(true)]);
    } finally {
      setRefreshing(false);
    }
  }

  const header = <Stack.Screen options={{ title: t('mobile.attendance.title') }} />;

  if (!cls && query.isPending) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5] }}>
        {header}
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  if (!cls) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5] }}>
        {header}
        <EmptyState icon={{ ios: 'questionmark.circle', android: 'help' }} title={t('mobile.classes.notFoundTitle')} body={t('mobile.classes.notFoundBody')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      </View>
    );
  }

  const archived = cls.status === 'ARCHIVED';
  const bottomBar = !readOnly && !archived && roll && counts.unmarked > 0;

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      {header}
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
        contentContainerStyle={{ paddingHorizontal: spacing[5], paddingTop: spacing[2], paddingBottom: (bottomBar ? 112 : spacing[6]) + insets.bottom, gap: spacing[3] }}>
        {roll && today ? (
          <DateSwitcher
            date={roll.date}
            today={today}
            onDate={(next) => setDate(next === today ? null : next)}
            slots={roll.slots}
            slot={roll.slot}
            onSlot={(next) => setSlot(next === roll.slots[0] ? null : next)}
          />
        ) : null}

        <StatusLine
          fetchedAt={query.fetchedAt}
          fromCache={query.fromCache}
          synthetic={roll?.synthetic ?? false}
          offline={offline}
          now={now}
          classId={classId!}
          loading={query.isFetching && !query.isPending}
        />

        {issues > 0 ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('mobile.attendance.issues.banner', { count: issues })}
            onPress={() => router.push({ pathname: '/class/[classId]/attendance-issues', params: { classId: classId! } })}
            style={{ padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.destructive, backgroundColor: colors.muted, gap: spacing[1] }}>
            <Text variant="label" tone="destructive">
              {t('mobile.attendance.issues.banner', { count: issues })}
            </Text>
            <Text variant="caption">{t('mobile.attendance.issues.bannerHint')}</Text>
          </Pressable>
        ) : null}

        {roll && mode.kind === 'locked' ? <Notice tone="warning" title={t('mobile.attendance.lock.lockedTitle')} body={t('mobile.attendance.lock.lockedBody', { time: lockTime(roll.locksAt, roll.timeZone, locale, calendar) })} /> : null}
        {roll && mode.kind === 'override' ? <Notice tone="warning" title={t('mobile.attendance.lock.overrideTitle')} body={t('mobile.attendance.lock.overrideBody', { time: lockTime(roll.locksAt, roll.timeZone, locale, calendar) })} /> : null}
        {roll && mode.kind === 'stale' ? <Notice tone="warning" title={t('mobile.attendance.stale.title')} body={t('mobile.attendance.stale.body', { age: ageText(query.fetchedAt ?? now, now) })} /> : null}
        {roll?.synthetic && mode.kind !== 'stale' ? <Notice tone="info" title={t('mobile.attendance.synthetic.title')} body={t('mobile.attendance.synthetic.body', { age: ageText(query.fetchedAt ?? now, now) })} /> : null}
        {archived ? <Text tone="muted">{t('mobile.classes.archivedNote')}</Text> : null}
        {notice ? (
          <View accessibilityRole="alert" style={{ padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.destructive, backgroundColor: colors.muted }}>
            <Text tone="destructive">{notice}</Text>
          </View>
        ) : null}

        {!roll && query.isPending ? <ActivityIndicator color={colors.primary} /> : null}
        {!roll && !query.isPending && query.isError ? (
          offline || (query.error as { status?: number } | null)?.status === undefined ? (
            <EmptyState
              icon={{ ios: 'wifi.slash', android: 'wifi_off' }}
              title={t('mobile.attendance.unavailable.title')}
              body={t('mobile.attendance.unavailable.body')}>
              <Button label={t('common.retry')} onPress={() => void query.refetch()} />
            </EmptyState>
          ) : (
            <ProblemBanner error={query.error} fallbackTitle={t('mobile.attendance.errors.loadTitle')} fallbackBody={t('mobile.attendance.errors.loadBody')} onRetry={() => void query.refetch()} />
          )
        ) : null}

        {roll && students.length === 0 ? (
          <EmptyState icon={{ ios: 'person.3', android: 'groups' }} title={t('mobile.classes.rosterEmptyTitle')} body={t('mobile.classes.rosterEmptyBody')} />
        ) : null}

        {roll && students.length > 0 ? (
          <>
            <Counts counts={counts} />
            {counts.unmarked === counts.total && !readOnly ? (
              <Card>
                <Text variant="heading">{t('mobile.attendance.start.title')}</Text>
                <Text tone="muted">{t('mobile.attendance.start.body')}</Text>
              </Card>
            ) : null}
            <View style={{ gap: spacing[2] }}>
              {students.map((student) => (
                <RollRow key={student.studentId} student={student} readOnly={readOnly} onTap={onTap} onDetails={onDetails} onChoose={onChoose} />
              ))}
            </View>
            {counts.unmarked === 0 ? <Text tone="muted" style={{ textAlign: 'center' }}>{t('mobile.attendance.allMarked')}</Text> : null}
          </>
        ) : null}
      </ScrollView>

      {bottomBar ? (
        <View
          style={{
            position: 'absolute',
            left: spacing[4],
            right: spacing[4],
            bottom: Math.max(insets.bottom, spacing[3]) + spacing[2],
          }}>
          <Button label={t('mobile.attendance.markAll.button', { count: counts.unmarked })} onPress={confirmMarkAll} />
        </View>
      ) : null}

      <StudentSheet
        student={sheetStudent}
        onClose={() => setSheetFor(null)}
        onSave={(student, status, extra) => {
          void editor.set(student, status, extra).then((outcome) => {
            if (outcome === 'needs-reason') askReason({ kind: 'set', student, status, extra });
          });
        }}
      />

      <Sheet visible={reasonOpen} onClose={() => setReasonOpen(false)} title={t('mobile.attendance.lock.reasonTitle')}>
        <Text tone="muted">{t('mobile.attendance.lock.reasonBody')}</Text>
        <TextField
          label={t('mobile.attendance.lock.reasonLabel')}
          value={reasonDraft}
          onChangeText={setReasonDraft}
          maxLength={200}
          multiline
          autoFocus
        />
        <Button
          label={t('common.continue')}
          disabled={reasonDraft.trim().length === 0}
          onPress={submitReason}
        />
        <Button
          variant="ghost"
          label={t('common.cancel')}
          onPress={() => {
            waitingForReason.current = null;
            setReasonOpen(false);
          }}
        />
      </Sheet>
    </View>
  );
}

function lockTime(locksAt: string | null, timeZone: string, locale: string, calendar: 'gregorian' | 'ethiopian'): string {
  return locksAt ? formatInstant(locksAt, { locale, calendar, timeZone }) : '';
}

/**
 * The persistent "is my work safe?" line: what waits to be sent, else whether this is the phone's copy, else that
 * everything is sent. Offline shows beside it.
 */
function StatusLine({
  fetchedAt,
  fromCache,
  synthetic,
  offline,
  now,
  classId,
  loading,
}: {
  fetchedAt: number | undefined;
  fromCache: boolean;
  synthetic: boolean;
  offline: boolean;
  now: number;
  classId: string;
  loading: boolean;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const ageText = useAgeText();
  const waiting = usePendingOperations().filter((op) => op.classId === classId).length;
  const age = fetchedAt !== undefined ? ageText(fetchedAt, now) : null;

  return (
    <View style={{ gap: spacing[2] }} accessibilityLiveRegion="polite">
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing[2] }}>
        {offline ? <Chip symbol={{ ios: 'wifi.slash', android: 'wifi_off' }} label={t('mobile.sync.offline')} color={colors.warning} /> : null}
        {waiting > 0 ? (
          <QueueIndicator classId={classId} />
        ) : (
          <Chip symbol={{ ios: 'checkmark.circle.fill', android: 'check_circle' }} label={t('mobile.sync.allSent')} color={colors.success} />
        )}
        {loading ? <ActivityIndicator color={colors.primary} /> : null}
      </View>
      {fromCache && age ? (
        <Text variant="caption" style={{ borderRadius: radii.md }}>
          {synthetic ? t('mobile.sync.classListUpdated', { age }) : t('mobile.sync.savedCopy', { age })}
        </Text>
      ) : null}
    </View>
  );
}

function Chip({ symbol, label, color }: { symbol: { ios: string; android: string }; label: string; color: string }) {
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <View
      accessible
      accessibilityLabel={label}
      style={{
        minHeight: minTouchTarget,
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[2],
        paddingHorizontal: spacing[4],
        borderRadius: radii.full,
        backgroundColor: colors.muted,
        borderWidth: 1,
        borderColor: color,
      }}>
      <SymbolView name={{ ...symbol, web: symbol.android } as SymbolViewProps['name']} size={18} tintColor={color} />
      <Text variant="label">{label}</Text>
    </View>
  );
}

function Notice({ tone, title, body }: { tone: 'warning' | 'info'; title: string; body: string }) {
  const { colors, radii, spacing } = useAppTheme();
  return (
    <View
      accessibilityRole="alert"
      style={{ gap: spacing[1], padding: spacing[4], borderRadius: radii.xl, borderWidth: 1, borderColor: tone === 'warning' ? colors.warning : colors.info, backgroundColor: colors.muted }}>
      <Text variant="label">{title}</Text>
      <Text>{body}</Text>
    </View>
  );
}

function Counts({ counts }: { counts: ReturnType<typeof countStatuses> }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const items: { status: AttendanceStatus | null; value: number }[] = [
    { status: 'PRESENT', value: counts.present },
    { status: 'ABSENT', value: counts.absent },
    { status: 'LATE', value: counts.late },
    { status: 'EXCUSED', value: counts.excused },
    { status: 'LEFT_EARLY', value: counts.leftEarly },
    { status: null, value: counts.unmarked },
  ];
  return (
    <View
      accessible
      accessibilityLabel={t('mobile.attendance.summary', {
        present: counts.present,
        absent: counts.absent,
        late: counts.late,
        excused: counts.excused,
        leftEarly: counts.leftEarly,
        unmarked: counts.unmarked,
      })}
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[3] }}>
      {items
        .filter((item) => item.value > 0 || item.status === 'PRESENT' || item.status === 'ABSENT')
        .map((item) => {
          const look = styleOf(item.status);
          return (
            <View key={item.status ?? 'none'} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[1] }}>
              <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={16} tintColor={look.accent(colors)} />
              <Text variant="caption">{`${t(look.labelKey)} ${item.value}`}</Text>
            </View>
          );
        })}
    </View>
  );
}
