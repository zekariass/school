/**
 * The two notices a reporter gets (M13a): `REPORT_ACKNOWLEDGED` right after the report and `REPORT_REVIEWED` when a person has
 * looked at it. Their title and body are rendered by the API in the person's language and are generic (no outcome, no names, no
 * content); they carry no path and no data, so tapping one only marks it read. The phone never opens a screen for them, even if a
 * path were sent, and it never adds a "what happened" screen: nothing about a case is available to a reporter.
 */
export const REPORT_NOTIFICATION_TYPES = ['REPORT_ACKNOWLEDGED', 'REPORT_REVIEWED'] as const;

export function isReportNotification(typeCode: string | null | undefined): boolean {
  return !!typeCode && (REPORT_NOTIFICATION_TYPES as readonly string[]).includes(typeCode);
}

/**
 * Moderation and safeguarding screens are a web task (the queue, the case page, incidents). The API names them in the paths of
 * the alerts it sends to moderators and leads (`/moderation`, `/s/{slug}/moderation`): the phone has no such screen, so it stays
 * where it is instead of guessing a route.
 */
const MODERATION_PATH = /^\/(?:s\/[^/]+\/)?(?:moderation|safeguarding)(?:\/|$)/;

export function isModerationPath(path: string): boolean {
  return MODERATION_PATH.test(path);
}
