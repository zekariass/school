import { useQuery, useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useFocusEffect, useLocalSearchParams, usePreventRemove } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, Alert, AppState, FlatList, KeyboardAvoidingView, Platform, Pressable, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Banner } from '@/components/messaging/banner';
import { Composer } from '@/components/messaging/composer';
import { MessageBubble, PendingMessage } from '@/components/messaging/message-bubble';
import { MessageOptions } from '@/components/messaging/message-options';
import { useParticipantText } from '@/components/messaging/use-participant-text';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text } from '@/components/ui';
import { formatInstant } from '@/lib/calendar/format-instant';
import { useClock } from '@/lib/feed/use-clock';
import { messageActions } from '@/lib/messaging/actions';
import { ApiRequestError } from '@/lib/api-error';
import { accessOf, editRemainingMs, incomingFromOthers, inboxTitle, isObserving, lastSeenMessageId, messageText, safeguardingNotice } from '@/lib/messaging/derive';
import { GROUP_KIND_LABEL } from '@/lib/messaging/labels';
import { afterHoursKey, conversationKey, inboxKey, messagesKey } from '@/lib/messaging/keys';
import { openConversation } from '@/lib/messaging/live-status';
import { upsertMessage, type MessageData } from '@/lib/messaging/pages';
import { useConversation, useConversationPolling, useMessages, useMessagingCapabilities } from '@/lib/messaging/queries';
import { blockMember, deleteMessage, deleteReaction, editMessage, fetchMessage, hideMessage, putReaction, reportMessage, unhideMessage } from '@/lib/messaging/requests';
import { markConversationRead, outbox, refreshLatest, useOutboxEntries } from '@/lib/messaging/runtime';
import { buildTimeline, type TimelineItem } from '@/lib/messaging/timeline';
import type { ConversationDetail, MessageItem } from '@/lib/messaging/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * One conversation. The messages, the names and the images are in memory only. What the screen offers comes from the API's flags
 * (`canPost`, `myRole`, `canModerate`, `canEdit`, `canDelete`, `guardiansCanSee`); a guardian who only observes sees a read-only
 * conversation with no composer; every action is checked again by the server.
 */
export default function ConversationScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { conversationId } = useLocalSearchParams<{ conversationId: string }>();
  const conversation = useConversation(conversationId);

  if (conversation.isPending) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  if (conversation.isError || !conversation.data) {
    const gone = conversation.error instanceof ApiRequestError && (conversation.error.status === 404 || conversation.error.status === 403);
    return (
      <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        {gone ? (
          <EmptyState icon={{ ios: 'bubble.left.and.exclamationmark.bubble.right', android: 'speaker_notes_off' }} title={t('mobile.messaging.conversation.notFoundTitle')} body={t('mobile.messaging.conversation.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
          </EmptyState>
        ) : (
          <ProblemBanner error={conversation.error} fallbackTitle={t('mobile.messaging.conversation.loadFailedTitle')} fallbackBody={t('mobile.messaging.conversation.loadFailedBody')} onRetry={() => void conversation.refetch()} />
        )}
      </SafeAreaView>
    );
  }
  return <Conversation detail={conversation.data} conversationId={conversationId!} />;
}

function Conversation({ detail, conversationId }: { detail: ConversationDetail; conversationId: string }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const client = useQueryClient();
  const personText = useParticipantText();
  const now = useClock(30_000);
  const summary = detail.summary;
  const viewer = summary.myActingAs;
  const caps = useMessagingCapabilities(summary.schoolId).data;
  const { query, messages } = useMessages(conversationId);
  useConversationPolling(conversationId);

  const access = accessOf(summary);
  const notice = safeguardingNotice(summary);
  const observing = isObserving(summary);
  const entries = useOutboxEntries();
  const pending = useMemo(() => entries.filter((entry) => entry.conversationId === conversationId), [entries, conversationId]);
  const timeline = useMemo(() => buildTimeline(messages, pending), [messages, pending]);
  const seenId = useMemo(() => lastSeenMessageId(messages), [messages]);

  const [reply, setReply] = useState<{ id: string; author: string } | null>(null);
  const [options, setOptions] = useState<MessageItem | null>(null);
  const [scheduledFor, setScheduledFor] = useState<string | null>(null);
  const afterHours = useQuery({ queryKey: afterHoursKey(conversationId), queryFn: () => null, enabled: false });

  // The conversation is open: a push for it needs no banner (see `lib/notifications/push.ts`).
  useFocusEffect(useCallback(() => openConversation.open(conversationId), [conversationId]));

  // Reading: tell the API once the newest message is on screen (and again when a newer one arrives while it is open).
  const newestId = messages[0]?.id;
  useFocusEffect(
    useCallback(() => {
      if (!newestId && !query.isSuccess) return;
      const timer = setTimeout(() => {
        if (AppState.currentState === 'active') void markConversationRead(client, conversationId, newestId);
      }, 600);
      return () => clearTimeout(timer);
    }, [client, conversationId, newestId, query.isSuccess]),
  );

  // Screen readers hear that a message arrived (who wrote it, not what: the list holds the words).
  const known = useRef<MessageItem[] | null>(null);
  useEffect(() => {
    if (!query.isSuccess) return;
    const previous = known.current;
    known.current = messages;
    if (!previous) return;
    const incoming = incomingFromOthers(previous, messages);
    if (incoming.length > 0) AccessibilityInfo.announceForAccessibility(t('mobile.messaging.a11y.newMessage', { name: personText(incoming[0]!.sender, viewer) }));
  }, [messages, personText, query.isSuccess, t, viewer]);

  // Leaving with messages that are not sent yet: say what happens to them.
  const [guard, setGuard] = useState(true);
  usePreventRemove(guard && pending.some((entry) => entry.status !== 'sent'), ({ repeat }) => {
    Alert.alert(t('mobile.messaging.unsent.title'), t('mobile.messaging.unsent.body'), [
      { text: t('mobile.messaging.unsent.stay'), style: 'cancel' },
      {
        text: t('mobile.messaging.unsent.leave'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });

  const title = (() => {
    if (observing) return summary.contextStudent?.displayName ? t('mobile.messaging.inbox.observingTitle', { child: summary.contextStudent.displayName }) : t('mobile.messaging.inbox.observingTitleNoChild');
    const named = inboxTitle(summary);
    if (named.kind === 'person') return personText(named.person, viewer);
    if (named.kind === 'title') return named.title;
    return t(GROUP_KIND_LABEL[named.groupKind]);
  })();

  const messageIndex = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages]);
  const replyFor = useCallback(
    (message: MessageItem) => {
      if (!message.replyToMessageId) return null;
      const target = messageIndex.get(message.replyToMessageId);
      if (!target) return { author: t('mobile.messaging.message.replyEarlierAuthor'), snippet: null };
      const text = messageText(target);
      return { author: personText(target.sender, viewer), snippet: text ? text.slice(0, 80) : null };
    },
    [messageIndex, personText, t, viewer],
  );

  const putMessage = useCallback(
    (message: MessageItem): void => {
      client.setQueryData<MessageData>(messagesKey(conversationId), (current) => (current ? upsertMessage(current, message) : current));
    },
    [client, conversationId],
  );

  const send = useCallback(
    (input: { body: string; mediaIds: string[]; replyToMessageId: string | undefined }) => {
      outbox.enqueue({ clientMessageId: randomUUID(), conversationId, body: input.body, mediaIds: input.mediaIds, replyToMessageId: input.replyToMessageId });
      setReply(null);
      client.removeQueries({ queryKey: afterHoursKey(conversationId) });
    },
    [client, conversationId, setReply],
  );

  const handlers = useMemo(
    () => ({
      reply: () => options?.sender && setReply({ id: options.id, author: personText(options.sender, viewer) }),
      react: async (emoji: string, remove: boolean) => putMessage(remove ? await deleteReaction(conversationId, options!.id, emoji) : await putReaction(conversationId, options!.id, emoji)),
      edit: async (body: string) => putMessage(await editMessage(conversationId, options!.id, body)),
      remove: async () => {
        await deleteMessage(conversationId, options!.id);
        putMessage(await fetchMessage(conversationId, options!.id));
      },
      report: async (reason: Parameters<typeof reportMessage>[2], note: string) => {
        await reportMessage(conversationId, options!.id, reason, note || undefined);
        Alert.alert(t('mobile.messaging.report.thanksTitle'), t('mobile.messaging.report.thanksBody'));
      },
      block: async () => {
        const memberId = options?.sender?.memberId;
        if (!memberId) return;
        await blockMember(conversationId, memberId);
        Alert.alert(t('mobile.messaging.block.doneTitle'), t('mobile.messaging.block.doneBody'));
        void client.invalidateQueries({ queryKey: conversationKey(conversationId) });
        void client.invalidateQueries({ queryKey: inboxKey(null, null) });
        void refreshLatest(client, conversationId);
      },
      hide: async (reason: string) => {
        await hideMessage(conversationId, options!.id, reason);
        putMessage(await fetchMessage(conversationId, options!.id));
      },
      unhide: async () => {
        await unhideMessage(conversationId, options!.id);
        putMessage(await fetchMessage(conversationId, options!.id));
      },
    }),
    [client, conversationId, options, personText, putMessage, setReply, t, viewer],
  );

  const toggleReaction = useCallback(
    async (message: MessageItem, emoji: string, remove: boolean) => {
      try {
        putMessage(remove ? await deleteReaction(conversationId, message.id, emoji) : await putReaction(conversationId, message.id, emoji));
      } catch {
        // the next refresh shows the truth
      }
    },
    [conversationId, putMessage],
  );

  const context = { access, canModerate: detail.canModerate, viewer, editWindowMinutes: caps?.editWindowMinutes ?? 0, now };
  const optionActions = options ? messageActions(options, context) : [];

  const renderItem = useCallback(
    ({ item }: { item: TimelineItem }) => {
      if (item.kind === 'pending') {
        return <PendingMessage entry={item.entry} onRetry={() => outbox.retryNow(item.entry.clientMessageId)} onDiscard={() => outbox.discard(item.entry.clientMessageId)} />;
      }
      const message = item.message;
      const actions = messageActions(message, context);
      return (
        <MessageBubble
          message={message}
          viewer={viewer}
          replyTo={replyFor(message)}
          seen={message.id === seenId}
          now={now}
          canReact={access.canCompose}
          onOpenOptions={actions.length > 0 ? () => setOptions(message) : undefined}
          onToggleReaction={(emoji, mine) => void toggleReaction(message, emoji, mine)}
        />
      );
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `context` is rebuilt from these
    [access, detail.canModerate, viewer, caps?.editWindowMinutes, now, replyFor, seenId, toggleReaction],
  );

  const schedule = viewer !== 'STUDENT' && access.canCompose && caps ? { timeZone: caps.timeZone } : null;
  const canAttach = access.canCompose && !!caps && (viewer !== 'STUDENT' || caps.studentMediaEnabled);

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen
        options={{
          title,
          headerRight: () => (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('mobile.messaging.people.open')}
              hitSlop={8}
              onPress={() => router.push({ pathname: '/messages/[conversationId]/people', params: { conversationId } })}
              style={{ width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}>
              <SymbolView name={{ ios: 'info.circle', android: 'info', web: 'info' }} size={22} tintColor={colors.primary} />
            </Pressable>
          ),
        }}
      />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <View style={{ paddingBottom: spacing[1] }}>
          {notice === 'guardiansCanSee' ? <Banner tone="safety" icon={{ ios: 'person.2.fill', android: 'family_restroom' }} body={t('mobile.messaging.conversation.guardiansCanSee')} /> : null}
          {notice === 'teacherStudent' ? <Banner tone="safety" icon={{ ios: 'shield.fill', android: 'shield' }} title={t('mobile.messaging.conversation.teacherStudentTitle')} body={t('mobile.messaging.conversation.teacherStudentBody')} /> : null}
          {observing ? <Banner icon={{ ios: 'eye.fill', android: 'visibility' }} title={t('mobile.messaging.inbox.observing')} body={t('mobile.messaging.conversation.observerBanner')} /> : null}
        </View>

        <FlatList
          inverted
          data={timeline}
          keyExtractor={(item) => item.key}
          renderItem={renderItem}
          onEndReachedThreshold={0.5}
          onEndReached={() => {
            if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
          }}
          initialNumToRender={14}
          windowSize={9}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="interactive"
          contentContainerStyle={{ paddingVertical: spacing[3], flexGrow: 1 }}
          ListFooterComponent={
            query.isFetchingNextPage ? (
              <ActivityIndicator color={colors.primary} style={{ paddingVertical: spacing[3] }} />
            ) : query.isError && messages.length > 0 ? (
              <View style={{ padding: spacing[3], alignItems: 'center' }}>
                <Button variant="ghost" fullWidth={false} label={t('mobile.messaging.conversation.olderFailed')} onPress={() => void query.fetchNextPage()} />
              </View>
            ) : undefined
          }
          ListEmptyComponent={
            query.isPending ? (
              <View style={{ transform: [{ scaleY: -1 }], paddingVertical: spacing[8] }}>
                <ActivityIndicator color={colors.primary} />
              </View>
            ) : query.isError ? (
              <View style={{ transform: [{ scaleY: -1 }], padding: spacing[4] }}>
                <ProblemBanner error={query.error} fallbackTitle={t('mobile.messaging.conversation.loadFailedTitle')} fallbackBody={t('mobile.messaging.conversation.loadFailedBody')} onRetry={() => void query.refetch()} />
              </View>
            ) : (
              <View style={{ transform: [{ scaleY: -1 }], padding: spacing[6], alignItems: 'center' }}>
                <Text tone="muted" style={{ textAlign: 'center' }}>
                  {access.canCompose ? t('mobile.messaging.conversation.emptyWrite') : t('mobile.messaging.conversation.empty')}
                </Text>
              </View>
            )
          }
        />

        {afterHours.data !== undefined ? (
          <Banner tone="warning" icon={{ ios: 'moon.fill', android: 'bedtime' }} body={t('mobile.messaging.conversation.afterHours')} onDismiss={() => client.removeQueries({ queryKey: afterHoursKey(conversationId) })} />
        ) : null}
        {scheduledFor ? (
          <Banner icon={{ ios: 'clock.fill', android: 'schedule' }} body={t('mobile.messaging.scheduled.done', { when: formatInstant(scheduledFor, { locale, calendar, timeZone: caps?.timeZone }) })} onDismiss={() => setScheduledFor(null)} />
        ) : null}

        {access.canCompose ? (
          <Composer
            conversationId={conversationId}
            maxLength={caps?.maxMessageLength ?? 2000}
            canAttach={canAttach}
            schedule={schedule}
            reply={reply}
            onCancelReply={() => setReply(null)}
            onSend={send}
            onScheduled={(when) => {
              setScheduledFor(when);
              setReply(null);
            }}
          />
        ) : (
          <ReadOnlyNotice banner={access.banner} />
        )}
      </KeyboardAvoidingView>

      {options ? (
        <MessageOptions
          key={options.id}
          message={options}
          actions={optionActions}
          editRemainingMs={editRemainingMs(options, context.editWindowMinutes, now)}
          studentViewer={viewer === 'STUDENT'}
          onClose={() => setOptions(null)}
          handlers={handlers}
        />
      ) : null}
    </SafeAreaView>
  );
}

/** Where the composer would be: why one cannot write here. No composer at all (not a disabled one). */
function ReadOnlyNotice({ banner }: { banner: ReturnType<typeof accessOf>['banner'] }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const text =
    banner === 'observer'
      ? t('mobile.messaging.conversation.readOnlyObserver')
      : banner === 'pending'
        ? t('mobile.messaging.conversation.pendingBanner')
        : banner === 'restricted'
          ? t('mobile.messaging.conversation.restrictedBanner')
          : t('mobile.messaging.conversation.closedBanner');
  return (
    <View accessible accessibilityRole="text" style={{ padding: spacing[4], borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.muted }}>
      <Text variant="caption" style={{ textAlign: 'center', color: colors.foreground }}>
        {text}
      </Text>
    </View>
  );
}
