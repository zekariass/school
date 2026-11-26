import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, SectionList, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useMyChildren } from '@/lib/family/queries';
import { groupByChild } from '@/lib/messaging/derive';
import { useClock } from '@/lib/feed/use-clock';
import { useInbox, useUnreadCount } from '@/lib/messaging/queries';
import type { Role } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Chips } from '../feed/chips';
import { ProblemBanner } from '../problem-banner';
import { TabHeader } from '../tab-header';
import { Button, EmptyState, Sheet, Text } from '../ui';

import { ConversationRow } from './conversation-row';

/**
 * The messages tab of a role: the inbox (a guardian's grouped by child), unread counts, "you are observing" rows, pull to refresh,
 * more as you scroll. Everything is memory only. Students get no "new message" button: they have no directory of people.
 */
export function MessagesInbox({ role }: { role: Role }) {
  const t = useTranslations();
  const { preview } = useSession();
  if (preview) return <EmptyState icon={{ ios: 'bubble.left.and.bubble.right.fill', android: 'forum' }} title={t('nav.messages')} body={t('mobile.messaging.inbox.emptyGuardianBody')} />;
  return <Inbox role={role} />;
}

function Inbox({ role }: { role: Role }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const children = useMyChildren(role === 'guardian');
  const [child, setChild] = useState('all');
  const [menu, setMenu] = useState(false);
  const inbox = useInbox(null, role === 'guardian' && child !== 'all' ? child : null);
  const unread = useUnreadCount();
  const now = useClock(60_000);

  const kids = useMemo(() => children.data?.children ?? [], [children.data]);
  const grouped = role === 'guardian' && child === 'all';
  const sections = useMemo(
    () => (grouped ? groupByChild(inbox.items) : [{ key: 'all', childName: null, items: inbox.items }]),
    [grouped, inbox.items],
  );
  const showHeaders = grouped && sections.length > 1;
  const canStart = role !== 'student';

  const header = (
    <View style={{ gap: spacing[3], paddingBottom: spacing[2] }}>
      <TabHeader />
      <Text variant="title">{t('nav.messages')}</Text>
      <View style={{ flexDirection: 'row', gap: spacing[2] }}>
        {canStart ? <Button fullWidth={false} label={t('mobile.messaging.inbox.new')} onPress={() => router.push('/messages/new')} /> : null}
        <Button fullWidth={false} variant="ghost" label={t('mobile.messaging.inbox.more')} onPress={() => setMenu(true)} />
      </View>
      {role === 'guardian' && kids.length > 1 ? (
        <Chips
          accessibilityLabel={t('mobile.messaging.inbox.childFilter')}
          value={child}
          onChange={setChild}
          chips={[{ value: 'all', label: t('mobile.messaging.inbox.allChildren') }, ...kids.map((kid) => ({ value: kid.studentId, label: kid.givenName }))]}
        />
      ) : null}
    </View>
  );

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      <SectionList
        sections={sections}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <View style={{ paddingHorizontal: spacing[5], paddingBottom: spacing[2] }}>
            <ConversationRow item={item} now={now} />
          </View>
        )}
        renderSectionHeader={({ section }) =>
          showHeaders ? (
            <View style={{ paddingHorizontal: spacing[5], paddingTop: spacing[3], paddingBottom: spacing[2], backgroundColor: colors.background }}>
              <Text variant="label" tone="muted" accessibilityRole="header">
                {section.childName ? t('mobile.messaging.inbox.aboutChild', { child: section.childName }) : t('mobile.messaging.inbox.notAboutChild')}
              </Text>
            </View>
          ) : null
        }
        stickySectionHeadersEnabled={false}
        ListHeaderComponent={<View style={{ paddingHorizontal: spacing[5], paddingTop: spacing[4] }}>{header}</View>}
        contentContainerStyle={{ flexGrow: 1, paddingBottom: spacing[6] }}
        refreshControl={
          <RefreshControl
            refreshing={inbox.query.isRefetching && !inbox.query.isFetchingNextPage}
            onRefresh={() => void Promise.all([inbox.query.refetch(), unread.refetch(), children.refetch()])}
            tintColor={colors.primary}
          />
        }
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (inbox.query.hasNextPage && !inbox.query.isFetchingNextPage) void inbox.query.fetchNextPage();
        }}
        initialNumToRender={8}
        windowSize={7}
        ListEmptyComponent={
          inbox.query.isPending ? (
            <View style={{ paddingVertical: spacing[8] }}>
              <ActivityIndicator color={colors.primary} />
            </View>
          ) : inbox.query.isError ? (
            <View style={{ paddingHorizontal: spacing[5] }}>
              <ProblemBanner error={inbox.query.error} fallbackTitle={t('mobile.messaging.inbox.loadFailedTitle')} fallbackBody={t('mobile.messaging.inbox.loadFailedBody')} onRetry={() => void inbox.query.refetch()} />
            </View>
          ) : (
            <View style={{ flex: 1, paddingHorizontal: spacing[5] }}>
              <EmptyState
                icon={{ ios: 'bubble.left.and.bubble.right.fill', android: 'forum' }}
                title={role === 'student' ? t('mobile.messaging.inbox.emptyStudentTitle') : t('mobile.messaging.inbox.emptyTitle')}
                body={role === 'student' ? t('mobile.messaging.inbox.emptyStudentBody') : role === 'teacher' ? t('mobile.messaging.inbox.emptyTeacherBody') : t('mobile.messaging.inbox.emptyGuardianBody')}
              />
            </View>
          )
        }
        ListFooterComponent={
          inbox.query.isFetchingNextPage ? (
            <ActivityIndicator color={colors.primary} style={{ paddingVertical: spacing[4] }} />
          ) : inbox.query.isError && inbox.items.length > 0 ? (
            <View style={{ paddingHorizontal: spacing[5], gap: spacing[2] }}>
              <Text tone="muted">{t('mobile.messaging.inbox.nextPageFailed')}</Text>
              <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void inbox.query.fetchNextPage()} />
            </View>
          ) : undefined
        }
      />
      <MoreMenu visible={menu} onClose={() => setMenu(false)} role={role} />
    </View>
  );
}

/** The rest of the messaging: scheduled messages and blocked people for everybody; groups for teachers. */
function MoreMenu({ visible, onClose, role }: { visible: boolean; onClose: () => void; role: Role }) {
  const t = useTranslations();
  const go = (path: '/messages/scheduled' | '/messages/blocked' | '/messages/groups') => {
    onClose();
    router.push(path);
  };
  return (
    <Sheet visible={visible} onClose={onClose} title={t('mobile.messaging.inbox.more')}>
      {role !== 'student' ? <Button variant="ghost" label={t('mobile.messaging.scheduled.title')} onPress={() => go('/messages/scheduled')} /> : null}
      {role === 'teacher' ? <Button variant="ghost" label={t('mobile.messaging.groups.title')} onPress={() => go('/messages/groups')} /> : null}
      <Button variant="ghost" label={t('mobile.messaging.blocked.title')} onPress={() => go('/messages/blocked')} />
    </Sheet>
  );
}
