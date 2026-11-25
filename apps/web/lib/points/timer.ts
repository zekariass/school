import { elapsedMs, formatClock, isFinished, isRunning, remainingMs, type TimerState } from "@school/classroom-toolkit";

/** Countdown presets in minutes. */
export const TIMER_PRESETS = [1, 2, 5, 10, 15] as const;

export const MAX_TIMER_MINUTES = 180;

/** A custom countdown typed as minutes (whole or with a decimal point), 1 second to 3 hours; null when invalid. */
export function parseMinutes(text: string): number | null {
  const value = Number(text.trim().replace(",", "."));
  if (!Number.isFinite(value) || value <= 0 || value > MAX_TIMER_MINUTES) return null;
  return Math.round(value * 60_000);
}

export interface ClockView {
  /** The text of the big clock: remaining time of a countdown (rounded up), elapsed time of a stopwatch. */
  text: string;
  running: boolean;
  finished: boolean;
}

/** What the clock shows at `now` (injected: the toolkit never reads the clock itself). */
export function clockView(state: TimerState, now: number): ClockView {
  const finished = isFinished(state, now);
  const remaining = remainingMs(state, now);
  return {
    text: remaining === null ? formatClock(elapsedMs(state, now)) : formatClock(remaining, true),
    running: isRunning(state) && !finished,
    finished,
  };
}
