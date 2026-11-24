"use client";

import { useRealtime } from "./use-realtime";

/** Opens the live notification connection for the signed-in area. Renders nothing. */
export function RealtimeMount() {
  useRealtime();
  return null;
}
