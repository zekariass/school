import type { SemanticColors } from '@school/design-tokens';

import type { AttendanceStatus } from '@/lib/sync/types';

/** How a status looks: a symbol (never colour alone), an accent from the design tokens and the label key. */
export interface StatusStyle {
  readonly symbol: { ios: string; android: string };
  readonly labelKey: `mobile.attendance.status.${AttendanceStatus | 'NONE'}`;
  readonly accent: (colors: SemanticColors) => string;
}

export const STATUS_STYLE: Readonly<Record<AttendanceStatus | 'NONE', StatusStyle>> = {
  PRESENT: { symbol: { ios: 'checkmark.circle.fill', android: 'check_circle' }, labelKey: 'mobile.attendance.status.PRESENT', accent: (c) => c.success },
  ABSENT: { symbol: { ios: 'xmark.circle.fill', android: 'cancel' }, labelKey: 'mobile.attendance.status.ABSENT', accent: (c) => c.destructive },
  LATE: { symbol: { ios: 'clock.fill', android: 'schedule' }, labelKey: 'mobile.attendance.status.LATE', accent: (c) => c.warning },
  EXCUSED: { symbol: { ios: 'envelope.circle.fill', android: 'mark_email_read' }, labelKey: 'mobile.attendance.status.EXCUSED', accent: (c) => c.info },
  LEFT_EARLY: { symbol: { ios: 'figure.walk.circle.fill', android: 'directions_walk' }, labelKey: 'mobile.attendance.status.LEFT_EARLY', accent: (c) => c.accentForeground },
  NONE: { symbol: { ios: 'circle.dashed', android: 'radio_button_unchecked' }, labelKey: 'mobile.attendance.status.NONE', accent: (c) => c.mutedForeground },
};

export const styleOf = (status: AttendanceStatus | null): StatusStyle => STATUS_STYLE[status ?? 'NONE'];
