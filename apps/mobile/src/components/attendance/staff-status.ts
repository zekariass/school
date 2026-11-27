import type { SemanticColors } from '@school/design-tokens';

import type { StaffStatus } from '@/lib/attendance/staff/model';

/** How a staff status looks: a symbol (never colour alone), an accent from the design tokens and the label key. */
export interface StaffStatusStyle {
  readonly symbol: { ios: string; android: string };
  readonly labelKey:
    | 'mobile.attendance.status.PRESENT'
    | 'mobile.attendance.status.ABSENT'
    | 'mobile.attendance.status.LATE'
    | 'mobile.attendance.status.EXCUSED'
    | 'mobile.attendance.staff.status.HALF_DAY'
    | 'mobile.attendance.staff.notMarked';
  readonly accent: (colors: SemanticColors) => string;
}

const STYLES: Readonly<Record<StaffStatus | 'NONE', StaffStatusStyle>> = {
  PRESENT: { symbol: { ios: 'checkmark.circle.fill', android: 'check_circle' }, labelKey: 'mobile.attendance.status.PRESENT', accent: (c) => c.success },
  ABSENT: { symbol: { ios: 'xmark.circle.fill', android: 'cancel' }, labelKey: 'mobile.attendance.status.ABSENT', accent: (c) => c.destructive },
  LATE: { symbol: { ios: 'clock.fill', android: 'schedule' }, labelKey: 'mobile.attendance.status.LATE', accent: (c) => c.warning },
  HALF_DAY: { symbol: { ios: 'circle.lefthalf.filled', android: 'contrast' }, labelKey: 'mobile.attendance.staff.status.HALF_DAY', accent: (c) => c.accentForeground },
  EXCUSED: { symbol: { ios: 'envelope.circle.fill', android: 'mark_email_read' }, labelKey: 'mobile.attendance.status.EXCUSED', accent: (c) => c.info },
  NONE: { symbol: { ios: 'circle.dashed', android: 'radio_button_unchecked' }, labelKey: 'mobile.attendance.staff.notMarked', accent: (c) => c.mutedForeground },
};

export const staffStatusStyle = (status: StaffStatus | null | undefined): StaffStatusStyle => STYLES[status ?? 'NONE'];
