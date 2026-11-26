import type { ReactNode } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Card, Text } from '@/components/ui';
import { isReasonCode, reportStatus } from '@/lib/absence/form';
import type { AbsenceReport } from '@/lib/absence/queries';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface AbsenceReportCardProps {
  report: AbsenceReport;
  /** The teacher's inbox names the child; a parent's own list does not need to. */
  showStudent?: boolean;
  actions?: ReactNode;
}

/** One absence report: dates, who it is about, the reason when the viewer may read it, and where it stands. */
export function AbsenceReportCard({ report, showStudent = false, actions }: AbsenceReportCardProps) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const format = useFormatCivilDate();
  const status = reportStatus(report.status);
  const accent = status === 'ACKNOWLEDGED' ? colors.success : status === 'DECLINED' ? colors.destructive : status === 'SUBMITTED' ? colors.warning : colors.mutedForeground;
  const dates = report.fromDate === report.toDate ? format(report.fromDate) : t('mobile.absence.range', { from: format(report.fromDate), to: format(report.toDate) });
  const reason = report.reasonCode && isReasonCode(report.reasonCode) ? t(`mobile.absence.reason.${report.reasonCode}`) : null;

  return (
    <Card>
      {showStudent && report.studentName ? <Text variant="heading">{report.studentName}</Text> : null}
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[2] }}>
        <Text variant="label" style={{ flex: 1 }}>
          {dates}
        </Text>
        <View
          accessible
          accessibilityLabel={t('mobile.absence.statusLabel', { status: status === 'UNKNOWN' ? report.status : t(`mobile.absence.status.${status}`) })}
          style={{ paddingHorizontal: spacing[3], paddingVertical: spacing[1], borderRadius: radii.full, borderWidth: 1, borderColor: accent }}>
          <Text variant="caption" style={{ color: accent }}>
            {status === 'UNKNOWN' ? report.status : t(`mobile.absence.status.${status}`)}
          </Text>
        </View>
      </View>
      {reason ? <Text>{reason}</Text> : null}
      {report.note ? <Text tone="muted">{report.note}</Text> : null}
      {report.decisionNote ? <Text variant="caption">{t('mobile.absence.decisionNote', { note: report.decisionNote })}</Text> : null}
      {actions}
    </Card>
  );
}
