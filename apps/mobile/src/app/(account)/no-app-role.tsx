import { router } from 'expo-router';
import { useTranslations } from 'use-intl';

import { StaffAttendanceButton } from '@/components/attendance/staff-entry';
import { PushPromptCard } from '@/components/push-prompt-card';
import { TabHeader } from '@/components/tab-header';
import { Button, EmptyState, Screen, Text } from '@/components/ui';
import { useSession } from '@/lib/session/session-provider';

/**
 * Signed in, but the account has no teacher, parent or student role yet. Today that is
 * every administrator: their tools live on the web.
 */
export default function NoAppRoleScreen() {
  const t = useTranslations();
  const { profile, signOut } = useSession();
  const admin = profile?.accessKind === 'admin';

  return (
    <Screen scroll edges={['top', 'bottom']} contentStyle={{ justifyContent: 'center' }}>
      <TabHeader />
      <PushPromptCard />
      <EmptyState
        icon={{ ios: admin ? 'desktopcomputer' : 'person.crop.circle.badge.questionmark', android: admin ? 'computer' : 'help' }}
        title={t(admin ? 'mobile.noAppRole.adminTitle' : 'mobile.noAppRole.noneTitle')}
        body={t(admin ? 'mobile.noAppRole.adminBody' : 'mobile.noAppRole.noneBody')}>
        {profile ? (
          <Text variant="label" style={{ textAlign: 'center' }} selectable>
            {t('mobile.noAppRole.signedInAs', { name: profile.displayName })}
          </Text>
        ) : null}
        <StaffAttendanceButton />
        <Button variant="secondary" label={t('nav.settings')} onPress={() => router.push('/settings')} />
        <Button variant="ghost" label={t('auth.signOut')} onPress={() => signOut()} />
      </EmptyState>
    </Screen>
  );
}
