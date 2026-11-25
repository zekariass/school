import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Text } from '@/components/ui';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { formatSigned } from '@/lib/points/categories';
import { useTimeline, type TimelineItem } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { CategoryIcon } from './category-tile';

/** A student's points over time, newest first, a page at a time. Never persisted. */
export function TimelineList({
  schoolId,
  studentId,
  section,
}: {
  schoolId: string | undefined;
  studentId: string | undefined;
  section: 'SCHOOL' | 'HOME';
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const timeline = useTimeline(schoolId, studentId, section);
  const items = timeline.data?.pages.flatMap((page) => page.items) ?? [];

  if (timeline.isPending) return <Text tone="muted">{t('common.loading')}</Text>;
  if (timeline.isError && items.length === 0) {
    return (
      <ProblemBanner
        error={timeline.error}
        fallbackTitle={t('mobile.points.timeline.errorTitle')}
        fallbackBody={t('mobile.points.timeline.errorBody')}
        onRetry={() => void timeline.refetch()}
      />
    );
  }
  if (items.length === 0) return <Text tone="muted">{t('mobile.points.timeline.empty')}</Text>;

  return (
    <View style={{ gap: spacing[3] }}>
      {items.map((item) => (
        <Row key={item.id} item={item} />
      ))}
      {timeline.hasNextPage ? (
        <Button
          variant="ghost"
          label={t('mobile.points.timeline.more')}
          loading={timeline.isFetchingNextPage}
          onPress={() => void timeline.fetchNextPage()}
        />
      ) : null}
      {timeline.isError ? <Text tone="destructive">{t('mobile.points.timeline.errorBody')}</Text> : null}
      <View style={{ height: 1, backgroundColor: colors.border }} />
    </View>
  );
}

function Row({ item }: { item: TimelineItem }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const format = useFormatInstant();
  const points = item.points ?? 0;
  const reversal = item.type === 'REVERSAL';
  const title = reversal ? t('mobile.points.timeline.reversal', { skill: item.categoryName }) : item.categoryName;
  const who = [item.className, item.awardedByName].filter(Boolean).join(' · ');
  const label = [
    title,
    formatSigned(points),
    format(item.createdAt),
    who,
    item.reversed ? t('mobile.points.timeline.undone') : null,
    item.note ?? null,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <View accessible accessibilityLabel={label} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], opacity: item.reversed ? 0.55 : 1 }}>
      <CategoryIcon category={{ icon: item.icon, color: item.color, polarity: item.polarity }} size={40} />
      <View style={{ flex: 1, gap: 2 }}>
        <Text variant="label" numberOfLines={2} style={{ textDecorationLine: item.reversed ? 'line-through' : 'none' }}>
          {title}
        </Text>
        <Text variant="caption" numberOfLines={1}>
          {[format(item.createdAt), who].filter(Boolean).join(' · ')}
        </Text>
        {item.note ? (
          <Text variant="caption" numberOfLines={3}>
            {item.note}
          </Text>
        ) : null}
        {item.reversed ? (
          <Text variant="caption" tone="muted">
            {t('mobile.points.timeline.undone')}
          </Text>
        ) : null}
      </View>
      <Text variant="heading" style={{ color: points < 0 ? colors.pointsNeedsWork : colors.success }}>
        {formatSigned(points)}
      </Text>
    </View>
  );
}
