import { router } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { FlatList, Pressable, RefreshControl, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text } from '@/components/ui';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import type { InboxItem } from '@/lib/notifications/events';
import { useInbox, useInboxActions, useUnreadCount } from '@/lib/notifications/queries';
import { isReportNotification } from '@/lib/moderation/notifications';
import { routeForPath } from '@/lib/notifications/routes';
import { homeRouteFor } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** The notification inbox: newest first, pull to refresh, more as you scroll, tap to open and mark read. */
export default function NotificationsScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { activeRole, preview } = useSession();
  const inbox = useInbox(false);
  const unread = useUnreadCount();
  const { markRead, markAllRead } = useInboxActions();

  const items = inbox.data?.pages.flatMap((page) => page.items) ?? [];

  function open(item: InboxItem) {
    if (!item.readAt) markRead.mutate(item.id);
    // A reporter's thank-you and "reviewed" notices are generic and open nothing: there is no case screen for a reporter.
    if (isReportNotification(item.typeCode)) return;
    const target = routeForPath(item.data?.path, activeRole ? homeRouteFor(activeRole) : null, {
      ...item.data,
      ...(item.schoolId ? { schoolId: item.schoolId } : {}),
    });
    // only follow routes the app knows; this screen is where "/notifications" points anyway
    if (target && target !== '/notifications') router.navigate(target as Parameters<typeof router.navigate>[0]);
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={preview ? [] : items}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => <Row item={item} onPress={() => open(item)} />}
        contentContainerStyle={{ flexGrow: 1, padding: spacing[4], gap: spacing[2] }}
        refreshControl={
          <RefreshControl
            refreshing={inbox.isRefetching && !inbox.isFetchingNextPage}
            onRefresh={() => {
              void inbox.refetch();
              void unread.refetch();
            }}
          />
        }
        onEndReachedThreshold={0.4}
        onEndReached={() => {
          if (inbox.hasNextPage && !inbox.isFetchingNextPage) void inbox.fetchNextPage();
        }}
        ListHeaderComponent={
          !preview && items.length > 0 ? (
            <View style={{ alignItems: 'flex-end', marginBottom: spacing[2] }}>
              <Button
                variant="ghost"
                fullWidth={false}
                label={t('mobile.notifications.markAllRead')}
                disabled={!unread.data}
                onPress={() => markAllRead.mutate()}
              />
            </View>
          ) : undefined
        }
        ListEmptyComponent={
          inbox.isError && !preview ? (
            <ProblemBanner
              error={inbox.error}
              fallbackTitle={t('mobile.notifications.errorTitle')}
              fallbackBody={t('mobile.notifications.errorBody')}
              onRetry={() => void inbox.refetch()}
            />
          ) : inbox.isPending && !preview ? undefined : (
            <EmptyState
              icon={{ ios: 'bell.slash.fill', android: 'notifications_off' }}
              title={t('mobile.notifications.emptyTitle')}
              body={t('mobile.notifications.emptyBody')}
            />
          )
        }
      />
    </SafeAreaView>
  );
}

function Row({ item, onPress }: { item: InboxItem; onPress: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const format = useFormatInstant();
  const unread = !item.readAt;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={[unread ? t('mobile.notifications.unreadItem') : null, item.priority === 'URGENT' ? t('mobile.announcements.type.urgent') : null, item.title, item.body, format(item.createdAt)]
        .filter(Boolean)
        .join('. ')}
      onPress={onPress}
      style={({ pressed }) => ({
        flexDirection: 'row',
        gap: spacing[3],
        padding: spacing[4],
        borderRadius: radii.xl,
        backgroundColor: pressed ? colors.muted : colors.card,
        borderWidth: 1,
        borderColor: unread ? colors.primary : colors.border,
      })}>
      <View
        accessibilityElementsHidden
        style={{
          width: 10,
          height: 10,
          borderRadius: 5,
          marginTop: 7,
          backgroundColor: unread ? colors.primary : 'transparent',
        }}
      />
      <View style={{ flex: 1, gap: spacing[1] }}>
        {item.priority === 'URGENT' ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <SymbolView name={{ ios: 'exclamationmark.triangle.fill', android: 'warning', web: 'warning' }} size={16} tintColor={colors.destructive} />
            <Text variant="label" style={{ color: colors.destructive }}>
              {t('mobile.announcements.type.urgent')}
            </Text>
          </View>
        ) : null}
        <Text variant={unread ? 'label' : 'body'}>{item.title}</Text>
        {item.body ? <Text tone="muted">{item.body}</Text> : null}
        <Text variant="caption">{format(item.createdAt)}</Text>
      </View>
    </Pressable>
  );
}
