import { useQuery } from '@tanstack/react-query';
import Constants from 'expo-constants';
import { Link, router } from 'expo-router';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { CommunicationChannelsCard } from '@/components/announcements/channels-card';
import { AvatarSection } from '@/components/avatar-section';
import { NotificationSettings } from '@/components/notification-settings';
import { Button, Card, Screen, SegmentedControl, Text } from '@/components/ui';
import { SUPPORTED_LOCALES, isSupportedLocale } from '@school/i18n';
import { metaQuery } from '@/lib/meta';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { ROLE_LABEL_KEY, homeRouteFor, type Role } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { THEME_PREFERENCES } from '@/lib/theme/preference';
import { useAppTheme } from '@/lib/theme/theme-provider';

export default function SettingsScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { locale, setLocale, themePreference, setThemePreference, calendar, calendars, setCalendar } = usePreferences();
  const { status, roles, activeRole, setActiveRole, signOut, preview } = useSession();
  // Show the API version the last status check saw; don't fetch just to open settings.
  const meta = useQuery({ ...metaQuery, staleTime: Infinity, enabled: false });

  function switchRole(role: Role) {
    setActiveRole(role);
    if (router.canDismiss()) router.dismissAll();
    router.replace(homeRouteFor(role));
  }


  async function onSignOut() {
    await signOut();
    // The root layout returns to the entry route when the session ends; this covers a settings
    // screen that is the only route left.
    if (router.canDismiss()) router.dismissAll();
    router.replace('/');
  }

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      {status === 'signedIn' && !preview ? <AvatarSection /> : null}

      <Section title={t('locale.label')}>
        <SegmentedControl
          accessibilityLabel={t('locale.label')}
          value={locale}
          onChange={(value) => isSupportedLocale(value) && setLocale(value)}
          options={SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.nativeName }))}
        />
      </Section>

      <Section title={t('theme.label')}>
        <SegmentedControl
          accessibilityLabel={t('theme.label')}
          value={themePreference}
          onChange={setThemePreference}
          options={THEME_PREFERENCES.map((value) => ({ value, label: t(`theme.${value}`) }))}
        />
      </Section>

      {/* Nothing to choose where the country has only the Gregorian calendar (D42). */}
      {calendars.length > 1 ? (
        <Section title={t('calendar.label')}>
          <SegmentedControl
            accessibilityLabel={t('calendar.label')}
            value={calendar}
            onChange={setCalendar}
            options={calendars.map((value) => ({ value, label: t(`calendar.${value}`) }))}
          />
        </Section>
      ) : null}

      {status === 'signedIn' && !preview ? <NotificationSettings /> : null}
      {status === 'signedIn' && !preview ? <CommunicationChannelsCard /> : null}

      {status === 'signedIn' && roles.length > 1 ? (
        <Section title={t('mobile.settings.switchRole')} hint={t('mobile.settings.switchRoleHint')}>
          <SegmentedControl
            accessibilityLabel={t('mobile.settings.switchRole')}
            value={activeRole ?? roles[0]!}
            onChange={switchRole}
            options={roles.map((role) => ({ value: role, label: t(ROLE_LABEL_KEY[role]) }))}
          />
        </Section>
      ) : null}

      <Section title={t('mobile.settings.about')}>
        <Row label={t('mobile.settings.appVersion')} value={Constants.expoConfig?.version ?? '-'} />
        <Row label={t('status.apiVersion')} value={meta.data?.meta.apiVersion ?? t('mobile.settings.unknown')} />
        <Link href="/status" accessibilityRole="link" style={{ paddingVertical: spacing[2] }}>
          <Text tone="primary" variant="label">
            {t('marketing.statusLink')}
          </Text>
        </Link>
      </Section>

      {status === 'signedIn' ? (
        <View style={{ gap: spacing[2] }}>
          {preview ? <Text variant="caption">{t('mobile.settings.previewNote')}</Text> : null}
          <Button variant="ghost" label={t('auth.signOut')} onPress={onSignOut} />
        </View>
      ) : null}
    </Screen>
  );
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <Card>
      <Text variant="heading">{title}</Text>
      {hint ? <Text variant="caption">{hint}</Text> : null}
      {children}
    </Card>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View
      accessible
      accessibilityLabel={`${label}: ${value}`}
      style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 12 }}>
      <Text tone="muted">{label}</Text>
      <Text variant="label" selectable>
        {value}
      </Text>
    </View>
  );
}
