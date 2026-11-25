import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * A class opened from the Classes tab: its roster, the student screens and the roster actions, as a
 * stack above the tabs (the tab bar would cover the action buttons).
 */
export default function ClassStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '' }}>
      <Stack.Screen
        name="[classId]/index"
        options={{ title: t('mobile.classes.classTitle'), headerLeft: () => <HeaderBack /> }}
      />
      <Stack.Screen name="[classId]/add-students" options={{ title: t('mobile.classes.add.title') }} />
      <Stack.Screen name="[classId]/new-student" options={{ title: t('mobile.classes.newStudent.title') }} />
      <Stack.Screen name="[classId]/student/[studentId]/index" options={{ title: t('mobile.classes.student.title') }} />
      <Stack.Screen
        name="[classId]/student/[studentId]/code"
        options={{ title: t('mobile.classes.code.title'), presentation: 'fullScreenModal', animation: 'slide_from_bottom' }}
      />
      <Stack.Screen
        name="[classId]/student/[studentId]/move"
        options={{ title: t('mobile.classes.move.title'), presentation: 'modal' }}
      />
    </Stack>
  );
}
