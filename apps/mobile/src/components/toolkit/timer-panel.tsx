import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, AppState, Modal, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import {
  addTime,
  countdown,
  elapsedMs,
  formatClock,
  isFinished,
  isRunning,
  pause,
  remainingMs,
  reset,
  start,
  stopwatch,
  type TimerState,
} from '@school/classroom-toolkit';

import { Button, SegmentedControl, Text } from '@/components/ui';
import { playFeedback } from '@/lib/points/feedback';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { useReduceMotion } from '@/lib/use-reduce-motion';

type Mode = 'timer' | 'stopwatch';

const PRESET_MINUTES = [1, 2, 3, 5, 10, 15] as const;
const KEEP_AWAKE_TAG = 'classroom-timer';

/**
 * Countdown timer and stopwatch. The time is never counted by ticks: it is always derived from the clock
 * through `@school/classroom-toolkit` (elapsed = time before + now - start), so it stays right when the app
 * is in the background, the phone sleeps or the screen re-renders.
 */
export function TimerPanel() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const reduceMotion = useReduceMotion();
  const [mode, setMode] = useState<Mode>('timer');
  const [minutes, setMinutes] = useState<number>(5);
  const [state, setState] = useState<TimerState>(() => countdown(5 * 60_000));
  const [now, setNow] = useState(() => Date.now());
  const [big, setBig] = useState(false);
  const announced = useRef(false);

  const running = isRunning(state);
  const finished = isFinished(state, now);

  // Tick only while it runs; the displayed value comes from the clock, so a late tick is harmless.
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), 200);
    const appState = AppState.addEventListener('change', (status) => {
      if (status === 'active') setNow(Date.now());
    });
    return () => {
      clearInterval(timer);
      appState.remove();
    };
  }, [running]);

  // Keep the screen on while it runs, so a class can watch it.
  useEffect(() => {
    if (!running) return;
    void activateKeepAwakeAsync(KEEP_AWAKE_TAG).catch(() => {});
    return () => {
      void deactivateKeepAwake(KEEP_AWAKE_TAG).catch(() => {});
    };
  }, [running]);

  // A countdown that ran out: stop it (the display stays at 0:00), chime once, tell screen readers.
  useEffect(() => {
    if (!finished || state.durationMs === null) {
      announced.current = false;
      return;
    }
    if (announced.current) return;
    announced.current = true;
    setState((current) => pause(current, Date.now()));
    playFeedback('timerDone', reduceMotion);
    void AccessibilityInfo.announceForAccessibility(t('mobile.toolkit.timer.finished'));
  }, [finished, state.durationMs, reduceMotion, t]);

  const choose = useCallback((next: Mode, nextMinutes: number) => {
    setMode(next);
    setMinutes(nextMinutes);
    setState(next === 'timer' ? countdown(nextMinutes * 60_000) : stopwatch());
    setNow(Date.now());
  }, []);

  const toggle = useCallback(() => {
    const at = Date.now();
    setNow(at);
    setState((current) => (isRunning(current) ? pause(current, at) : start(current, at)));
  }, []);

  const clockText =
    state.durationMs === null ? formatClock(elapsedMs(state, now)) : formatClock(remainingMs(state, now) ?? 0, true);
  const clockLabel = finished ? t('mobile.toolkit.timer.finished') : t('mobile.toolkit.timer.clockLabel', { time: clockText });

  return (
    <View style={{ gap: spacing[4] }}>
      <SegmentedControl<Mode>
        accessibilityLabel={t('mobile.toolkit.timer.modeLabel')}
        value={mode}
        onChange={(next) => choose(next, minutes)}
        options={[
          { value: 'timer', label: t('mobile.toolkit.timer.countdown') },
          { value: 'stopwatch', label: t('mobile.toolkit.timer.stopwatch') },
        ]}
      />

      <Clock text={clockText} label={clockLabel} finished={finished} running={running} onPress={() => setBig(true)} />

      {mode === 'timer' ? (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
          {PRESET_MINUTES.map((preset) => (
            <Chip
              key={preset}
              label={t('mobile.toolkit.timer.minutes', { minutes: preset })}
              selected={preset === minutes && state.durationMs === preset * 60_000}
              onPress={() => choose('timer', preset)}
            />
          ))}
        </View>
      ) : null}

      <View style={{ gap: spacing[3] }}>
        <Button
          label={running ? t('mobile.toolkit.timer.pause') : finished ? t('mobile.toolkit.timer.reset') : t('mobile.toolkit.timer.start')}
          onPress={finished ? () => setState((current) => reset(current)) : toggle}
        />
        <View style={{ flexDirection: 'row', gap: spacing[3] }}>
          <View style={{ flex: 1 }}>
            <Button variant="ghost" label={t('mobile.toolkit.timer.reset')} onPress={() => setState((current) => reset(current))} />
          </View>
          {mode === 'timer' ? (
            <View style={{ flex: 1 }}>
              <Button
                variant="ghost"
                label={t('mobile.toolkit.timer.addMinute')}
                onPress={() => {
                  const at = Date.now();
                  setNow(at);
                  setState((current) => addTime(current, 60_000, at));
                }}
              />
            </View>
          ) : null}
        </View>
        <Button variant="secondary" label={t('mobile.toolkit.timer.bigClock')} onPress={() => setBig(true)} />
      </View>

      <BigClock
        visible={big}
        onClose={() => setBig(false)}
        text={clockText}
        label={clockLabel}
        running={running}
        finished={finished}
        onToggle={finished ? () => setState((current) => reset(current)) : toggle}
      />
    </View>
  );
}

function Chip({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      accessibilityLabel={label}
      onPress={onPress}
      style={{
        minHeight: minTouchTarget,
        minWidth: minTouchTarget + 12,
        paddingHorizontal: spacing[4],
        borderRadius: radii.full,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: selected ? colors.primary : colors.muted,
      }}>
      <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
        {label}
      </Text>
    </Pressable>
  );
}

function Clock({
  text,
  label,
  finished,
  running,
  onPress,
}: {
  text: string;
  label: string;
  finished: boolean;
  running: boolean;
  onPress: () => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="timer"
      accessibilityLabel={label}
      accessibilityHint={t('mobile.toolkit.timer.bigClockHint')}
      onPress={onPress}
      style={{
        paddingVertical: spacing[8],
        borderRadius: radii['2xl'],
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: finished ? colors.secondary : colors.card,
        borderWidth: 3,
        borderColor: running ? colors.primary : colors.border,
      }}>
      <Text
        variant="title"
        allowFontScaling={false}
        adjustsFontSizeToFit
        numberOfLines={1}
        style={{ fontSize: 76, lineHeight: 88, fontVariant: ['tabular-nums'], color: finished ? colors.secondaryForeground : colors.foreground }}>
        {text}
      </Text>
    </Pressable>
  );
}

function BigClock({
  visible,
  onClose,
  text,
  label,
  running,
  finished,
  onToggle,
}: {
  visible: boolean;
  onClose: () => void;
  text: string;
  label: string;
  running: boolean;
  finished: boolean;
  onToggle: () => void;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} animationType="fade" onRequestClose={onClose} supportedOrientations={['portrait', 'landscape']}>
      <View
        style={{
          flex: 1,
          backgroundColor: finished ? colors.secondary : colors.background,
          paddingTop: insets.top + spacing[4],
          paddingBottom: insets.bottom + spacing[4],
          paddingHorizontal: spacing[5],
          gap: spacing[4],
        }}>
        <Pressable
          accessibilityRole="timer"
          accessibilityLabel={label}
          onPress={onToggle}
          style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <Text
            variant="title"
            allowFontScaling={false}
            adjustsFontSizeToFit
            numberOfLines={1}
            style={{
              fontSize: 220,
              lineHeight: 250,
              fontVariant: ['tabular-nums'],
              color: finished ? colors.secondaryForeground : colors.foreground,
            }}>
            {text}
          </Text>
        </Pressable>
        <View style={{ flexDirection: 'row', gap: spacing[3] }}>
          <View style={{ flex: 1 }}>
            <Button
              label={running ? t('mobile.toolkit.timer.pause') : finished ? t('mobile.toolkit.timer.reset') : t('mobile.toolkit.timer.start')}
              onPress={onToggle}
            />
          </View>
          <View style={{ flex: 1 }}>
            <Button variant="ghost" label={t('common.done')} onPress={onClose} />
          </View>
        </View>
      </View>
    </Modal>
  );
}
