import { NativeTabs } from 'expo-router/native-tabs';
import { useTranslations } from 'use-intl';

import { useMessagesBadge } from '@/components/messaging/use-messages-badge';
import { useTabBarProps } from '@/components/tab-bar';

export default function GuardianTabs() {
  const t = useTranslations();
  const badge = useMessagesBadge();
  return (
    <NativeTabs {...useTabBarProps()}>
      <NativeTabs.Trigger name="home">
        <NativeTabs.Trigger.Label>{t('nav.home')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'house', selected: 'house.fill' }} md="home" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="feed">
        <NativeTabs.Trigger.Label>{t('mobile.feed.tab')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'photo.on.rectangle', selected: 'photo.on.rectangle.angled' }} md="photo_library" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="messages">
        <NativeTabs.Trigger.Label>{t('nav.messages')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon
          sf={{ default: 'bubble.left.and.bubble.right', selected: 'bubble.left.and.bubble.right.fill' }}
          md="forum"
        />
        <NativeTabs.Trigger.Badge hidden={!badge}>{badge}</NativeTabs.Trigger.Badge>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="calendar">
        <NativeTabs.Trigger.Label>{t('nav.calendar')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'calendar', selected: 'calendar' }} md="calendar_month" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="profile">
        <NativeTabs.Trigger.Label>{t('nav.profile')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'person.crop.circle', selected: 'person.crop.circle.fill' }} md="account_circle" />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
