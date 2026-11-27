import { Pressable, ScrollView, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import type { SubjectChoice } from '@/lib/attendance/period';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface PeriodPickerProps {
  subject: SubjectChoice;
  /** The name of the subject the roll has (or the edits waiting for it name). */
  subjectName: string | null;
  subjects: readonly { id: string; name: string }[];
  onSubject: (id: string | null) => void;
  groups: readonly { id: string; name: string }[];
  groupId: string | null;
  onGroup: (id: string | null) => void;
}

/**
 * What a period roll adds to the roll screen: the subject (chosen while the roll is new, then read-only: the roll
 * belongs to it) and, when the class has saved groups, the group whose members are shown (an elective).
 */
export function PeriodPicker({ subject, subjectName, subjects, onSubject, groups, groupId, onGroup }: PeriodPickerProps) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const showSubject = subject.applicable && (subject.locked ? subjectName !== null : subjects.length > 0);
  if (!showSubject && groups.length === 0) return null;

  return (
    <View style={{ gap: spacing[3] }}>
      {showSubject ? (
        subject.locked ? (
          <View accessible accessibilityLabel={t('mobile.attendance.period.subjectIs', { subject: subjectName ?? '' })} style={{ gap: spacing[1] }}>
            <Text variant="caption">{t('mobile.attendance.period.subject')}</Text>
            <Text variant="label">{subjectName}</Text>
          </View>
        ) : (
          <Chips
            label={t('mobile.attendance.period.subject')}
            hint={t('mobile.attendance.period.subjectHint')}
            options={[{ id: null, name: t('mobile.attendance.period.noSubject') }, ...subjects]}
            selected={subject.subjectId}
            onSelect={onSubject}
          />
        )
      ) : null}
      {groups.length > 0 ? (
        <Chips
          label={t('mobile.attendance.period.group')}
          hint={t('mobile.attendance.period.groupHint')}
          options={[{ id: null, name: t('mobile.attendance.period.wholeClass') }, ...groups]}
          selected={groupId}
          onSelect={onGroup}
        />
      ) : null}
    </View>
  );
}

function Chips({
  label,
  hint,
  options,
  selected,
  onSelect,
}: {
  label: string;
  hint: string;
  options: readonly { id: string | null; name: string }[];
  selected: string | null;
  onSelect: (id: string | null) => void;
}) {
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <View style={{ gap: spacing[1] }}>
      <Text variant="caption">{label}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: spacing[2] }} accessibilityRole="radiogroup" accessibilityLabel={label} accessibilityHint={hint}>
        {options.map((option) => {
          const on = option.id === selected;
          return (
            <Pressable
              key={option.id ?? 'none'}
              accessibilityRole="radio"
              accessibilityState={{ selected: on }}
              onPress={() => onSelect(option.id)}
              style={{
                minHeight: minTouchTarget,
                justifyContent: 'center',
                paddingHorizontal: spacing[4],
                borderRadius: radii.full,
                backgroundColor: on ? colors.primary : colors.muted,
              }}>
              <Text variant="label" style={{ color: on ? colors.primaryForeground : colors.foreground }}>
                {option.name}
              </Text>
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
}
