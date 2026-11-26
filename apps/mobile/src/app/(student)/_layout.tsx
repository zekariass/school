import { NativeTabs } from 'expo-router/native-tabs';
import { useTranslations } from 'use-intl';

import { useTabBarProps } from '@/components/tab-bar';

export default function StudentTabs() {
  const t = useTranslations();
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
      <NativeTabs.Trigger name="portfolio">
        <NativeTabs.Trigger.Label>{t('nav.portfolio')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'photo.on.rectangle', selected: 'photo.on.rectangle.angled' }} md="photo_library" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="profile">
        <NativeTabs.Trigger.Label>{t('nav.profile')}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: 'person.crop.circle', selected: 'person.crop.circle.fill' }} md="account_circle" />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
