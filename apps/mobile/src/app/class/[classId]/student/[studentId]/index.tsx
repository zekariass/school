import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Alert, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { ReportSheet } from '@/components/moderation/report-sheet';
import { CaptureForStudentButton } from '@/components/portfolio/class-actions';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { api } from '@/lib/api';
import { ApiRequestError, assertOk, request } from '@/lib/api-error';
import { useMe } from '@/lib/auth/use-me';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { canReportPerson } from '@/lib/moderation/visibility';
import { canEditRoster, moveTargets } from '@/lib/people/classes';
import { invalidateRoster, useMyClass, useMyClasses, useRoster, useStudent } from '@/lib/people/queries';
import { canSeePrivateFields } from '@/lib/people/roster';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** A student of the class: avatar and name, private details for the student's teachers, and actions. */
export default function StudentProfile() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { classId, studentId } = useLocalSearchParams<{ classId: string; studentId: string }>();
  const { cls } = useMyClass(classId);
  const mine = useMyClasses();
  const roster = useRoster(cls?.schoolId, classId);
  const entry = roster.data?.find((student) => student.id === studentId);
  const full = canSeePrivateFields(entry?.accessLevel);
  const student = useStudent(cls?.schoolId, studentId, full);
  const formatDate = useFormatCivilDate();
  const queryClient = useQueryClient();
  const viewerId = useMe().data?.id;
  const [reporting, setReporting] = useState(false);

  const editable = canEditRoster(cls);
  const targets = cls ? moveTargets(mine.data ?? [], cls) : [];

  const remove = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: () =>
      request(async () =>
        assertOk(
          await api.DELETE('/api/v1/schools/{schoolId}/classes/{classId}/students/{studentId}', {
            params: { path: { schoolId: cls!.schoolId, classId: classId!, studentId: studentId! } },
          }),
        ),
      ),
    onSuccess: async () => {
      await invalidateRoster(queryClient, classId!);
      router.back();
    },
  });

  function confirmRemove() {
    if (!entry || !cls) return;
    Alert.alert(
      t('mobile.classes.remove.title', { name: entry.firstName }),
      t('mobile.classes.remove.body', { name: entry.firstName, class: cls.name }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        { text: t('mobile.classes.remove.confirm'), style: 'destructive', onPress: () => remove.mutate() },
      ],
    );
  }

  if (!entry || !cls) {
    return (
      <Screen edges={['bottom']}>
        {roster.isPending ? null : (
          <EmptyState
            icon={{ ios: 'person.crop.circle.badge.questionmark', android: 'help' }}
            title={t('mobile.classes.student.notFoundTitle')}
            body={t('mobile.classes.student.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => router.back()} />
          </EmptyState>
        )}
      </Screen>
    );
  }

  const details = student.data;
  const rows: { label: string; value: string }[] = [];
  if (full && details) {
    if (details.dateOfBirth) rows.push({ label: t('mobile.classes.student.dateOfBirth'), value: formatDate(details.dateOfBirth) });
    if (details.gender) rows.push({ label: t('mobile.classes.student.gender'), value: details.gender });
    if (details.username) rows.push({ label: t('mobile.classes.student.username'), value: details.username });
    if (details.studentNumber) rows.push({ label: t('mobile.classes.student.number'), value: details.studentNumber });
    if (details.classes?.length) {
      rows.push({ label: t('mobile.classes.student.classes'), value: details.classes.map((item) => item.name).join(', ') });
    }
  }

  const forbidden = remove.error instanceof ApiRequestError && remove.error.status === 403;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Stack.Screen options={{ title: entry.firstName }} />
      <View style={{ alignItems: 'center', gap: spacing[3] }}>
        <Avatar
          mediaId={entry.avatarMediaId}
          name={entry.name}
          seed={entry.id}
          config={entry.avatarConfig}
          size={140}
          maxInitials={2}
        />
        <Text variant="title" style={{ textAlign: 'center' }}>
          {entry.name}
        </Text>
        <Text tone="muted">{cls.name}</Text>
      </View>

      {rows.length > 0 ? (
        <Card>
          {rows.map((row) => (
            <View
              key={row.label}
              accessible
              accessibilityLabel={`${row.label}: ${row.value}`}
              style={{ flexDirection: 'row', justifyContent: 'space-between', gap: spacing[3] }}>
              <Text tone="muted">{row.label}</Text>
              <Text variant="label" selectable style={{ flexShrink: 1, textAlign: 'right' }}>
                {row.value}
              </Text>
            </View>
          ))}
          <Text variant="caption">{t('mobile.classes.student.privateNote')}</Text>
        </Card>
      ) : null}

      {remove.isError ? (
        <ProblemBanner
          error={remove.error}
          fallbackTitle={t(forbidden ? 'mobile.classes.forbiddenTitle' : 'status.errorTitle')}
          fallbackBody={t(forbidden ? 'mobile.classes.forbiddenBody' : 'mobile.classes.actionFailed')}
        />
      ) : null}

      <CaptureForStudentButton schoolId={cls.schoolId} classId={cls.id} studentId={entry.id} />

      {canReportPerson(entry.id, viewerId) ? (
        <Button variant="ghost" label={t('mobile.moderation.personAction')} onPress={() => setReporting(true)} />
      ) : null}

      {editable ? (
        <View style={{ gap: spacing[3] }}>
          <Button
            label={t('mobile.classes.student.showCode')}
            onPress={() =>
              router.push({
                pathname: '/class/[classId]/student/[studentId]/code',
                params: { classId: cls.id, studentId: entry.id },
              })
            }
          />
          {targets.length > 0 ? (
            <Button
              variant="secondary"
              label={t('mobile.classes.student.move')}
              onPress={() =>
                router.push({
                  pathname: '/class/[classId]/student/[studentId]/move',
                  params: { classId: cls.id, studentId: entry.id },
                })
              }
            />
          ) : null}
          <Button variant="destructive" label={t('mobile.classes.student.remove')} loading={remove.isPending} onPress={confirmRemove} />
        </View>
      ) : null}
      <ReportSheet target={reporting ? { type: 'USER', id: entry.id, schoolId: cls.schoolId } : null} onClose={() => setReporting(false)} />
    </Screen>
  );
}
