import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { Button, Sheet, Text, TextField } from '@/components/ui';
import type { EffectiveStudent } from '@/lib/attendance/roll-model';
import { ATTENDANCE_STATUSES, type AttendanceStatus } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { styleOf } from './status-style';

export interface StudentSheetProps {
  student: EffectiveStudent | null;
  onClose: () => void;
  onSave: (student: EffectiveStudent, status: AttendanceStatus, extra: { minutesLate: number | null; note: string }) => void;
}

const QUICK_MINUTES = [5, 10, 15, 30] as const;
export const NOTE_MAX_LENGTH = 200;

/** Details of one student's mark: a chosen status, minutes late and an optional short note. */
export function StudentSheet({ student, onClose, onSave }: StudentSheetProps) {
  // Keyed by student: opening another one starts from that student's own mark.
  return student ? <StudentSheetBody key={student.studentId} student={student} onClose={onClose} onSave={onSave} /> : null;
}

function StudentSheetBody({ student, onClose, onSave }: StudentSheetProps & { student: EffectiveStudent }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const [status, setStatus] = useState<AttendanceStatus>(student.status ?? student.suggestedStatus ?? 'PRESENT');
  const [minutes, setMinutes] = useState(student.minutesLate ? String(student.minutesLate) : '');
  // A note is never read back from the phone: it starts empty.
  const [note, setNote] = useState('');

  const parsed = Number.parseInt(minutes, 10);
  const minutesLate = status === 'LATE' && Number.isInteger(parsed) && parsed >= 1 && parsed <= 600 ? parsed : null;
  const minutesInvalid = status === 'LATE' && minutes.trim().length > 0 && minutesLate === null;

  return (
    <Sheet visible onClose={onClose} title={student.name}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
        <Avatar
          mediaId={student.avatarMediaId}
          name={student.name}
          seed={student.studentId}
          config={student.avatarColor ? { color: student.avatarColor } : null}
          size={48}
          maxInitials={1}
        />
        <Text tone="muted" style={{ flex: 1 }}>
          {t('mobile.attendance.sheet.status')}
        </Text>
      </View>

      <View accessibilityRole="radiogroup" accessibilityLabel={t('mobile.attendance.sheet.status')} style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {ATTENDANCE_STATUSES.map((value) => {
          const look = styleOf(value);
          const accent = look.accent(colors);
          const selected = value === status;
          return (
            <Pressable
              key={value}
              accessibilityRole="radio"
              accessibilityState={{ selected }}
              accessibilityLabel={t(look.labelKey)}
              onPress={() => setStatus(value)}
              style={{
                minHeight: minTouchTarget,
                flexDirection: 'row',
                alignItems: 'center',
                gap: spacing[2],
                paddingHorizontal: spacing[4],
                borderRadius: radii.full,
                borderWidth: 2,
                borderColor: selected ? accent : colors.border,
                backgroundColor: selected ? colors.muted : colors.card,
              }}>
              <SymbolView name={{ ...look.symbol, web: look.symbol.android } as SymbolViewProps['name']} size={22} tintColor={accent} />
              <Text variant="label">{t(look.labelKey)}</Text>
            </Pressable>
          );
        })}
      </View>

      {status === 'LATE' ? (
        <View style={{ gap: spacing[2] }}>
          <Text variant="label">{t('mobile.attendance.sheet.minutesLate')}</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
            {QUICK_MINUTES.map((value) => (
              <Pressable
                key={value}
                accessibilityRole="button"
                accessibilityState={{ selected: minutes === String(value) }}
                accessibilityLabel={t('mobile.attendance.sheet.minutesOption', { minutes: value })}
                onPress={() => setMinutes(String(value))}
                style={{
                  minHeight: minTouchTarget,
                  minWidth: minTouchTarget + 8,
                  alignItems: 'center',
                  justifyContent: 'center',
                  paddingHorizontal: spacing[3],
                  borderRadius: radii.full,
                  borderWidth: 2,
                  borderColor: minutes === String(value) ? colors.warning : colors.border,
                  backgroundColor: colors.card,
                }}>
                <Text variant="label">{value}</Text>
              </Pressable>
            ))}
          </View>
          <TextField
            label={t('mobile.attendance.sheet.minutesCustom')}
            value={minutes}
            onChangeText={(text) => setMinutes(text.replace(/[^0-9]/g, '').slice(0, 3))}
            keyboardType="number-pad"
            maxLength={3}
            error={minutesInvalid ? t('mobile.attendance.sheet.minutesError') : undefined}
          />
        </View>
      ) : null}

      <TextField
        label={t('mobile.attendance.sheet.note')}
        hint={t('mobile.attendance.sheet.noteHint')}
        value={note}
        onChangeText={setNote}
        maxLength={NOTE_MAX_LENGTH}
        multiline
        autoCapitalize="sentences"
      />

      <Button
        label={t('common.done')}
        disabled={minutesInvalid}
        onPress={() => {
          onSave(student, status, { minutesLate, note });
          onClose();
        }}
      />
    </Sheet>
  );
}
