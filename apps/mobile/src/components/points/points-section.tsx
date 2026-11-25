import { useState, type ReactNode } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Card, Text } from '@/components/ui';
import { formatSigned } from '@/lib/points/categories';
import type { Section } from '@/lib/points/queries';
import { donutModel } from '@/lib/points/donut';

import { DonutChart } from './donut-chart';
import { TimelineList } from './timeline-list';

export interface PointsSectionProps {
  title: string;
  /** A plain sentence under the title (what these points are, for home points). */
  note?: string;
  section: Section;
  schoolId: string | undefined;
  studentId: string | undefined;
  kind: 'SCHOOL' | 'HOME';
  /** Extra content under the totals, e.g. the home award button. */
  action?: ReactNode;
}

/**
 * One block of a child's points: total, donut by skill, needs-work list and the timeline on demand. School and
 * home points are always separate blocks with their own totals: they are never added together.
 */
export function PointsSection({ title, note, section, schoolId, studentId, kind, action }: PointsSectionProps) {
  const t = useTranslations();
  const [showTimeline, setShowTimeline] = useState(false);
  const total = section.total ?? 0;
  const model = donutModel(section.categories);

  return (
    <Card accessibilityLabel={`${title}, ${t('mobile.points.section.total', { total: formatSigned(total) })}`}>
      <Text variant="heading">{title}</Text>
      {note ? <Text tone="muted">{note}</Text> : null}
      <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 8 }}>
        <Text variant="title" allowFontScaling={false} style={{ fontSize: 44, lineHeight: 52 }}>
          {total}
        </Text>
        <Text tone="muted">{t('mobile.points.section.points')}</Text>
      </View>

      <DonutChart categories={section.categories} total={model.total} />

      {model.needsWork.length > 0 ? (
        <View style={{ gap: 4 }}>
          <Text variant="label">{t('mobile.points.section.needsWork')}</Text>
          {model.needsWork.map((item) => (
            <Text key={item.categoryId} tone="muted">
              {item.name}: {formatSigned(item.points)}
            </Text>
          ))}
        </View>
      ) : null}

      {action}

      <Button
        variant="ghost"
        label={showTimeline ? t('mobile.points.section.hideTimeline') : t('mobile.points.section.showTimeline')}
        onPress={() => setShowTimeline((current) => !current)}
      />
      {showTimeline ? <TimelineList schoolId={schoolId} studentId={studentId} section={kind} /> : null}
    </Card>
  );
}
