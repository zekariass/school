import { router } from 'expo-router';
import { useState } from 'react';
import { RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ChildCard, PendingCard } from '@/components/family/child-card';
import { PlaceholderScreen } from '@/components/placeholder-screen';
import { ProblemBanner } from '@/components/problem-banner';
import { PushPromptCard } from '@/components/push-prompt-card';
import { TabHeader } from '@/components/tab-header';
import { Button, EmptyState, Screen, Text } from '@/components/ui';
import { useMyChildren, useMyConsents } from '@/lib/family/queries';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { openDecisionCount } from '@school/validation/family';

/** "My children": calm and minimal. Each child with classes and teachers, waiting requests, open consent decisions. */
export default function GuardianHome() {
  const { preview } = useSession();
  // The developer role previewer has no account behind it, so there is nothing to load.
  return preview ? <PlaceholderScreen kind="guardianHome" /> : <MyChildren />;
}

function MyChildren() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const children = useMyChildren();
  const consents = useMyConsents();
  const [refreshing, setRefreshing] = useState(false);

  async function refresh() {
    setRefreshing(true);
    await Promise.all([children.refetch(), consents.refetch()]);
    setRefreshing(false);
  }

  const open = consents.data ? openDecisionCount(consents.data.children) : 0;
  const data = children.data;
  const empty = !!data && data.children.length === 0 && data.pending.length === 0;

  return (
    <Screen
      scroll
      contentStyle={{ gap: spacing[4] }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}>
      <TabHeader />
      <PushPromptCard />
      <View style={{ gap: spacing[1] }}>
        <Text variant="title">{t('family.title')}</Text>
        <Text tone="muted">{t('family.subtitle')}</Text>
      </View>

      {open > 0 ? (
        <View style={{ gap: spacing[2] }} accessibilityRole="alert">
          <Text variant="label">{t('family.consentNeeded')}</Text>
          <Button variant="secondary" label={t('family.consentReview')} onPress={() => router.push('/consent')} />
        </View>
      ) : null}

      {children.isPending ? null : children.isError ? (
        <ProblemBanner
          error={children.error}
          fallbackTitle={t('status.errorTitle')}
          fallbackBody={t('family.loadFailed')}
          onRetry={() => void children.refetch()}
        />
      ) : empty ? (
        <EmptyState
          icon={{ ios: 'person.2.fill', android: 'family_restroom' }}
          title={t('family.emptyTitle')}
          body={t('family.emptyBody')}
        />
      ) : (
        <>
          {data?.children.map((child) => (
            <ChildCard
              key={child.studentId}
              child={child}
              consent={childConsent(consents.data, child.studentId)}
              onReviewConsent={() => router.push('/consent')}
              onOpenPoints={() => router.push({ pathname: '/child/[studentId]', params: { studentId: child.studentId } })}
            />
          ))}
          {data?.pending.map((link) => <PendingCard key={link.linkId} link={link} />)}
        </>
      )}

      <Button variant={empty ? 'primary' : 'ghost'} label={t('family.connect.open')} onPress={() => router.push('/parent-code')} />
    </Screen>
  );
}

function childConsent(
  consents: ReturnType<typeof useMyConsents>['data'],
  studentId: string,
): 'needed' | 'done' | null {
  if (!consents) return null;
  const mine = consents.children.filter((c) => c.studentId === studentId);
  if (mine.length === 0) return null;
  return openDecisionCount(mine) > 0 ? 'needed' : 'done';
}
