import { useMutation, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ClassCard } from '@/components/class-card';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Screen, Text } from '@/components/ui';
import { api } from '@/lib/api';
import { ApiRequestError, assertOk, request } from '@/lib/api-error';
import { moveTargets } from '@/lib/people/classes';
import { invalidateRoster, useMyClass, useMyClasses, useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** Pick another class of the teacher's (same school) to move the student to. */
export default function MoveStudent() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { classId, studentId } = useLocalSearchParams<{ classId: string; studentId: string }>();
  const { cls } = useMyClass(classId);
  const mine = useMyClasses();
  const roster = useRoster(cls?.schoolId, classId);
  const entry = roster.data?.find((student) => student.id === studentId);
  const queryClient = useQueryClient();
  const targets = cls ? moveTargets(mine.data ?? [], cls) : [];

  const move = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (toClassId: string) =>
      request(async () =>
        assertOk(
          await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/students/{studentId}/move', {
            params: { path: { schoolId: cls!.schoolId, classId: classId!, studentId: studentId! } },
            body: { toClassId },
          }),
        ),
      ),
    onSuccess: async (_, toClassId) => {
      await Promise.all([invalidateRoster(queryClient, classId!), invalidateRoster(queryClient, toClassId)]);
      router.dismissTo({ pathname: '/class/[classId]', params: { classId: classId! } });
    },
  });

  function choose(targetId: string, targetName: string) {
    if (!entry) return;
    Alert.alert(
      t('mobile.classes.move.confirmTitle', { name: entry.firstName, class: targetName }),
      t('mobile.classes.move.confirmBody', { name: entry.firstName, from: cls?.name ?? '', to: targetName }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        { text: t('mobile.classes.move.confirm'), onPress: () => move.mutate(targetId) },
      ],
    );
  }

  const forbidden = move.error instanceof ApiRequestError && move.error.status === 403;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[4] }}>
      <Text tone="muted">{t('mobile.classes.move.intro', { name: entry?.firstName ?? '' })}</Text>
      {move.isError ? (
        <ProblemBanner
          error={move.error}
          fallbackTitle={t(forbidden ? 'mobile.classes.forbiddenTitle' : 'status.errorTitle')}
          fallbackBody={t(forbidden ? 'mobile.classes.forbiddenBody' : 'mobile.classes.actionFailed')}
        />
      ) : null}
      {targets.length === 0 ? (
        <EmptyState
          icon={{ ios: 'person.3', android: 'groups' }}
          title={t('mobile.classes.move.emptyTitle')}
          body={t('mobile.classes.move.emptyBody')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      ) : (
        <View style={{ gap: spacing[3] }}>
          {targets.map((target) => (
            <ClassCard
              key={target.id}
              cls={target}
              lines={[target.gradeLevelName, t('mobile.classes.studentCount', { count: target.studentCount })]}
              accessibilityLabel={target.name}
              onPress={move.isPending ? undefined : () => choose(target.id, target.name)}
            />
          ))}
        </View>
      )}
    </Screen>
  );
}
