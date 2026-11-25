import { router } from 'expo-router';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useMe } from '@/lib/auth/use-me';
import { ROLE_LABEL_KEY, type Role } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from './avatar';
import { TabHeader } from './tab-header';
import { Button, Card, EmptyState, Screen, Text } from './ui';

const MEMBERSHIP_OF_ROLE = { teacher: 'TEACHER', student: 'STUDENT', guardian: 'GUARDIAN' } as const;

/** Profile tab shared by every role: who is signed in, their school, Settings and sign out. */
export function ProfileScreen({ role }: { role: Role }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { preview, signOut } = useSession();
  const me = useMe();

  if (preview || !me.data) {
    return (
      <Screen>
        <TabHeader />
        <EmptyState
          icon={{ ios: 'person.crop.circle', android: 'account_circle' }}
          title={t(ROLE_LABEL_KEY[role])}
          body={t('mobile.empty.profile.body')}>
          <Button label={t('nav.settings')} onPress={() => router.push('/settings')} />
        </EmptyState>
      </Screen>
    );
  }

  const profile = me.data;
  const schools = [
    ...new Set(
      profile.memberships.filter((membership) => membership.type === MEMBERSHIP_OF_ROLE[role]).map((membership) => membership.schoolName),
    ),
  ];

  return (
    <Screen scroll contentStyle={{ gap: spacing[5] }}>
      <TabHeader />
      <View style={{ alignItems: 'center', gap: spacing[3] }}>
        <Avatar
          mediaId={profile.avatar?.id}
          name={profile.displayName}
          seed={profile.id}
          size={132}
          accessibilityLabel={t('media.avatar.alt', { name: profile.displayName })}
        />
        <Text variant="title" style={{ textAlign: 'center' }}>
          {profile.displayName}
        </Text>
        <Text tone="muted">{t(ROLE_LABEL_KEY[role])}</Text>
      </View>

      {schools.length > 0 ? (
        <Card>
          <Text variant="caption">{t(schools.length > 1 ? 'mobile.profile.schools' : 'mobile.profile.school')}</Text>
          {schools.map((name) => (
            <Text key={name} variant="heading">
              {name}
            </Text>
          ))}
        </Card>
      ) : null}

      <View style={{ gap: spacing[3] }}>
        <Button variant="secondary" label={t('nav.settings')} onPress={() => router.push('/settings')} />
        <Button variant="ghost" label={t('auth.signOut')} onPress={() => void signOut()} />
      </View>
    </Screen>
  );
}
