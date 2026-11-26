"use client";

import { useSyncExternalStore } from "react";

/**
 * Whether the live connection is up. The doorbell hook sets it; message queries poll while it is down. A tiny external
 * store (no React context needed: the socket lives in the app layout, the readers deeper down).
 */
let up = false;
const listeners = new Set<() => void>();

export function setSocketUp(value: boolean) {
  if (up === value) return;
  up = value;
  for (const l of listeners) l();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useSocketUp(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => up,
    () => false,
  );
}
