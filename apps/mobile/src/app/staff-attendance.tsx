import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { DateSwitcher } from '@/components/attendance/date-switcher';
import { StaffSheet } from '@/components/attendance/staff-sheet';
import { staffStatusStyle } from '@/components/attendance/staff-status';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Screen, SegmentedControl, Text } from '@/components/ui';
import { localDateIn } from '@/lib/attendance/dates';
import { staffAttendanceSchools, staffRefusal, type StaffRefusal } from '@/lib/attendance/staff/access';
import { canGoEarlierStaff, canGoLaterStaff, countStaff, dayRequest, isStaffDate, type StaffEntry } from '@/lib/attendance/staff/model';
import { useMarkStaffDay, useStaffDay, useStaffSchool } from '@/lib/attendance/staff/queries';
import { problemOf } from '@/lib/api-error';
import { useMe } from '@/lib/auth/use-me';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The manager's staff roll (M7d): the school's staff for a school day, marked PRESENT, ABSENT, LATE, HALF_DAY or EXCUSED,
 * with corrections that keep a history. Online only: nothing of it is queued, cached on the phone or stored. Who sees it
 * comes from `GET /me` (the two staff-attendance permissions); there is no teacher view, and the API refuses a colleague
 * anyway (403/404, shown here without further explanation).
 */
export default function StaffAttendanceScreen() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const params = useLocalSearchParams<{ schoolId?: string }>();
  const me = useMe();
  const schools = useMemo(() => staffAttendanceSchools(me.data), [me.data]);
  const [chosen, setChosen] = useState<string | null>(params.schoolId ?? null);
  const school = schools.find((candidate) => candidate.schoolId === chosen) ?? schools[0];

  if (me.isPending) {
    return (
      <Screen>
        <ActivityIndicator color={colors.primary} />
      </Screen>
    );
  }
  if (!school) {
    return (
      <Screen>
        <EmptyState icon={{ ios: 'lock.fill', android: 'lock' }} title={t('mobile.attendance.staff.noSchools.title')} body={t('mobile.attendance.staff.noSchools.body')} />
      </Screen>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      {schools.length > 1 ? (
        <View style={{ paddingHorizontal: spacing[5], paddingTop: spacing[3] }}>
          <SegmentedControl
            accessibilityLabel={t('mobile.attendance.staff.school')}
            options={schools.map((entry) => ({ value: entry.schoolId, label: entry.schoolName }))}
            value={school.schoolId}
            onChange={setChosen}
          />
        </View>
      ) : null}
      {/* Keyed by school: another school starts from its own day and sheet. */}
      <StaffRoll
        key={school.schoolId}
        schoolId={school.schoolId}
        schoolName={school.schoolName}
        membershipZone={me.data?.memberships.find((membership) => membership.schoolId === school.schoolId)?.timezone}
        canManage={school.canManage}
      />
    </View>
  );
}

function StaffRoll({ schoolId, schoolName, membershipZone, canManage }: { schoolId: string; schoolName: string; membershipZone: string | undefined; canManage: boolean }) {
  const t = useTranslations();
  const { spacing, colors, radii } = useAppTheme();
  const info = useStaffSchool(schoolId, membershipZone);
  const [now, setNow] = useState(() => Date.now());
  const [picked, setPicked] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);

  // The school's day, from the school's zone (never the phone's); the arrows never go past it.
  const timeZone = info.data?.timeZone ?? null;
  const today = timeZone ? localDateIn(timeZone, now) : null;
  const date = picked && today && isStaffDate(picked, today) ? picked : today;

  const day = useStaffDay(schoolId, date);
  const markRest = useMarkStaffDay(schoolId, date);
  const entries: StaffEntry[] = useMemo(() => day.data?.pages.flatMap((page) => page.staff.items) ?? [], [day.data]);
  const counts = useMemo(() => countStaff(entries), [entries]);
  const open = entries.find((entry) => entry.staffId === openId) ?? null;

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([info.refetch(), day.refetch()]);
    } finally {
      setRefreshing(false);
    }
  }

  function confirmMarkRest() {
    Alert.alert(t('mobile.attendance.staff.markRest.confirmTitle'), t('mobile.attendance.staff.markRest.confirmBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('mobile.attendance.staff.markRest.confirm'),
        onPress: () => {
          const body = dayRequest([], true);
          if (!body) return;
          markRest.mutate(body, {
            onSuccess: (result) => setMessage(t('mobile.attendance.staff.result.rest', { created: result.created ?? 0, existing: result.existing ?? 0 })),
          });
        },
      },
    ]);
  }

  const refusal: StaffRefusal | null = (() => {
    const error = day.error ?? info.error;
    if (!error) return null;
    const problem = problemOf(error);
    return staffRefusal(problem?.status ?? (error as { status?: number }).status, problem?.code);
  })();

  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[3] }}>
      {today && date ? (
        <DateSwitcher date={date} today={today} onDate={(next) => setPicked(next === today ? null : next)} window={{ canGoEarlier: canGoEarlierStaff, canGoLater: canGoLaterStaff }} />
      ) : null}

      {schoolName ? <Text variant="caption" style={{ textAlign: 'center' }}>{schoolName}</Text> : null}
      {!canManage ? <Text tone="muted">{t('mobile.attendance.staff.readOnly')}</Text> : null}
      {message ? (
        <View accessibilityRole="alert" accessibilityLiveRegion="polite" style={{ padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.success, backgroundColor: colors.muted }}>
          <Text>{message}</Text>
        </View>
      ) : null}

      {(info.isPending || (!!date && day.isPending)) && !refusal ? <ActivityIndicator color={colors.primary} /> : null}

      {refusal ? (
        <EmptyState
          icon={{ ios: refusal === 'disabled' || refusal === 'featureOff' ? 'person.badge.clock' : 'lock.fill', android: refusal === 'disabled' || refusal === 'featureOff' ? 'schedule' : 'lock' }}
          title={t(`mobile.attendance.staff.${refusal}.title`)}
          body={t(`mobile.attendance.staff.${refusal}.body`)}
        />
      ) : null}

      {!refusal && (day.isError || info.isError) ? (
        <ProblemBanner
          error={day.error ?? info.error}
          fallbackTitle={t('mobile.attendance.staff.errors.loadTitle')}
          fallbackBody={t('mobile.attendance.staff.errors.loadBody')}
          onRetry={() => void refresh()}
        />
      ) : null}
      {markRest.isError ? (
        <ProblemBanner error={markRest.error} fallbackTitle={t('mobile.attendance.staff.errors.saveTitle')} fallbackBody={t('mobile.attendance.staff.errors.saveBody')} />
      ) : null}

      {day.data && entries.length === 0 ? (
        <EmptyState icon={{ ios: 'person.3', android: 'groups' }} title={t('mobile.attendance.staff.empty.title')} body={t('mobile.attendance.staff.empty.body')} />
      ) : null}

      {entries.length > 0 ? (
        <>
          <Text
            variant="caption"
            accessibilityLabel={t('mobile.attendance.staff.summary', {
              present: counts.present,
              absent: counts.absent,
              late: counts.late,
              halfDay: counts.halfDay,
              excused: counts.excused,
              unmarked: counts.unmarked,
            })}>
            {t('mobile.attendance.staff.summary', {
              present: counts.present,
              absent: counts.absent,
              late: counts.late,
              halfDay: counts.halfDay,
              excused: counts.excused,
              unmarked: counts.unmarked,
            })}
          </Text>
          {canManage && (counts.unmarked > 0 || day.hasNextPage) ? (
            <Button variant="secondary" label={t('mobile.attendance.staff.markRest.button')} loading={markRest.isPending} onPress={confirmMarkRest} />
          ) : null}
          <View style={{ gap: spacing[2] }}>
            {entries.map((entry) => (
              <StaffRow key={entry.staffId} entry={entry} onPress={() => setOpenId(entry.staffId)} />
            ))}
          </View>
          {day.hasNextPage ? <Button variant="ghost" label={t('mobile.attendance.staff.loadMore')} loading={day.isFetchingNextPage} onPress={() => void day.fetchNextPage()} /> : null}
        </>
      ) : null}

      {open && date && timeZone ? (
        <StaffSheet
          entry={open}
          schoolId={schoolId}
          date={date}
          timeZone={timeZone}
          canManage={canManage}
          onClose={() => setOpenId(null)}
          onDone={(outcome) => setMessage(t(outcome === 'exists' ? 'mobile.attendance.staff.result.exists' : 'mobile.attendance.staff.result.saved'))}
        />
      ) : null}
    </Screen>
  );
}

function StaffRow({ entry, onPress }: { entry: StaffEntry; onPress: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const status = entry.record?.status ?? null;
  const look = staffStatusStyle(status);
  const detail = status === 'LATE' && entry.record?.minutesLate ? t('mobile.attendance.staff.minutesLate', { minutes: entry.record.minutesLate }) : null;
  const label = [entry.displayName, t(look.labelKey), detail].filter(Boolean).join(', ');

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={{
        minHeight: minTouchTarget + 8,
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[3],
        paddingHorizontal: spacing[4],
        paddingVertical: spacing[2],
        borderRadius: radii.xl,
        borderWidth: 1,
        borderColor: colors.border,
        backgroundColor: colors.card,
      }}>
      <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={26} tintColor={look.accent(colors)} />
      <View style={{ flex: 1 }}>
        <Text variant="label" numberOfLines={1}>
          {entry.displayName}
        </Text>
        {entry.jobTitle ? (
          <Text variant="caption" numberOfLines={1}>
            {entry.jobTitle}
          </Text>
        ) : null}
      </View>
      <View style={{ alignItems: 'flex-end' }}>
        <Text variant="label">{t(look.labelKey)}</Text>
        {detail ? <Text variant="caption">{detail}</Text> : null}
      </View>
    </Pressable>
  );
}
