import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { makeGroups } from '@school/classroom-toolkit';

import { Avatar } from '@/components/avatar';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, SegmentedControl, Text } from '@/components/ui';
import type { RosterEntry } from '@/lib/people/roster';
import { useSaveGroupSet } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

type By = 'count' | 'size';

/** Group maker: split the class into a number of groups or groups of a size, shuffle again, save the set. */
export function GroupsPanel({
  students,
  schoolId,
  classId,
}: {
  students: readonly RosterEntry[];
  schoolId: string | undefined;
  classId: string;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [by, setBy] = useState<By>('count');
  const [value, setValue] = useState(4);
  const [groups, setGroups] = useState<string[][]>([]);
  const [saved, setSaved] = useState(false);
  const save = useSaveGroupSet(schoolId, classId);

  const max = Math.max(1, students.length);
  const amount = Math.min(Math.max(1, value), max);

  function make() {
    setSaved(false);
    save.reset();
    setGroups(makeGroups(students.map((student) => student.id), by === 'count' ? { groupCount: amount } : { groupSize: amount }));
  }

  async function saveSet() {
    try {
      await save.mutateAsync(
        groups.map((ids, index) => ({ name: t('mobile.toolkit.groups.groupName', { number: index + 1 }), studentIds: ids })),
      );
      setSaved(true);
    } catch {
      // The banner below shows the API's own message.
    }
  }

  return (
    <View style={{ gap: spacing[4] }}>
      <SegmentedControl<By>
        accessibilityLabel={t('mobile.toolkit.groups.byLabel')}
        value={by}
        onChange={(next) => {
          setBy(next);
          setValue(next === 'count' ? 4 : 3);
        }}
        options={[
          { value: 'count', label: t('mobile.toolkit.groups.byCount') },
          { value: 'size', label: t('mobile.toolkit.groups.bySize') },
        ]}
      />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing[5] }}>
        <StepButton label={t('mobile.toolkit.groups.less')} symbol="−" disabled={amount <= 1} onPress={() => setValue(amount - 1)} />
        <View style={{ alignItems: 'center', minWidth: 120 }}>
          <Text variant="title" accessibilityLabel={by === 'count' ? t('mobile.toolkit.groups.countValue', { value: amount }) : t('mobile.toolkit.groups.sizeValue', { value: amount })}>
            {amount}
          </Text>
          <Text tone="muted" variant="caption">
            {by === 'count' ? t('mobile.toolkit.groups.countCaption') : t('mobile.toolkit.groups.sizeCaption')}
          </Text>
        </View>
        <StepButton label={t('mobile.toolkit.groups.more')} symbol="+" disabled={amount >= max} onPress={() => setValue(amount + 1)} />
      </View>
      <Button
        label={groups.length > 0 ? t('mobile.toolkit.groups.again') : t('mobile.toolkit.groups.make')}
        disabled={students.length === 0}
        onPress={make}
      />

      {groups.map((ids, index) => (
        <Card key={ids.join('-')} accessibilityLabel={t('mobile.toolkit.groups.groupName', { number: index + 1 })}>
          <Text variant="heading">{t('mobile.toolkit.groups.groupName', { number: index + 1 })}</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[3] }}>
            {ids.map((id) => {
              const student = students.find((candidate) => candidate.id === id);
              if (!student) return null;
              return (
                <View key={id} style={{ alignItems: 'center', width: 72, gap: 4 }}>
                  <Avatar mediaId={student.avatarMediaId} name={student.name} seed={student.id} config={student.avatarConfig} size={48} maxInitials={1} />
                  <Text variant="caption" numberOfLines={1} style={{ textAlign: 'center' }}>
                    {student.firstName}
                  </Text>
                </View>
              );
            })}
          </View>
        </Card>
      ))}

      {groups.length > 0 ? (
        <View style={{ gap: spacing[2] }}>
          {save.isError ? (
            <ProblemBanner
              error={save.error}
              fallbackTitle={t('mobile.toolkit.groups.saveFailedTitle')}
              fallbackBody={t('mobile.toolkit.groups.saveFailedBody')}
            />
          ) : null}
          {saved ? (
            <Text tone="primary" accessibilityLiveRegion="polite">
              {t('mobile.toolkit.groups.saved')}
            </Text>
          ) : null}
          <Button variant="secondary" label={t('mobile.toolkit.groups.save')} loading={save.isPending} disabled={saved} onPress={() => void saveSet()} />
          <Text variant="caption" tone="muted">
            {t('mobile.toolkit.groups.saveHint')}
          </Text>
        </View>
      ) : null}
    </View>
  );
}

function StepButton({ label, symbol, disabled, onPress }: { label: string; symbol: string; disabled: boolean; onPress: () => void }) {
  const { colors, spacing } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={{
        width: 56,
        height: 56,
        borderRadius: 28,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: colors.muted,
        opacity: disabled ? 0.4 : 1,
        marginHorizontal: spacing[1],
      }}>
      <Text variant="title" allowFontScaling={false}>
        {symbol}
      </Text>
    </Pressable>
  );
}
