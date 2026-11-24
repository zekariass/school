"use client";

import { useCallback, useSyncExternalStore } from "react";

const listeners = new Map<string, Set<() => void>>();
/** Fallback when localStorage throws, so toggles still work for the session. */
const memory = new Map<string, string>();

function subscribe(key: string, onChange: () => void) {
  let set = listeners.get(key);
  if (!set) listeners.set(key, (set = new Set()));
  set.add(onChange);
  const onStorage = (event: StorageEvent) => {
    if (event.key === key || event.key === null) onChange();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    set.delete(onChange);
    window.removeEventListener("storage", onStorage);
  };
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return memory.get(key) ?? null;
  }
}

/**
 * Raw localStorage value as external store: `null` on the server and during
 * hydration (so markup matches), the stored value afterwards, in sync across
 * components and tabs. Storage may be unavailable (private mode), hence the guards.
 */
export function useLocalStorageValue(key: string): [string | null, (value: string) => void] {
  const value = useSyncExternalStore(
    useCallback((onChange) => subscribe(key, onChange), [key]),
    () => read(key),
    () => null,
  );
  const set = useCallback(
    (next: string) => {
      try {
        localStorage.setItem(key, next);
      } catch {
        memory.set(key, next); // storage unavailable: lasts for this session only
      }
      listeners.get(key)?.forEach((fn) => fn());
    },
    [key],
  );
  return [value, set];
}
