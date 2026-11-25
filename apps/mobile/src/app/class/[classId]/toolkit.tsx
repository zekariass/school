import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { GroupsPanel } from '@/components/toolkit/groups-panel';
import { PickerPanel } from '@/components/toolkit/picker-panel';
import { RoutinesPanel } from '@/components/toolkit/routines-panel';
import { TimerPanel } from '@/components/toolkit/timer-panel';
import { Screen, SegmentedControl } from '@/components/ui';
import { useMyClass, useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

type Tab = 'timer' | 'picker' | 'groups' | 'routines';

/**
 * The classroom toolkit: timer and stopwatch, random student picker, group maker and routines. Every panel
 * stays mounted while another is showing, so a running timer keeps going when the teacher picks a student.
 */
export default function Toolkit() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const roster = useRoster(cls?.schoolId, classId);
  const [tab, setTab] = useState<Tab>('timer');
  const students = roster.data ?? [];

  const hidden = (name: Tab) => ({ display: tab === name ? ('flex' as const) : ('none' as const) });

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[4] }}>
      <SegmentedControl<Tab>
        accessibilityLabel={t('mobile.toolkit.tabsLabel')}
        value={tab}
        onChange={setTab}
        options={[
          { value: 'timer', label: t('mobile.toolkit.tabs.timer') },
          { value: 'picker', label: t('mobile.toolkit.tabs.picker') },
          { value: 'groups', label: t('mobile.toolkit.tabs.groups') },
          { value: 'routines', label: t('mobile.toolkit.tabs.routines') },
        ]}
      />
      <View style={hidden('timer')}>
        <TimerPanel />
      </View>
      <View style={hidden('picker')}>
        <PickerPanel students={students} />
      </View>
      <View style={hidden('groups')}>
        <GroupsPanel students={students} schoolId={cls?.schoolId} classId={classId!} />
      </View>
      <View style={hidden('routines')}>
        <RoutinesPanel schoolId={cls?.schoolId} classId={classId!} />
      </View>
    </Screen>
  );
}
