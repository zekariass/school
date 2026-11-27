import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { ItemList } from '@/components/portfolio/item-list';
import { NeedsConnection } from '@/components/portfolio/needs-connection';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Screen, Text } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { STATUS_LABEL } from '@/lib/portfolio/labels';
import { STATUS_FILTERS, needsConnection, type StatusFilter } from '@/lib/portfolio/model';
import { useMyItems, usePortfolioCapabilities } from '@/lib/portfolio/queries';
import { schoolsOf } from '@/lib/portfolio/schools';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * A student's own portfolio: every item they made or that a teacher captured about them, in every status, with a filter and a
 * button to add work (only when the API says the school lets students add). Nothing is kept on the phone: without a connection
 * the screen says it needs one.
 */
export default function MyPortfolio() {
  const t = useTranslations();
  const { colors } = useAppTheme();
  const me = useMe();
  const schools = useMemo(() => schoolsOf(me.data?.memberships, 'STUDENT'), [me.data?.memberships]);
  const [picked, setPicked] = useState<string | null>(null);
  const schoolId = picked ?? schools[0]?.id;

  if (!schoolId) {
    return (
      <Screen edges={['bottom']}>
        {me.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : me.isError ? (
          needsConnection(me.error) ? (
            <NeedsConnection onRetry={() => void me.refetch()} />
          ) : (
            <ProblemBanner error={me.error} fallbackTitle={t('mobile.portfolio.loadFailedTitle')} fallbackBody={t('mobile.portfolio.loadFailedBody')} onRetry={() => void me.refetch()} />
          )
        ) : (
          <EmptyState icon={{ ios: 'photo.on.rectangle.angled', android: 'photo_library' }} title={t('mobile.portfolio.mine.noSchoolTitle')} body={t('mobile.portfolio.mine.noSchoolBody')} />
        )}
      </Screen>
    );
  }
  return <Items key={schoolId} schoolId={schoolId} schools={schools} onSchool={setPicked} />;
}

function Items({ schoolId, schools, onSchool }: { schoolId: string; schools: { id: string; name: string }[]; onSchool: (id: string) => void }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const caps = usePortfolioCapabilities(schoolId);
  const { query, items } = useMyItems(schoolId, filter === 'ALL' ? null : filter);
  const canCreate = caps.data?.studentMayCreate === true;

  const header = (
    <View style={{ gap: spacing[3] }}>
      {schools.length > 1 ? <Chips accessibilityLabel={t('mobile.portfolio.mine.school')} value={schoolId} onChange={onSchool} chips={schools.map((school) => ({ value: school.id, label: school.name }))} /> : null}
      {canCreate ? <Button label={t('mobile.portfolio.mine.add')} onPress={() => router.push({ pathname: '/portfolio/compose', params: { schoolId } })} /> : null}
      {caps.data && !canCreate ? <Text variant="caption">{t('mobile.portfolio.mine.createOff')}</Text> : null}
      <Chips<StatusFilter>
        accessibilityLabel={t('mobile.portfolio.mine.filter')}
        value={filter}
        onChange={setFilter}
        chips={STATUS_FILTERS.map((value) => ({ value, label: value === 'ALL' ? t('mobile.portfolio.mine.all') : t(STATUS_LABEL[value]) }))}
      />
    </View>
  );

  return (
    <ItemList
      query={query}
      items={items}
      header={header}
      edges={['bottom']}
      onRefresh={() => caps.refetch()}
      empty={{
        icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' },
        title: filter === 'ALL' ? t('mobile.portfolio.mine.emptyTitle') : t('mobile.portfolio.mine.emptyFilteredTitle'),
        body: filter === 'ALL' ? t('mobile.portfolio.mine.emptyBody') : t('mobile.portfolio.mine.emptyFilteredBody'),
      }}
    />
  );
}
