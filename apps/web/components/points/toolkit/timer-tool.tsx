"use client";

import {
  addTime,
  countdown,
  isRunning,
  pause,
  reset,
  start,
  stopwatch,
  type TimerState,
} from "@school/classroom-toolkit";
import { MaximizeIcon, MinimizeIcon, PauseIcon, PlayIcon, PlusIcon, RotateCcwIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { clockView, parseMinutes, TIMER_PRESETS } from "@/lib/points/timer";
import { cn } from "@/lib/utils";
import { usePointsFeedback } from "../points-provider";

type Mode = "countdown" | "stopwatch";

/** Re-reads the clock while `active`; the toolkit state itself never reads the clock. */
function useNow(active: boolean, intervalMs = 200): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [active, intervalMs]);
  return now;
}

/**
 * Countdown timer and stopwatch. The state is `@school/classroom-toolkit`'s (elapsed time before + time since
 * start), so a re-render, a background tab or a pause never drifts. "Big clock" fills the screen for the projector.
 */
export function TimerTool() {
  const t = useTranslations("school.toolkit.timer");
  const { play, announce } = usePointsFeedback();
  const [mode, setMode] = useState<Mode>("countdown");
  const [state, setState] = useState<TimerState>(() => countdown(5 * 60_000));
  const [custom, setCustom] = useState("");
  const [customError, setCustomError] = useState(false);
  const [chime, setChime] = useState(true);
  const [big, setBig] = useState(false);
  const [clockNow, setClockNow] = useState(() => Date.now());
  const ticking = isRunning(state);
  const polled = useNow(ticking);
  // A click reads the clock itself (so a paused timer shows the right time at once); the interval keeps it fresh.
  const now = ticking ? Math.max(polled, clockNow) : clockNow;
  const view = clockView(state, now);

  const finished = view.finished;
  useEffect(() => {
    if (!finished) return;
    if (chime) play("alarm");
    announce(t("finished"));
  }, [finished, chime, play, announce, t]);

  useEffect(() => {
    if (!big) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setBig(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [big]);

  function apply(next: (current: TimerState, at: number) => TimerState) {
    const at = Date.now();
    setClockNow(at);
    setState((current) => next(current, at));
  }

  function choose(nextMode: Mode, ms?: number) {
    setMode(nextMode);
    setClockNow(Date.now());
    setState(nextMode === "countdown" ? countdown(ms ?? 5 * 60_000) : stopwatch());
  }

  const controls = (
    <div className="flex flex-wrap items-center justify-center gap-3">
      {ticking && !finished ? (
        <Button size={big ? "lg" : "default"} onClick={() => apply((s, at) => pause(s, at))} data-testid="timer-pause">
          <PauseIcon className="size-5" aria-hidden />
          {t("pause")}
        </Button>
      ) : (
        <Button size={big ? "lg" : "default"} onClick={() => apply((s, at) => start(s, at))} disabled={finished} data-testid="timer-start">
          <PlayIcon className="size-5" aria-hidden />
          {state.elapsedBeforeMs > 0 ? t("resume") : t("start")}
        </Button>
      )}
      <Button size={big ? "lg" : "default"} variant="outline" onClick={() => apply((s) => reset(s))} data-testid="timer-reset">
        <RotateCcwIcon className="size-5" aria-hidden />
        {t("reset")}
      </Button>
      {mode === "countdown" ? (
        <Button size={big ? "lg" : "default"} variant="outline" onClick={() => apply((s, at) => addTime(s, 60_000, at))} data-testid="timer-plus">
          <PlusIcon className="size-5" aria-hidden />
          {t("addMinute")}
        </Button>
      ) : null}
    </div>
  );

  const clock = (
    <p
      role="timer"
      aria-label={t(mode === "countdown" ? "clockLabelCountdown" : "clockLabelStopwatch")}
      data-testid="timer-clock"
      className={cn(
        "text-center font-extrabold tabular-nums",
        big ? "text-[clamp(6rem,28vw,24rem)] leading-none" : "text-7xl sm:text-8xl",
        view.finished && "text-destructive motion-safe:animate-pulse",
      )}
    >
      {view.text}
    </p>
  );

  if (big) {
    return (
      <div role="dialog" aria-modal="true" aria-label={t("bigClock")} className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-10 bg-background p-6" data-testid="big-clock">
        {clock}
        {view.finished ? <p className="text-3xl font-bold text-destructive">{t("finished")}</p> : null}
        {controls}
        <Button variant="ghost" onClick={() => setBig(false)} data-testid="big-clock-exit">
          <MinimizeIcon className="size-4" aria-hidden />
          {t("exitBig")}
        </Button>
      </div>
    );
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex gap-2" role="group" aria-label={t("mode")}>
            <Button variant={mode === "countdown" ? "default" : "outline"} aria-pressed={mode === "countdown"} onClick={() => choose("countdown")} data-testid="mode-countdown">
              {t("countdown")}
            </Button>
            <Button variant={mode === "stopwatch" ? "default" : "outline"} aria-pressed={mode === "stopwatch"} onClick={() => choose("stopwatch")} data-testid="mode-stopwatch">
              {t("stopwatch")}
            </Button>
          </div>
          <Button variant="outline" onClick={() => setBig(true)} data-testid="big-clock-open">
            <MaximizeIcon className="size-4" aria-hidden />
            {t("bigClock")}
          </Button>
        </div>

        {mode === "countdown" ? (
          <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("presets")}>
            {TIMER_PRESETS.map((minutes) => (
              <Button
                key={minutes}
                size="sm"
                variant={state.durationMs === minutes * 60_000 ? "default" : "outline"}
                onClick={() => choose("countdown", minutes * 60_000)}
              >
                {t("minutes", { count: minutes })}
              </Button>
            ))}
            <form
              className="flex items-center gap-2"
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                const ms = parseMinutes(custom);
                setCustomError(ms === null);
                if (ms !== null) choose("countdown", ms);
              }}
            >
              <label className="sr-only" htmlFor="timer-custom">
                {t("custom")}
              </label>
              <Input
                id="timer-custom"
                inputMode="decimal"
                placeholder={t("customPlaceholder")}
                value={custom}
                aria-invalid={customError || undefined}
                onChange={(e) => setCustom(e.target.value)}
                className="h-9 w-28"
              />
              <Button type="submit" size="sm" variant="outline">
                {t("set")}
              </Button>
            </form>
            {customError ? (
              <p role="alert" className="w-full text-sm font-medium text-destructive">
                {t("customInvalid")}
              </p>
            ) : null}
          </div>
        ) : null}

        {clock}
        {view.finished ? (
          <p role="status" className="text-center text-xl font-bold text-destructive">
            {t("finished")}
          </p>
        ) : null}
        {controls}

        {mode === "countdown" ? (
          <label className="flex items-center justify-center gap-2 text-sm">
            <input type="checkbox" className="size-5 accent-primary" checked={chime} onChange={(e) => setChime(e.target.checked)} />
            {t("chime")}
          </label>
        ) : null}
      </CardContent>
    </Card>
  );
}
