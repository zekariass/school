import { useQueryClient } from '@tanstack/react-query';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ClassAnnouncementButton } from '@/components/announcements/school-entry';
import { ClassBadge } from '@/components/class-card';
import { ProblemBanner } from '@/components/problem-banner';
import { RosterGrid } from '@/components/roster-grid';
import { OfflineNote } from '@/components/sync/offline-note';
import { QueueIndicator } from '@/components/sync/queue-indicator';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { canEditRoster, teacherNames } from '@/lib/people/classes';
import { useClassDetail, useClassTeachers, useMyClass, useRoster } from '@/lib/people/queries';
import { useAttentionOperations } from '@/lib/sync/runtime';
import { isAttendanceOp } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** A class: its details, the roster as an avatar wall and the roster actions. */
export default function ClassDetail() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls, query: mine } = useMyClass(classId);
  const schoolId = cls?.schoolId;
  const detail = useClassDetail(schoolId, classId);
  const teachers = useClassTeachers(schoolId, classId);
  const roster = useRoster(schoolId, classId);
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const attentionCount = useAttentionOperations().filter((op) => isAttendanceOp(op) && op.classId === classId).length;

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([
        mine.refetch(),
        detail.refetch(),
        teachers.refetch(),
        roster.refetch(),
        queryClient.invalidateQueries({ queryKey: ['student'] }),
      ]);
    } finally {
      setRefreshing(false);
    }
  }

  const editable = canEditRoster(cls);
  const students = roster.data ?? [];
  const info = detail.data;
  const grade = info?.gradeLevelName ?? cls?.gradeLevelName;
  const year = info?.academicYearName;
  const subtitle = [grade, year].filter(Boolean).join(' · ');
  const others = teachers.data ? teacherNames(teachers.data) : '';

  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[5] }}>
      <Stack.Screen options={{ title: cls?.name ?? t('mobile.classes.classTitle') }} />

      {!cls && mine.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : !cls ? (
        <EmptyState
          icon={{ ios: 'questionmark.circle', android: 'help' }}
          title={t('mobile.classes.notFoundTitle')}
          body={t('mobile.classes.notFoundBody')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      ) : (
        <>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[4] }}>
            <ClassBadge cls={cls} size={72} />
            <View style={{ flex: 1, gap: 2 }}>
              <Text variant="title" numberOfLines={2}>
                {cls.name}
              </Text>
              {subtitle ? <Text tone="muted">{subtitle}</Text> : null}
              <Text tone="muted" variant="caption">
                {t('mobile.classes.studentCount', { count: roster.data?.length ?? cls.studentCount })}
              </Text>
              {others ? (
                <Text tone="muted" variant="caption" numberOfLines={2}>
                  {t('mobile.classes.teachersLine', { names: others })}
                </Text>
              ) : null}
            </View>
          </View>

          {cls.status === 'ARCHIVED' ? (
            <Card>
              <Text>{t('mobile.classes.archivedNote')}</Text>
            </Card>
          ) : null}

          {cls.role === 'TEACHER' && cls.status === 'ACTIVE' ? (
            <View style={{ gap: spacing[3] }}>
              <Button
                label={t('mobile.attendance.open')}
                onPress={() => router.push({ pathname: '/class/[classId]/attendance', params: { classId: cls.id } })}
              />
              <Button
                variant="secondary"
                label={t('mobile.feed.openClassFeed')}
                onPress={() => router.push({ pathname: '/feed/class/[classId]', params: { classId: cls.id, schoolId: cls.schoolId } })}
              />
              <ClassAnnouncementButton schoolId={cls.schoolId} classId={cls.id} />
              <Button
                variant="secondary"
                label={t('mobile.points.openClass')}
                onPress={() => router.push({ pathname: '/class/[classId]/points', params: { classId: cls.id } })}
              />
              <Button
                variant="ghost"
                label={t('mobile.absence.inbox.open')}
                onPress={() => router.push({ pathname: '/class/[classId]/absences', params: { classId: cls.id } })}
              />
              <Button
                variant="ghost"
                label={t('mobile.toolkit.open')}
                onPress={() => router.push({ pathname: '/class/[classId]/toolkit', params: { classId: cls.id } })}
              />
              <QueueIndicator classId={cls.id} />
              {attentionCount > 0 ? (
                <Button
                  variant="destructive"
                  label={t('mobile.attendance.issues.banner', { count: attentionCount })}
                  onPress={() => router.push({ pathname: '/class/[classId]/attendance-issues', params: { classId: cls.id } })}
                />
              ) : null}
            </View>
          ) : null}

          {editable ? (
            <View style={{ gap: spacing[3] }}>
              <Button
                variant="ghost"
                label={t('mobile.classes.addStudents')}
                onPress={() => router.push({ pathname: '/class/[classId]/add-students', params: { classId: cls.id } })}
              />
              <Button
                variant="ghost"
                label={t('mobile.classes.newStudent.action')}
                onPress={() => router.push({ pathname: '/class/[classId]/new-student', params: { classId: cls.id } })}
              />
            </View>
          ) : null}

          <View style={{ gap: spacing[3] }}>
            <Text variant="heading">{t('mobile.classes.rosterTitle')}</Text>
            <OfflineNote fromCache={roster.fromCache} fetchedAt={roster.fetchedAt} />
            {roster.isPending ? (
              <ActivityIndicator color={colors.primary} />
            ) : roster.isError && !roster.data ? (
              <ProblemBanner
                error={roster.error}
                fallbackTitle={t('mobile.classes.rosterErrorTitle')}
                fallbackBody={t('mobile.classes.rosterErrorBody')}
                onRetry={() => void roster.refetch()}
              />
            ) : students.length === 0 ? (
              <Card>
                <Text variant="heading">{t('mobile.classes.rosterEmptyTitle')}</Text>
                <Text tone="muted">{t('mobile.classes.rosterEmptyBody')}</Text>
              </Card>
            ) : (
              <RosterGrid
                students={students}
                onSelect={(student) =>
                  router.push({
                    pathname: '/class/[classId]/student/[studentId]',
                    params: { classId: cls.id, studentId: student.id },
                  })
                }
              />
            )}
          </View>

          <Card>
            <Text variant="caption">{t('mobile.classes.printHint')}</Text>
          </Card>
        </>
      )}
    </Screen>
  );
}
