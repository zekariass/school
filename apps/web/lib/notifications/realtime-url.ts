export const DEFAULT_REALTIME_URL = "ws://localhost:8180/ws";

/**
 * The gateway's WebSocket URL: NEXT_PUBLIC_REALTIME_URL (fixed at build time), else the local default.
 * Only ws:// and wss:// URLs are accepted; anything else falls back to the default.
 */
export function resolveRealtimeUrl(configured: string | undefined): string {
  const value = configured?.trim();
  if (!value) return DEFAULT_REALTIME_URL;
  try {
    const url = new URL(value);
    return url.protocol === "ws:" || url.protocol === "wss:" ? value : DEFAULT_REALTIME_URL;
  } catch {
    return DEFAULT_REALTIME_URL;
  }
}
