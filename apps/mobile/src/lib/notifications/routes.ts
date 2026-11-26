import { GROUPS_PATH, conversationIdFromPath } from '@/lib/messaging/notifications';

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
  // The announcements inbox (no id).
  '/announcements': '/announcements',
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
/** The feed (M8): a post (NEW_POST, POST_APPROVED/REJECTED, POST_PUBLISH_BLOCKED, POST_MEDIA_WITHHELD), a story, the approval queue. */
const FEED_POST = new RegExp(`^/feed/posts/(${UUID})$`);
const FEED_STORY = new RegExp(`^/feed/stories/(${UUID})$`);
/** An announcement (ANNOUNCEMENT_PUBLISHED, ACK_REQUESTED, _UPDATED, _REMINDER, _WITHDRAWN, _PUBLISH_BLOCKED): `/announcements/{id}`. */
const ANNOUNCEMENT = new RegExp(`^/announcements/(${UUID})$`);
/** An event (EVENT_INVITATION, EVENT_UPDATED, EVENT_CANCELLED, UPCOMING_EVENT): `/calendar/events/{id}`. */
const CALENDAR_EVENT = new RegExp(`^/calendar/events/(${UUID})$`);
/** The id a repeating event's occurrence has (the UTC instant it has in the series). */
const OCCURRENCE_ID = /^\d{8}T\d{6}Z$/;

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
  const feed = feedRoute(trimmed, homeRoute, data);
  if (feed !== undefined) return feed;
  const announcement = ANNOUNCEMENT.exec(trimmed);
  if (announcement) {
    if (!homeRoute) return null;
    // `data.schoolId` (every push that has a school carries it) saves the lookup; without it the screen asks the caller's schools.
    return `/announcements/${announcement[1]}${queryOf([['schoolId', idFrom(data, 'schoolId')]])}`;
  }
  const messaging = messagingRoute(trimmed, homeRoute, data);
  if (messaging !== undefined) return messaging;
  const event = CALENDAR_EVENT.exec(trimmed);
  if (event) {
    if (!homeRoute) return null;
    // The occurrence a reminder is about, and the school, when the push names them (a bad value is left out, never followed).
    const occurrence = firstString(data, 'occurrenceId');
    return `/events/${event[1]}${queryOf([['schoolId', idFrom(data, 'schoolId')], ['occurrenceId', occurrence && OCCURRENCE_ID.test(occurrence) ? occurrence : null]])}`;
  }
  const target = KNOWN_ROUTES[trimmed];
  if (!target) return null;
  if (target === 'points') return homeRoute?.startsWith('/(student)/') ? '/(student)/points' : homeRoute;
  return target === 'home' ? homeRoute : target;
}

/**
 * The screens of the feed. A push carries only the id, so the screen finds the school itself (the inbox row also names it:
 * `data.schoolId`, when present, saves the lookup). Approvals are for teachers; everybody else lands on their home.
 * `undefined` when the path is not a feed path.
 */
function feedRoute(path: string, homeRoute: string | null, data: unknown): string | null | undefined {
  const post = FEED_POST.exec(path);
  const story = FEED_STORY.exec(path);
  if (!post && !story && path !== '/feed/approvals') return undefined;
  if (!homeRoute) return null;
  const pick = (key: string) => idFrom(data, key);
  const query = queryOf;
  if (post) return `/feed/post/${post[1]}${query([['schoolId', pick('schoolId')]])}`;
  if (story) return `/feed/story/${story[1]}${query([['schoolId', pick('schoolId')]])}`;
  if (!homeRoute.startsWith('/(teacher)/')) return homeRoute;
  return `/feed/approvals${query([['schoolId', pick('schoolId')], ['classId', pick('classId')]])}`;
}

/**
 * The messaging (M9): `/messages/{conversationId}` (NEW_MESSAGE, NEW_MESSAGE_QUIET, MESSAGE_OBSERVER_ADDED, MESSAGE_REPORTED,
 * MESSAGE_SCHEDULE_BLOCKED) opens that conversation: the screen asks the API, which answers 404 to anybody who may not read it,
 * so no id is trusted here. `/messages/groups` (GROUP_APPROVAL_REQUESTED) is the teachers' group list, with the school when the
 * push names it; `/messages` is the inbox tab. `undefined` when the path is not a messaging path.
 */
function messagingRoute(path: string, homeRoute: string | null, data: unknown): string | null | undefined {
  if (path !== '/messages' && !path.startsWith('/messages/')) return undefined;
  if (!homeRoute) return null;
  if (path === '/messages') return homeRoute.replace(/\/[^/]+$/, '/messages');
  if (path === GROUPS_PATH) return homeRoute.startsWith('/(teacher)/') ? `/messages/groups${queryOf([['schoolId', idFrom(data, 'schoolId')]])}` : homeRoute;
  const conversationId = conversationIdFromPath(path);
  return conversationId ? `/messages/${conversationId}` : null;
}

/** A uuid under `key` of a notification's data (top level, or inside a raw APNs `body`); null when absent or not a uuid. */
function idFrom(data: unknown, key: string): string | null {
  const record = asRecord(data);
  const nested = asRecord(record?.body);
  const value = record?.[key] ?? nested?.[key];
  return typeof value === 'string' && UUID_ONLY.test(value) ? value : null;
}

/** A string under `key` of a notification's data (top level, or inside a raw APNs `body`). */
function firstString(data: unknown, key: string): string | null {
  const record = asRecord(data);
  const nested = asRecord(record?.body);
  const value = record?.[key] ?? nested?.[key];
  return typeof value === 'string' ? value : null;
}

function queryOf(entries: [string, string | null][]): string {
  const parts = entries.filter((entry): entry is [string, string] => entry[1] !== null).map(([key, value]) => `${key}=${value}`);
  return parts.length ? `?${parts.join('&')}` : '';
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
