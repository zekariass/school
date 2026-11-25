import { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { EMPTY_PICKER, pickNext, type PickerState } from '@school/classroom-toolkit';

import { Avatar } from '@/components/avatar';
import { Button, Card, Text } from '@/components/ui';
import type { RosterEntry } from '@/lib/people/roster';
import { playFeedback } from '@/lib/points/feedback';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { useReduceMotion } from '@/lib/use-reduce-motion';

const SHUFFLE_STEPS = 9;
const SHUFFLE_MS = 80;

/**
 * Random student picker: nobody is picked twice until everybody was (the rule lives in the toolkit package).
 * A short "shuffle" through the faces builds a little suspense, unless the OS asks for reduced motion.
 */
export function PickerPanel({ students }: { students: readonly RosterEntry[] }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const reduceMotion = useReduceMotion();
  const [state, setState] = useState<PickerState>(EMPTY_PICKER);
  const [shown, setShown] = useState<RosterEntry | null>(null);
  const [shuffling, setShuffling] = useState(false);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) clearInterval(timer.current);
    },
    [],
  );

  function pick() {
    if (shuffling) return;
    const ids = students.map((student) => student.id);
    const result = pickNext(ids, state);
    const chosen = students.find((student) => student.id === result.id) ?? null;
    if (!chosen) return;
    setState(result.state);

    const land = () => {
      setShown(chosen);
      setShuffling(false);
      playFeedback('pick', reduceMotion);
      void AccessibilityInfo.announceForAccessibility(chosen.name);
    };
    if (reduceMotion || students.length < 3) {
      land();
      return;
    }
    setShuffling(true);
    let step = 0;
    timer.current = setInterval(() => {
      step++;
      if (step >= SHUFFLE_STEPS) {
        if (timer.current) clearInterval(timer.current);
        timer.current = null;
        land();
        return;
      }
      setShown(students[Math.floor(Math.random() * students.length)] ?? null);
    }, SHUFFLE_MS);
  }

  function startOver() {
    setState(EMPTY_PICKER);
    setShown(null);
  }

  const total = students.length;
  const picked = Math.min(state.picked.length, total);

  return (
    <View style={{ gap: spacing[4] }}>
      <Card style={{ alignItems: 'center', minHeight: 280, justifyContent: 'center', gap: spacing[4] }}>
        {shown ? (
          <>
            <Avatar mediaId={shown.avatarMediaId} name={shown.name} seed={shown.id} config={shown.avatarConfig} size={160} maxInitials={1} />
            <Text variant="title" style={{ textAlign: 'center' }} accessibilityLiveRegion="polite">
              {shown.firstName}
            </Text>
          </>
        ) : (
          <Text tone="muted" style={{ textAlign: 'center' }}>
            {total === 0 ? t('mobile.toolkit.picker.noStudents') : t('mobile.toolkit.picker.prompt')}
          </Text>
        )}
      </Card>
      {total > 0 ? (
        <Text tone="muted" style={{ textAlign: 'center' }}>
          {t('mobile.toolkit.picker.progress', { picked, total })}
        </Text>
      ) : null}
      <Button
        label={shown ? t('mobile.toolkit.picker.pickNext') : t('mobile.toolkit.picker.pick')}
        disabled={total === 0 || shuffling}
        onPress={pick}
      />
      <Button variant="ghost" label={t('mobile.toolkit.picker.startOver')} disabled={state.picked.length === 0 && !shown} onPress={startOver} />
    </View>
  );
}
