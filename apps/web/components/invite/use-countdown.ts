"use client";

import { useSyncExternalStore } from "react";
import { secondsUntil } from "@/lib/problem";

function subscribeToClock(onTick: () => void) {
  const id = window.setInterval(onTick, 1000);
  return () => window.clearInterval(id);
}

/** The current time, truncated to the second so the snapshot is stable between ticks. */
function readClock() {
  return Math.floor(Date.now() / 1000) * 1000;
}

/** Whole seconds until `deadline` (epoch ms, or null for none); re-renders every second. */
export function useSecondsLeft(deadline: number | null): number {
  const now = useSyncExternalStore(subscribeToClock, readClock, () => 0);
  return deadline === null ? 0 : secondsUntil(deadline, now);
}
