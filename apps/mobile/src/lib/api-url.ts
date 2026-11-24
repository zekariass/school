/** Port of the local backend (`npm run dev -- --filter=school-backend`). */
export const LOCAL_API_PORT = 8180;
export const FALLBACK_API_URL = `http://localhost:${LOCAL_API_PORT}`;

export interface ApiBaseUrlInput {
  /** `process.env.EXPO_PUBLIC_API_URL`. */
  envUrl?: string | null | undefined;
  /** `Constants.expoConfig?.hostUri`, e.g. "192.168.1.20:8081" (dev server only). */
  hostUri?: string | null | undefined;
  isDev: boolean;
}

/**
 * Extracts the host from an Expo `hostUri` ("192.168.1.20:8081", "localhost:8081",
 * "[::1]:8081", "http://host:8081/path"). Returns undefined for tunnel hosts, which
 * don't forward to the backend.
 */
export function hostFromHostUri(hostUri: string): string | undefined {
  let rest = hostUri.trim().replace(/^[a-z][a-z0-9+.-]*:\/\//i, '');
  rest = rest.split(/[/?#]/)[0] ?? '';
  let host: string;
  if (rest.startsWith('[')) {
    const end = rest.indexOf(']');
    host = end === -1 ? '' : rest.slice(0, end + 1);
  } else {
    host = rest.split(':')[0] ?? '';
  }
  if (!host || host === '[]') return undefined;
  if (/\.exp\.direct$/i.test(host) || /\.ngrok[\w-]*\.(io|app|dev)$/i.test(host)) return undefined;
  return host;
}

/**
 * Backend origin for the API client:
 * 1. `EXPO_PUBLIC_API_URL` when set (staging, production, tunnels);
 * 2. in development, the Metro host on the backend port, so physical devices on the
 *    same Wi-Fi reach the backend running on the developer's machine;
 * 3. `http://localhost:8180`.
 */
export function resolveApiBaseUrl({ envUrl, hostUri, isDev }: ApiBaseUrlInput): string {
  const fromEnv = envUrl?.trim();
  if (fromEnv) return fromEnv.replace(/\/+$/, '');
  if (isDev && hostUri) {
    const host = hostFromHostUri(hostUri);
    if (host) return `http://${host}:${LOCAL_API_PORT}`;
  }
  return FALLBACK_API_URL;
}
