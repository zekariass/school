import { NativeTabs } from 'expo-router/native-tabs';
import { useTranslations } from 'use-intl';

import { useMessagesBadge } from '@/components/messaging/use-messages-badge';
import { useTabBarProps } from '@/components/tab-bar';

export default function StudentTabs() {
  const t = useTranslations();
  const badge = useMessagesBadge();
  return (
    <NativeTabs {...useTabBarProps()}>
      <NativeTabs.Trigger name="home">
        <NativeTabs.Trigger.Label>{t('nav.home')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'star', selected: 'star.fill' }} md="star" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="feed">
        <NativeTabs.Trigger.Label>{t('mobile.feed.tab')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'photo.on.rectangle', selected: 'photo.on.rectangle.angled' }} md="photo_library" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="points">
        <NativeTabs.Trigger.Label>{t('mobile.points.tab')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'trophy', selected: 'trophy.fill' }} md="emoji_events" />
      </NativeTabs.Trigger>
      {/* Five tabs at most (the Android bar allows no more): the portfolio is not a tab but a card on the home screen that opens the
          `portfolio` stack (the calendar does the same). */}
      <NativeTabs.Trigger name="messages">
        <NativeTabs.Trigger.Label>{t('nav.messages')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon
          sf={{ default: 'bubble.left.and.bubble.right', selected: 'bubble.left.and.bubble.right.fill' }}
          md="forum"
        />
        <NativeTabs.Trigger.Badge hidden={!badge}>{badge}</NativeTabs.Trigger.Badge>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="profile">
        <NativeTabs.Trigger.Label>{t('nav.profile')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'person.crop.circle', selected: 'person.crop.circle.fill' }} md="account_circle" />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
