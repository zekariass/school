import { randomUUID } from 'expo-crypto';
import { useState } from 'react';
import { Alert, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ConsentItemCard, type ConsentTarget } from '@/components/family/consent-item-card';
import { ProblemBanner } from '@/components/problem-banner';
import { Card, EmptyState, Screen, Text } from '@/components/ui';
import { ApiRequestError } from '@/lib/api-error';
import { useMyConsents, useRecordConsent } from '@/lib/family/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';
import {
  isChildGoneProblem,
  isVersionOutdatedProblem,
  orderedChildItems,
  type ConsentAction,
} from '@school/validation/family';

/**
 * Consent requests of my children and my own agreements. A decision is recorded for one child only,
 * after a confirmation; if the text changed meanwhile the new version is loaded and I decide again.
 */
export default function ConsentScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const consents = useMyConsents();
  const record = useRecordConsent();
  const [notice, setNotice] = useState<'outdated' | 'gone' | 'saved' | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  function confirmDecision(target: ConsentTarget, action: ConsentAction) {
    const version = target.item.currentVersion;
    if (!version) return;
    const name = target.child?.name ?? '';
    const media = target.item.consentType === 'MEDIA_PUBLICATION' && target.child;
    Alert.alert(
      t(`consent.confirmTitle.${action}`, { child: name || '—' }),
      [t(`consent.type.${target.item.consentType}`), media ? t('consent.confirmBodyMedia', { child: name }) : target.child ? t('consent.confirmBody', { child: name }) : ''].filter(Boolean).join('\n\n'),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t(`consent.confirm.${action}`),
          style: action === 'GRANTED' ? 'default' : 'destructive',
          onPress: () => submit(target, action, version.versionId),
        },
      ],
    );
  }

  function submit(target: ConsentTarget, action: ConsentAction, policyVersionId: string) {
    setNotice(null);
    record.mutate(
      {
        idempotencyKey: randomUUID(),
        consentType: target.item.consentType,
        policyVersionId,
        decision: action,
        ...(target.child ? { studentId: target.child.studentId, schoolId: target.child.schoolId } : {}),
      },
      {
        onSuccess: () => setNotice('saved'),
        onError: (error) => {
          const problem = error instanceof ApiRequestError ? error.problem : undefined;
          if (error instanceof ApiRequestError && isVersionOutdatedProblem(problem, error.status)) {
            setNotice('outdated');
            void consents.refetch();
          } else if (isChildGoneProblem(problem)) {
            setNotice('gone');
            void consents.refetch();
          }
        },
      },
    );
  }

  const data = consents.data;
  const empty = !!data && data.children.length === 0 && data.account.length === 0;
  const genericError = record.isError && notice === null ? record.error : null;

  return (
    <Screen
      scroll
      edges={['bottom']}
      contentStyle={{ gap: spacing[4] }}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => {
            setRefreshing(true);
            void consents.refetch().finally(() => setRefreshing(false));
          }}
        />
      }>
      <Text tone="muted">{t('consent.subtitle')}</Text>

      <View accessibilityLiveRegion="polite" style={{ gap: spacing[2] }}>
        {notice === 'outdated' ? <Text tone="destructive">{t('consent.versionOutdated')}</Text> : null}
        {notice === 'gone' ? <Text tone="destructive">{t('consent.childNotFound')}</Text> : null}
        {notice === 'saved' ? <Text variant="label">{t('consent.saved')}</Text> : null}
        {genericError ? (
          <ProblemBanner error={genericError} fallbackTitle={t('status.errorTitle')} fallbackBody={t('consent.saveFailed')} />
        ) : null}
      </View>

      {consents.isError ? (
        <ProblemBanner
          error={consents.error}
          fallbackTitle={t('status.errorTitle')}
          fallbackBody={t('consent.loadFailed')}
          onRetry={() => void consents.refetch()}
        />
      ) : null}

      {empty ? (
        <EmptyState icon={{ ios: 'checkmark.shield.fill', android: 'verified_user' }} title={t('consent.title')} body={t('consent.nothingToDecide')} />
      ) : null}

      {data?.children.map((child) => (
        <Card key={`${child.studentId}:${child.schoolId}`}>
          <Text variant="heading">{child.childName}</Text>
          <Text tone="muted">{child.schoolName}</Text>
          <Text variant="label">{t('consent.perChild', { child: child.childName })}</Text>
          {orderedChildItems(child.items).map((item) => (
            <ConsentItemCard
              key={item.consentType}
              item={item}
              child={{ studentId: child.studentId, schoolId: child.schoolId, name: child.childName }}
              busy={record.isPending}
              onDecide={(action) =>
                confirmDecision({ item, child: { studentId: child.studentId, schoolId: child.schoolId, name: child.childName } }, action)
              }
            />
          ))}
        </Card>
      ))}

      {data && data.account.length > 0 ? (
        <Card>
          <Text variant="heading">{t('consent.accountTitle')}</Text>
          <Text tone="muted">{t('consent.accountIntro')}</Text>
          {data.account.map((item) => (
            <ConsentItemCard key={item.consentType} item={item} busy={record.isPending} onDecide={(action) => confirmDecision({ item }, action)} />
          ))}
        </Card>
      ) : null}
    </Screen>
  );
}
