/**
 * Where a notification's `data.path` may take the user. The server sends relative in-app
 * paths ("/", "/notifications", later "/messages/123"); only paths this app knows are
 * followed, anything else (unknown screens, absolute URLs, odd characters) leaves the user
 * where they are.
 */

/** Paths the app can open now, without query or hash. "/" means the user's home screen. */
const KNOWN_ROUTES: Record<string, 'home' | 'points' | string> = {
  '/': 'home',
  '/notifications': '/notifications',
  '/settings': '/settings',
  // Guardians: a link was decided ("/children") and a consent decision is requested ("/consents").
  '/children': 'home',
  '/consents': '/consent',
  // Students: points or a badge was awarded ("/points"). Anyone else lands on their home.
  '/points': 'points',
};

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
/** A teacher is told of a parent's absence report: "/classes/{classId}/attendance" opens that class's inbox. */
const CLASS_ABSENCES = new RegExp(`^/classes/(${UUID})/attendance$`);
const UUID_ONLY = new RegExp(`^${UUID}$`);

/**
 * The route to open for `path`, or null to stay put. `homeRoute` is the active role's home tab. `data` is the
 * notification's data: "/children" alone only names the family list, but an ATTENDANCE_MARKED notification also
 * carries the child and the date, and opens that child's attendance.
 */
export function routeForPath(path: unknown, homeRoute: string | null, data?: unknown): string | null {
  if (typeof path !== 'string' || path.length === 0 || path.length > 2048) return null;
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) return null;
  if (/[\u0000-\u001f\u007f]/.test(path)) return null;
  const clean = path.split(/[?#]/)[0] ?? '';
  const trimmed = clean.length > 1 ? clean.replace(/\/+$/, '') : clean;
  const absences = CLASS_ABSENCES.exec(trimmed);
  if (absences) return homeRoute?.startsWith('/(teacher)/') ? `/class/${absences[1]}/absences` : homeRoute;
  if (trimmed === '/children' && homeRoute?.startsWith('/(guardian)/')) {
    const record = asRecord(data);
    const nested = asRecord(record?.body);
    const studentId = record?.studentId ?? nested?.studentId;
    const date = record?.date ?? nested?.date;
    if (typeof studentId === 'string' && UUID_ONLY.test(studentId) && typeof date === 'string') return `/child/${studentId}/attendance`;
  }
  const target = KNOWN_ROUTES[trimmed];
  if (!target) return null;
  if (target === 'points') return homeRoute?.startsWith('/(student)/') ? '/(student)/points' : homeRoute;
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
