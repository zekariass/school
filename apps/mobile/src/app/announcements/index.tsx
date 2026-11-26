import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { AnnouncementList } from '@/components/announcements/announcement-list';
import { Chips } from '@/components/feed/chips';
import { SegmentedControl } from '@/components/ui';
import { useAnnouncementSummary, useInbox } from '@/lib/announcements/queries';
import type { InboxFilter, InboxSort } from '@/lib/announcements/keys';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The announcements delivered to the person (a guardian, a student with a class code, a teacher or other staff): filters,
 * "unread first", pull to refresh and more as you scroll. Texts and lists live in memory only.
 */
export default function AnnouncementsInbox() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { schoolId } = useLocalSearchParams<{ schoolId?: string }>();
  const [filter, setFilter] = useState<InboxFilter>('ALL');
  const [sort, setSort] = useState<InboxSort>('NEWEST');
  const inbox = useInbox(schoolId ?? null, filter, sort);
  const summary = useAnnouncementSummary();

  const header = (
    <View style={{ gap: spacing[3], marginBottom: spacing[2] }}>
      <Chips
        accessibilityLabel={t('mobile.announcements.filter.label')}
        value={filter}
        onChange={setFilter}
        chips={[
          { value: 'ALL', label: t('mobile.announcements.filter.all') },
          { value: 'UNREAD', label: summary.data ? t('mobile.announcements.filter.unreadCount', { count: summary.data.unread }) : t('mobile.announcements.filter.unread') },
          {
            value: 'NEEDS_ACKNOWLEDGEMENT',
            label: summary.data ? t('mobile.announcements.filter.needsAckCount', { count: summary.data.needingAcknowledgement }) : t('mobile.announcements.filter.needsAck'),
          },
        ]}
      />
      <SegmentedControl
        accessibilityLabel={t('mobile.announcements.sort.label')}
        value={sort}
        onChange={setSort}
        options={[
          { value: 'NEWEST', label: t('mobile.announcements.sort.newest') },
          { value: 'UNREAD_FIRST', label: t('mobile.announcements.sort.unreadFirst') },
        ]}
      />
    </View>
  );

  const empty =
    filter === 'UNREAD'
      ? { title: t('mobile.announcements.empty.unreadTitle'), body: t('mobile.announcements.empty.unreadBody') }
      : filter === 'NEEDS_ACKNOWLEDGEMENT'
        ? { title: t('mobile.announcements.empty.ackTitle'), body: t('mobile.announcements.empty.ackBody') }
        : { title: t('mobile.announcements.empty.title'), body: t('mobile.announcements.empty.body') };

  return (
    <AnnouncementList
      query={inbox.query}
      cards={inbox.cards}
      header={header}
      empty={{ icon: { ios: 'megaphone', android: 'campaign' }, ...empty }}
      onRefresh={() => summary.refetch()}
    />
  );
}
