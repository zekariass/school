import { NativeTabs } from 'expo-router/native-tabs';
import { useTranslations } from 'use-intl';

import { useMessagesBadge } from '@/components/messaging/use-messages-badge';
import { useTabBarProps } from '@/components/tab-bar';

export default function TeacherTabs() {
  const t = useTranslations();
  const badge = useMessagesBadge();
  return (
    <NativeTabs {...useTabBarProps()}>
      <NativeTabs.Trigger name="classes">
        <NativeTabs.Trigger.Label>{t('nav.classes')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'person.3', selected: 'person.3.fill' }} md="groups" />
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
