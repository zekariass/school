/**
 * The WebSocket endpoint of the API: `ws(s)://<host>/ws` from the API base URL
 * (http -> ws, https -> wss; path, query and trailing slashes of the base are dropped).
 */
export function realtimeUrl(apiBaseUrl: string): string {
  const url = new URL(apiBaseUrl.trim());
  const scheme = url.protocol === 'https:' || url.protocol === 'wss:' ? 'wss:' : 'ws:';
  return `${scheme}//${url.host}/ws`;
}
