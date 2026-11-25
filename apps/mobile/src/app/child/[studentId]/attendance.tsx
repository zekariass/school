import { Stack, router, useLocalSearchParams } from 'expo-router';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useState } from 'react';
import { ActivityIndicator, Alert, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { AbsenceReportCard } from '@/components/absence/report-card';
import { AbsenceReportSheet } from '@/components/absence/report-form';
import { styleOf } from '@/components/attendance/status-style';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { canCancel } from '@/lib/absence/form';
import { useAttendanceHistory, useCancelAbsence, useMyAbsenceReports, type AttendanceHistoryItem } from '@/lib/absence/queries';
import { isAttendanceStatus } from '@/lib/sync/types';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { useMyChildren } from '@/lib/family/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * A parent's attendance page for one child: tell the school about an absence ahead of time, see and withdraw the
 * reports filed, and read the attendance history (status, lateness, date and class: never a teacher's note).
 */
export default function ChildAttendance() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { studentId } = useLocalSearchParams<{ studentId: string }>();
  const children = useMyChildren();
  const [refreshing, setRefreshing] = useState(false);
  const [reportFor, setReportFor] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const child = children.data?.children.find((candidate) => candidate.studentId === studentId);

  if (!child) {
    return (
      <Screen edges={['bottom']}>
        <Stack.Screen options={{ title: t('mobile.attendance.guardian.title') }} />
        {children.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : children.isError ? (
          <ProblemBanner error={children.error} fallbackTitle={t('mobile.points.child.errorTitle')} fallbackBody={t('mobile.points.child.errorBody')} onRetry={() => void children.refetch()} />
        ) : (
          <EmptyState icon={{ ios: 'person.crop.circle.badge.questionmark', android: 'help' }} title={t('mobile.points.child.notFoundTitle')} body={t('mobile.points.child.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => router.back()} />
          </EmptyState>
        )}
      </Screen>
    );
  }

  const firstName = child.givenName;
  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); void children.refetch().finally(() => setRefreshing(false)); }} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[5] }}>
      <Stack.Screen options={{ title: t('mobile.attendance.guardian.titleFor', { name: firstName }) }} />
      {sent ? (
        <Card accessibilityRole="alert" accessibilityLiveRegion="polite">
          <Text variant="label" tone="primary">
            {t('mobile.absence.sent', { name: firstName })}
          </Text>
        </Card>
      ) : null}

      {child.schools.map((school) => (
        <View key={school.schoolId} style={{ gap: spacing[4] }}>
          {child.schools.length > 1 ? <Text variant="heading">{school.schoolName}</Text> : null}
          <Button label={t('mobile.absence.report', { name: firstName })} onPress={() => { setSent(false); setReportFor(school.schoolId); }} />
          <MyReports schoolId={school.schoolId} studentId={child.studentId} />
          {child.permissions.canViewAcademics ? <History schoolId={school.schoolId} studentId={child.studentId} /> : <Card><Text>{t('mobile.attendance.guardian.noAccess')}</Text></Card>}
        </View>
      ))}

      {reportFor ? (
        <AbsenceReportSheet visible schoolId={reportFor} studentId={child.studentId} childName={firstName} onClose={() => setReportFor(null)} onSent={() => setSent(true)} />
      ) : null}
    </Screen>
  );
}

function MyReports({ schoolId, studentId }: { schoolId: string; studentId: string }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const reports = useMyAbsenceReports(schoolId, studentId);
  const cancel = useCancelAbsence(schoolId, studentId);
  const items = reports.data?.pages.flatMap((page) => page.items) ?? [];

  function confirmCancel(id: string) {
    Alert.alert(t('mobile.absence.cancel.title'), t('mobile.absence.cancel.body'), [
      { text: t('mobile.absence.cancel.keep'), style: 'cancel' },
      { text: t('mobile.absence.cancel.confirm'), style: 'destructive', onPress: () => cancel.mutate(id) },
    ]);
  }

  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.absence.mine.title')}</Text>
      {cancel.isError ? <ProblemBanner error={cancel.error} fallbackTitle={t('mobile.absence.cancel.failedTitle')} fallbackBody={t('mobile.absence.cancel.failedBody')} /> : null}
      {reports.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : reports.isError ? (
        <ProblemBanner error={reports.error} fallbackTitle={t('mobile.absence.mine.errorTitle')} fallbackBody={t('mobile.absence.mine.errorBody')} onRetry={() => void reports.refetch()} />
      ) : items.length === 0 ? (
        <Text tone="muted">{t('mobile.absence.mine.empty')}</Text>
      ) : (
        items.map((report) => (
          <AbsenceReportCard
            key={report.id}
            report={report}
            actions={canCancel(report.status) ? <Button variant="destructive" label={t('mobile.absence.cancel.action')} loading={cancel.isPending && cancel.variables === report.id} onPress={() => confirmCancel(report.id)} /> : null}
          />
        ))
      )}
      {reports.hasNextPage ? <Button variant="ghost" label={t('mobile.absence.loadMore')} loading={reports.isFetchingNextPage} onPress={() => void reports.fetchNextPage()} /> : null}
    </View>
  );
}

function History({ schoolId, studentId }: { schoolId: string; studentId: string }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const history = useAttendanceHistory(schoolId, studentId);
  const items = history.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.attendance.guardian.history')}</Text>
      {history.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : history.isError ? (
        <ProblemBanner error={history.error} fallbackTitle={t('mobile.attendance.guardian.historyErrorTitle')} fallbackBody={t('mobile.attendance.guardian.historyErrorBody')} onRetry={() => void history.refetch()} />
      ) : items.length === 0 ? (
        <Text tone="muted">{t('mobile.attendance.guardian.historyEmpty')}</Text>
      ) : (
        <Card style={{ gap: 0, padding: spacing[2] }}>
          {items.map((item) => (
            <HistoryRow key={item.recordId} item={item} />
          ))}
        </Card>
      )}
      {history.hasNextPage ? <Button variant="ghost" label={t('mobile.absence.loadMore')} loading={history.isFetchingNextPage} onPress={() => void history.fetchNextPage()} /> : null}
    </View>
  );
}

function HistoryRow({ item }: { item: AttendanceHistoryItem }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const format = useFormatCivilDate();
  const look = styleOf(isAttendanceStatus(item.status) ? item.status : null);
  const status = t(look.labelKey);
  const detail = item.status === 'LATE' && item.minutesLate ? `${status} · ${t('mobile.attendance.hint.minutesLate', { minutes: item.minutesLate })}` : status;
  return (
    <View accessible accessibilityLabel={`${format(item.date)}, ${item.className}, ${detail}`} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[3] }}>
      <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={26} tintColor={look.accent(colors)} />
      <View style={{ flex: 1 }}>
        <Text variant="label">{format(item.date)}</Text>
        <Text variant="caption" numberOfLines={1}>
          {item.className}
        </Text>
      </View>
      <Text style={{ color: look.accent(colors) }}>{detail}</Text>
    </View>
  );
}
