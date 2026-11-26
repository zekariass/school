import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { AnnouncementList } from '@/components/announcements/announcement-list';
import { Chips } from '@/components/feed/chips';
import { Button } from '@/components/ui';
import { AUTHOR_STATUS_FILTERS } from '@/lib/announcements/inbox';
import { MINE_STATUS } from '@/lib/announcements/labels';
import { useAnnouncementCapabilities, useAuthored } from '@/lib/announcements/queries';
import type { AnnouncementStatus } from '@/lib/announcements/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * What the person wrote in a school, in every status (drafts, scheduled, published, withdrawn), newest first. A manager sees
 * the whole school's announcements too, with a switch to "mine only". The "New announcement" button follows the capabilities.
 */
export default function MyAnnouncements() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { schoolId } = useLocalSearchParams<{ schoolId: string }>();
  const [status, setStatus] = useState<AnnouncementStatus | 'ALL'>('ALL');
  const [mine, setMine] = useState(true);
  const caps = useAnnouncementCapabilities(schoolId);
  const authored = useAuthored(schoolId, status === 'ALL' ? null : status, mine);

  const header = (
    <View style={{ gap: spacing[3], marginBottom: spacing[2] }}>
      {caps.data?.canCompose ? <Button label={t('mobile.announcements.mine.new')} onPress={() => router.push({ pathname: '/announcements/compose', params: { schoolId } })} /> : null}
      {caps.data?.schoolWide ? (
        <Chips
          accessibilityLabel={t('mobile.announcements.mine.whoseLabel')}
          value={mine ? 'mine' : 'school'}
          onChange={(value) => setMine(value === 'mine')}
          chips={[
            { value: 'mine', label: t('mobile.announcements.mine.mineOnly') },
            { value: 'school', label: t('mobile.announcements.mine.wholeSchool') },
          ]}
        />
      ) : null}
      <Chips
        accessibilityLabel={t('mobile.announcements.mine.statusLabel')}
        value={status}
        onChange={setStatus}
        chips={AUTHOR_STATUS_FILTERS.map((value) => ({ value, label: t(MINE_STATUS[value]) }))}
      />
    </View>
  );

  return (
    <AnnouncementList
      query={authored.query}
      cards={authored.cards}
      header={header}
      authorView
      empty={{ icon: { ios: 'megaphone', android: 'campaign' }, title: t('mobile.announcements.mine.emptyTitle'), body: t('mobile.announcements.mine.emptyBody') }}
      onRefresh={() => caps.refetch()}
    />
  );
}
