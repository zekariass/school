/**
 * Where a notification's `data.path` may take the user. The server sends relative in-app
 * paths ("/", "/notifications", later "/messages/123"); only paths this app knows are
 * followed, anything else (unknown screens, absolute URLs, odd characters) leaves the user
 * where they are.
 */

/** Paths the app can open now, without query or hash. "/" means the user's home screen. */
const KNOWN_ROUTES: Record<string, 'home' | string> = {
  '/': 'home',
  '/notifications': '/notifications',
  '/settings': '/settings',
  // Guardians: a link was decided ("/children") and a consent decision is requested ("/consents").
  '/children': 'home',
  '/consents': '/consent',
};

/** The route to open for `path`, or null to stay put. `homeRoute` is the active role's home tab. */
export function routeForPath(path: unknown, homeRoute: string | null): string | null {
  if (typeof path !== 'string' || path.length === 0 || path.length > 2048) return null;
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) return null;
  if (/[\u0000-\u001f\u007f]/.test(path)) return null;
  const clean = path.split(/[?#]/)[0] ?? '';
  const trimmed = clean.length > 1 ? clean.replace(/\/+$/, '') : clean;
  const target = KNOWN_ROUTES[trimmed];
  if (!target) return null;
  return target === 'home' ? homeRoute : target;
}

/**
 * The `path` of a pushed notification's data. Expo delivers the push `data` as is; a raw APNs
 * payload may carry it under `body` (object or JSON text).
 */
export function pathFromNotificationData(data: unknown): string | null {
  const record = asRecord(data);
  if (!record) return null;
  if (typeof record.path === 'string') return record.path;
  const body = asRecord(record.body);
  return body && typeof body.path === 'string' ? body.path : null;
}

export function notificationIdFromData(data: unknown): string | null {
  const record = asRecord(data);
  if (!record) return null;
  if (typeof record.notificationId === 'string') return record.notificationId;
  const body = asRecord(record.body);
  return body && typeof body.notificationId === 'string' ? body.notificationId : null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value);
    } catch {
      return null;
    }
  }
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}
