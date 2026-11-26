import { useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Alert, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { useParticipantText } from '@/components/messaging/use-participant-text';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, Sheet, Text, TextField } from '@/components/ui';
import { formatInstant } from '@/lib/calendar/format-instant';
import { problemOf } from '@/lib/api-error';
import { ACTING_AS_LABEL, MUTE_CHOICES, ROLE_LABEL } from '@/lib/messaging/labels';
import { INBOX_PREFIX, conversationKey, membersKey } from '@/lib/messaging/keys';
import { useConversation, useMembers } from '@/lib/messaging/queries';
import { leaveConversation, muteConversation, removeMember, unmuteConversation, updateConversation } from '@/lib/messaging/requests';
import { forgetConversation } from '@/lib/messaging/runtime';
import type { Participant } from '@/lib/messaging/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Who is in the conversation (teachers, staff and students by name, guardians only as role labels), what the person may do with
 * it (mute, leave) and, for moderators (`canModerate`), the controls of a group: lock or archive it, limit posting to moderators,
 * remove a member with a reason. The roster of a class conversation follows the class list (the API says so when it refuses).
 */
export default function PeopleScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { conversationId } = useLocalSearchParams<{ conversationId: string }>();
  const conversation = useConversation(conversationId);
  const members = useMembers(conversationId);

  if (conversation.isPending || members.isPending) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  if (conversation.isError || members.isError || !conversation.data || !members.data) {
    return (
      <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5] }}>
        <ProblemBanner error={conversation.error ?? members.error} fallbackTitle={t('mobile.messaging.conversation.loadFailedTitle')} fallbackBody={t('mobile.messaging.conversation.loadFailedBody')} onRetry={() => { void conversation.refetch(); void members.refetch(); }} />
      </SafeAreaView>
    );
  }
  return <People conversationId={conversationId!} detail={conversation.data} list={members.data} />;
}

function People({ conversationId, detail, list }: { conversationId: string; detail: NonNullable<ReturnType<typeof useConversation>['data']>; list: NonNullable<ReturnType<typeof useMembers>['data']> }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const client = useQueryClient();
  const personText = useParticipantText();
  const summary = detail.summary;
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [muteMenu, setMuteMenu] = useState(false);
  const [removing, setRemoving] = useState<Participant | null>(null);
  const [reason, setReason] = useState('');
  const group = summary.type !== 'DIRECT';
  const moderated = detail.canModerate && group;

  async function run(work: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await work();
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(false);
    }
  }
  const refresh = () => {
    void client.invalidateQueries({ queryKey: conversationKey(conversationId) });
    void client.invalidateQueries({ queryKey: membersKey(conversationId) });
    void client.invalidateQueries({ queryKey: INBOX_PREFIX });
  };

  const roleLine = (person: Participant) => [t(ACTING_AS_LABEL[person.actingAs]), person.role !== 'MEMBER' ? t(ROLE_LABEL[person.role]) : null].filter(Boolean).join(' · ');

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }}>
        {problem ? (
          <Text tone="destructive" accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}

        <Card>
          <Text variant="heading">{t('mobile.messaging.people.members')}</Text>
          {list.members.map((person) => (
            <View key={person.memberId} accessible accessibilityLabel={`${personText(person, summary.myActingAs)}. ${roleLine(person)}`} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], minHeight: 48 }}>
              <View style={{ flex: 1 }}>
                <Text variant="label">{personText(person, summary.myActingAs)}</Text>
                <Text variant="caption">{roleLine(person)}</Text>
              </View>
              {moderated && !person.self && person.role !== 'OWNER' && person.role !== 'OBSERVER' ? (
                <Button fullWidth={false} variant="destructive" label={t('mobile.messaging.people.remove')} onPress={() => { setReason(''); setRemoving(person); }} />
              ) : null}
            </View>
          ))}
          {list.observerCount > 0 ? <Text variant="caption">{t('mobile.messaging.people.observers', { count: list.observerCount })}</Text> : null}
        </Card>

        <Card>
          <Text variant="heading">{t('mobile.messaging.people.yourSettings')}</Text>
          {summary.muted && summary.mutedUntil ? (
            <Text variant="caption">{t('mobile.messaging.mute.until', { when: formatInstant(summary.mutedUntil, { locale, calendar }) })}</Text>
          ) : null}
          {summary.muted ? (
            <Button variant="ghost" label={t('mobile.messaging.mute.unmute')} loading={busy} onPress={() => void run(async () => { await unmuteConversation(conversationId); refresh(); })} />
          ) : (
            <Button variant="ghost" label={t('mobile.messaging.mute.action')} onPress={() => setMuteMenu(true)} />
          )}
          {group && summary.myRole !== 'OBSERVER' ? (
            <Button
              variant="destructive"
              label={t('mobile.messaging.people.leave')}
              onPress={() =>
                Alert.alert(t('mobile.messaging.people.leaveTitle'), t('mobile.messaging.people.leaveBody'), [
                  { text: t('common.cancel'), style: 'cancel' },
                  {
                    text: t('mobile.messaging.people.leave'),
                    style: 'destructive',
                    onPress: () =>
                      void run(async () => {
                        await leaveConversation(conversationId);
                        forgetConversation(client, conversationId);
                        router.dismissTo('/');
                      }),
                  },
                ])
              }
            />
          ) : null}
        </Card>

        {moderated ? (
          <Card>
            <Text variant="heading">{t('mobile.messaging.people.manage')}</Text>
            <Text variant="caption">{t('mobile.messaging.people.manageHint')}</Text>
            <Button
              variant="ghost"
              label={summary.postingPolicy === 'MODERATORS_ONLY' ? t('mobile.messaging.people.allowEveryone') : t('mobile.messaging.people.moderatorsOnly')}
              loading={busy}
              onPress={() => void run(async () => { await updateConversation(conversationId, { postingPolicy: summary.postingPolicy === 'MODERATORS_ONLY' ? 'ALL_MEMBERS' : 'MODERATORS_ONLY' }); refresh(); })}
            />
            {summary.status === 'ACTIVE' ? (
              <>
                <Button variant="ghost" label={t('mobile.messaging.people.lock')} loading={busy} onPress={() => void run(async () => { await updateConversation(conversationId, { status: 'LOCKED' }); refresh(); })} />
                <Button variant="destructive" label={t('mobile.messaging.people.archive')} loading={busy} onPress={() => void run(async () => { await updateConversation(conversationId, { status: 'ARCHIVED' }); refresh(); })} />
              </>
            ) : (
              <Button variant="ghost" label={t('mobile.messaging.people.reopen')} loading={busy} onPress={() => void run(async () => { await updateConversation(conversationId, { status: 'ACTIVE' }); refresh(); })} />
            )}
          </Card>
        ) : null}
      </ScrollView>

      <Sheet visible={muteMenu} onClose={() => setMuteMenu(false)} title={t('mobile.messaging.mute.action')}>
        <Text tone="muted">{t('mobile.messaging.mute.hint')}</Text>
        {MUTE_CHOICES.map((choice) => (
          <Button
            key={choice.hours}
            variant="ghost"
            label={t(choice.key)}
            onPress={() => {
              setMuteMenu(false);
              void run(async () => { await muteConversation(conversationId, new Date(Date.now() + choice.hours * 3_600_000).toISOString()); refresh(); });
            }}
          />
        ))}
      </Sheet>

      <Sheet visible={removing !== null} onClose={() => setRemoving(null)} title={t('mobile.messaging.people.removeTitle')}>
        <Text tone="muted">{t('mobile.messaging.people.removeBody')}</Text>
        <TextField label={t('mobile.messaging.moderation.reasonLabel')} value={reason} onChangeText={setReason} multiline maxLength={300} />
        <Button
          variant="destructive"
          label={t('mobile.messaging.people.remove')}
          loading={busy}
          disabled={reason.trim().length === 0}
          onPress={() =>
            void run(async () => {
              if (!removing) return;
              await removeMember(conversationId, removing.memberId, reason.trim());
              setRemoving(null);
              refresh();
            })
          }
        />
      </Sheet>
    </SafeAreaView>
  );
}
