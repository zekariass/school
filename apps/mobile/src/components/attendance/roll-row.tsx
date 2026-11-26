import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { memo } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { Text } from '@/components/ui';
import type { EffectiveStudent } from '@/lib/attendance/roll-model';
import { ATTENDANCE_STATUSES, type AttendanceStatus } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { styleOf } from './status-style';

export interface RollRowProps {
  student: EffectiveStudent;
  /** Taps do nothing (a locked or stale roll). */
  readOnly: boolean;
  onTap: (student: EffectiveStudent) => void;
  onDetails: (student: EffectiveStudent) => void;
  onChoose: (student: EffectiveStudent, status: AttendanceStatus) => void;
}

/**
 * One student of the roll: a big, easy target. A tap moves to the next status, a long press (or the "..." button)
 * opens the details. The status is a symbol plus a word, never a colour alone.
 */
export const RollRow = memo(function RollRow({ student, readOnly, onTap, onDetails, onChoose }: RollRowProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const status = styleOf(student.status);
  const accent = status.accent(colors);
  const statusLabel = t(status.labelKey);

  let hint: string | null = null;
  if (student.status === null && student.absence) {
    const reason = student.absence.reasonCode && isKnownReason(student.absence.reasonCode) ? t(`mobile.absence.reason.${student.absence.reasonCode}`) : null;
    hint =
      student.absence.status === 'ACKNOWLEDGED'
        ? reason
          ? t('mobile.attendance.hint.parentReported', { reason })
          : t('mobile.attendance.hint.parentReportedNoReason')
        : t('mobile.attendance.hint.parentWaiting');
  } else if (student.status === 'LATE' && student.minutesLate) {
    hint = t('mobile.attendance.hint.minutesLate', { minutes: student.minutesLate });
  }

  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={t('mobile.attendance.row.label', { name: student.name, status: statusLabel })}
        accessibilityHint={readOnly ? undefined : t('mobile.attendance.row.hint')}
        accessibilityState={{ disabled: readOnly }}
        accessibilityActions={readOnly ? [] : [...ATTENDANCE_STATUSES.map((value) => ({ name: value, label: t(`mobile.attendance.status.${value}`) })), { name: 'details', label: t('mobile.attendance.row.details') }]}
        onAccessibilityAction={(event) => {
          const name = event.nativeEvent.actionName;
          if (name === 'details') onDetails(student);
          else if ((ATTENDANCE_STATUSES as readonly string[]).includes(name)) onChoose(student, name as AttendanceStatus);
        }}
        disabled={readOnly}
        onPress={() => onTap(student)}
        onLongPress={() => onDetails(student)}
        delayLongPress={350}
        style={({ pressed }) => ({
          flex: 1,
          minHeight: 72,
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing[3],
          paddingVertical: spacing[2],
          paddingHorizontal: spacing[3],
          borderRadius: radii.xl,
          borderWidth: student.queued ? 2 : 1,
          borderColor: student.queued ? colors.warning : colors.border,
          backgroundColor: pressed ? colors.muted : colors.card,
        })}>
        <Avatar
          mediaId={student.avatarMediaId}
          name={student.name}
          seed={student.studentId}
          config={student.avatarColor ? { color: student.avatarColor } : null}
          size={48}
          maxInitials={1}
          accessibilityLabel={student.name}
        />
        <View style={{ flex: 1, gap: 2 }}>
          <Text variant="label" numberOfLines={2}>
            {student.name}
          </Text>
          {hint ? (
            <Text variant="caption" numberOfLines={2}>
              {hint}
            </Text>
          ) : null}
        </View>
        <View style={{ alignItems: 'center', gap: 2, minWidth: 64 }} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
          <SymbolView name={{ ...status.symbol, web: status.symbol.android } as SymbolViewProps['name']} size={30} tintColor={accent} />
          <Text variant="caption" numberOfLines={1} style={{ color: accent }}>
            {statusLabel}
          </Text>
        </View>
      </Pressable>
      {readOnly ? null : (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('mobile.attendance.row.detailsFor', { name: student.name })}
          hitSlop={4}
          onPress={() => onDetails(student)}
          style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
          <SymbolView name={{ ios: 'ellipsis.circle', android: 'more_horiz', web: 'more_horiz' } as SymbolViewProps['name']} size={26} tintColor={colors.mutedForeground} />
        </Pressable>
      )}
    </View>
  );
});

const REASONS = ['ILLNESS', 'FAMILY', 'APPOINTMENT', 'OTHER'] as const;
function isKnownReason(code: string): code is (typeof REASONS)[number] {
  return (REASONS as readonly string[]).includes(code);
}
