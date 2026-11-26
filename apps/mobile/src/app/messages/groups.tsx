import { useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { goToConversation } from '@/components/messaging/conversation-row';
import { Chips } from '@/components/feed/chips';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { problemOf } from '@/lib/api-error';
import { GROUP_KIND_LABEL, GROUP_STATUS_LABEL } from '@/lib/messaging/labels';
import { INBOX_PREFIX, MESSAGING_ROOT } from '@/lib/messaging/keys';
import { useGroups } from '@/lib/messaging/queries';
import { approveGroup, rejectGroup } from '@/lib/messaging/requests';
import type { GroupAdmin } from '@/lib/messaging/types';
import { useMyClasses } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The groups a teacher moderates, proposed or approves (metadata only, never messages) and the way to create one. A student group
 * proposed by students waits here (`PENDING_APPROVAL`: nobody can use it) until a lead or co-teacher approves; the approving
 * teacher becomes its owner. Who may approve is the API's decision.
 */
export default function GroupsScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const params = useLocalSearchParams<{ schoolId?: string }>();
  const classes = useMyClasses();
  const schools = useMemo(() => {
    const found = new Map<string, string>();
    for (const cls of classes.data ?? []) if (cls.role === 'TEACHER') found.set(cls.schoolId, cls.schoolName ?? cls.name);
    return [...found.entries()];
  }, [classes.data]);
  const [schoolPick, setSchoolPick] = useState<string | null>(params.schoolId ?? null);
  const schoolId = schoolPick ?? schools[0]?.[0];
  const [filter, setFilter] = useState<'all' | 'pending'>(params.schoolId ? 'pending' : 'all');
  const groups = useGroups(schoolId, filter === 'pending' ? 'PENDING_APPROVAL' : null);
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function decide(group: GroupAdmin, approve: boolean) {
    if (busy) return;
    setBusy(group.id);
    setProblem(null);
    try {
      if (approve) await approveGroup(group.id);
      else await rejectGroup(group.id);
      await client.invalidateQueries({ queryKey: [MESSAGING_ROOT, 'groups'] });
      void client.invalidateQueries({ queryKey: INBOX_PREFIX });
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }}>
        <Button label={t('mobile.messaging.newGroup.title')} onPress={() => router.push('/messages/new-group')} />
        {schools.length > 1 ? <Chips accessibilityLabel={t('mobile.messaging.start.pickSchool')} value={schoolId ?? ''} onChange={setSchoolPick} chips={schools.map(([id, name]) => ({ value: id, label: name }))} /> : null}
        <Chips
          accessibilityLabel={t('mobile.messaging.groups.filter')}
          value={filter}
          onChange={setFilter}
          chips={[
            { value: 'all', label: t('mobile.messaging.groups.all') },
            { value: 'pending', label: t('mobile.messaging.groups.waiting') },
          ]}
        />
        {problem ? <Text tone="destructive" accessibilityLiveRegion="polite">{problem}</Text> : null}
        {groups.isPending ? <ActivityIndicator color={colors.primary} /> : null}
        {groups.isError ? <ProblemBanner error={groups.error} fallbackTitle={t('mobile.messaging.groups.loadFailedTitle')} fallbackBody={t('mobile.messaging.groups.loadFailedBody')} onRetry={() => void groups.refetch()} /> : null}
        {groups.data && groups.data.length === 0 ? <EmptyState icon={{ ios: 'person.3', android: 'groups' }} title={t('mobile.messaging.groups.emptyTitle')} body={t('mobile.messaging.groups.emptyBody')} /> : null}
        {(groups.data ?? []).map((group) => (
          <Card key={group.id}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`${group.title ?? t(GROUP_KIND_LABEL[group.groupKind])}. ${t(GROUP_STATUS_LABEL[group.status as keyof typeof GROUP_STATUS_LABEL] ?? GROUP_STATUS_LABEL.ACTIVE)}`}
              disabled={group.status === 'PENDING_APPROVAL' || group.status === 'REJECTED'}
              onPress={() => goToConversation(group.id)}
              style={{ gap: 2, minHeight: 44, justifyContent: 'center' }}>
              <Text variant="label">{group.title ?? t(GROUP_KIND_LABEL[group.groupKind])}</Text>
              <Text variant="caption">
                {t(GROUP_KIND_LABEL[group.groupKind])} · {t(GROUP_STATUS_LABEL[group.status as keyof typeof GROUP_STATUS_LABEL] ?? GROUP_STATUS_LABEL.ACTIVE)} · {t('mobile.messaging.groups.members', { count: group.memberCount })}
              </Text>
            </Pressable>
            {group.status === 'PENDING_APPROVAL' && !group.proposedByMe ? (
              <View style={{ flexDirection: 'row', gap: spacing[2] }}>
                <Button fullWidth={false} label={t('mobile.messaging.groups.approve')} loading={busy === group.id} onPress={() => void decide(group, true)} />
                <Button fullWidth={false} variant="destructive" label={t('mobile.messaging.groups.reject')} disabled={busy !== null} onPress={() => void decide(group, false)} />
              </View>
            ) : null}
            {group.status === 'PENDING_APPROVAL' && group.proposedByMe ? <Text variant="caption">{t('mobile.messaging.groups.proposedByMe')}</Text> : null}
          </Card>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}
