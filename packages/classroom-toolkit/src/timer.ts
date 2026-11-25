/**
 * A countdown timer or stopwatch as plain state, so a screen can survive re-renders, backgrounding and clock
 * reads: the elapsed time is always `elapsedBeforeMs` plus the time since `startedAt`. All times are epoch ms
 * (inject `now`, never read the clock in here).
 */
export interface TimerState {
  /** Countdown length; null for a stopwatch. */
  readonly durationMs: number | null;
  readonly elapsedBeforeMs: number;
  /** Set while running. */
  readonly startedAt: number | null;
}

export const countdown = (durationMs: number): TimerState => ({
  durationMs: Math.max(0, Math.floor(durationMs)),
  elapsedBeforeMs: 0,
  startedAt: null,
});

export const stopwatch = (): TimerState => ({ durationMs: null, elapsedBeforeMs: 0, startedAt: null });

export function elapsedMs(state: TimerState, now: number): number {
  const running = state.startedAt === null ? 0 : Math.max(0, now - state.startedAt);
  const total = state.elapsedBeforeMs + running;
  return state.durationMs === null ? total : Math.min(total, state.durationMs);
}

/** Time left of a countdown (0 once it ran out); null for a stopwatch. */
export function remainingMs(state: TimerState, now: number): number | null {
  return state.durationMs === null ? null : Math.max(0, state.durationMs - elapsedMs(state, now));
}

export const isRunning = (state: TimerState): boolean => state.startedAt !== null;

export const isFinished = (state: TimerState, now: number): boolean =>
  state.durationMs !== null && elapsedMs(state, now) >= state.durationMs;

export function start(state: TimerState, now: number): TimerState {
  if (state.startedAt !== null || isFinished(state, now)) return state;
  return { ...state, startedAt: now };
}

export function pause(state: TimerState, now: number): TimerState {
  if (state.startedAt === null) return state;
  return { ...state, elapsedBeforeMs: elapsedMs(state, now), startedAt: null };
}

export const reset = (state: TimerState): TimerState => ({ ...state, elapsedBeforeMs: 0, startedAt: null });

/** Adds (or, negative, removes) time on a countdown: the "+1 min" button. Never below what already elapsed. */
export function addTime(state: TimerState, deltaMs: number, now: number): TimerState {
  if (state.durationMs === null) return state;
  return { ...state, durationMs: Math.max(elapsedMs(state, now), state.durationMs + Math.floor(deltaMs)) };
}

/** "m:ss", or "h:mm:ss" from one hour; countdowns round up so "0:00" means finished. */
export function formatClock(ms: number, roundUp = false): string {
  const clamped = Math.max(0, ms);
  const totalSeconds = roundUp ? Math.ceil(clamped / 1000) : Math.floor(clamped / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}
