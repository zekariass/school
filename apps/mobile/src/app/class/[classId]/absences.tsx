import { Stack, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { AbsenceReportCard } from '@/components/absence/report-card';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Screen, Sheet, Text, TextField } from '@/components/ui';
import { MAX_DECISION_NOTE_LENGTH, canDecide } from '@/lib/absence/form';
import { useClassAbsenceReports, useDecideAbsence, type AbsenceReport } from '@/lib/absence/queries';
import { useMyClass } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The class's absence reports from parents: waiting ones first to decide on, acknowledged ones as a record. An
 * acknowledgement shows on the roll as a hint ("parent reported illness"); it never marks anybody by itself.
 */
export default function ClassAbsences() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const reports = useClassAbsenceReports(cls?.schoolId, classId);
  const decide = useDecideAbsence(cls?.schoolId, classId);
  const [declining, setDeclining] = useState<AbsenceReport | null>(null);
  const [note, setNote] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const items = (reports.data?.pages.flatMap((page) => page.items) ?? []).slice().sort((a, b) => Number(canDecide(b.status)) - Number(canDecide(a.status)));
  // Assistants neither read the reason nor decide: the API says so, the buttons just do not offer it.
  const mayDecide = cls?.teacherRole !== 'ASSISTANT';

  async function run(report: AbsenceReport, decision: 'acknowledge' | 'decline', text?: string) {
    setBusyId(report.id);
    try {
      await decide.mutateAsync({ reportId: report.id, decision, ...(text ? { note: text } : {}) });
    } catch {
      // The mutation's error is shown below (the API's own localized words).
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={reports.isRefetching && !reports.isFetchingNextPage} onRefresh={() => void reports.refetch()} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[4] }}>
      <Stack.Screen options={{ title: t('mobile.absence.inbox.title') }} />
      <Text tone="muted">{t('mobile.absence.inbox.intro')}</Text>

      {decide.isError ? <ProblemBanner error={decide.error} fallbackTitle={t('mobile.absence.inbox.decisionFailedTitle')} fallbackBody={t('mobile.absence.inbox.decisionFailedBody')} /> : null}

      {reports.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : reports.isError ? (
        <ProblemBanner error={reports.error} fallbackTitle={t('mobile.absence.inbox.errorTitle')} fallbackBody={t('mobile.absence.inbox.errorBody')} onRetry={() => void reports.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState icon={{ ios: 'envelope.open', android: 'drafts' }} title={t('mobile.absence.inbox.emptyTitle')} body={t('mobile.absence.inbox.emptyBody')} />
      ) : (
        <View style={{ gap: spacing[3] }}>
          {items.map((report) => (
            <AbsenceReportCard
              key={report.id}
              report={report}
              showStudent
              actions={
                mayDecide && canDecide(report.status) ? (
                  <View style={{ flexDirection: 'row', gap: spacing[2] }}>
                    <View style={{ flex: 1 }}>
                      <Button label={t('mobile.absence.inbox.acknowledge')} loading={busyId === report.id} disabled={busyId !== null} onPress={() => void run(report, 'acknowledge')} />
                    </View>
                    <View style={{ flex: 1 }}>
                      <Button variant="ghost" label={t('mobile.absence.inbox.decline')} disabled={busyId !== null} onPress={() => { setNote(''); setDeclining(report); }} />
                    </View>
                  </View>
                ) : null
              }
            />
          ))}
          {reports.hasNextPage ? <Button variant="ghost" label={t('mobile.absence.loadMore')} loading={reports.isFetchingNextPage} onPress={() => void reports.fetchNextPage()} /> : null}
        </View>
      )}

      <Sheet visible={declining !== null} onClose={() => setDeclining(null)} title={t('mobile.absence.inbox.declineTitle', { name: declining?.studentName ?? '' })}>
        <Text tone="muted">{t('mobile.absence.inbox.declineBody')}</Text>
        <TextField label={t('mobile.absence.inbox.declineNote')} value={note} onChangeText={setNote} maxLength={MAX_DECISION_NOTE_LENGTH} multiline />
        <Button
          label={t('mobile.absence.inbox.decline')}
          onPress={() => {
            const report = declining;
            setDeclining(null);
            if (report) void run(report, 'decline', note.trim());
          }}
        />
        <Button variant="ghost" label={t('common.cancel')} onPress={() => setDeclining(null)} />
      </Sheet>
    </Screen>
  );
}
