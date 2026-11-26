import { isPersistableAnnouncementQuery } from '@/lib/announcements/keys';
import { isPersistableAttendanceQuery } from '@/lib/attendance/keys';
import { isPersistableFamilyQuery } from '@/lib/family/keys';
import { isPersistableFeedQuery } from '@/lib/feed/keys';
import { shouldPersistQuery } from '@/lib/media/url-cache';
import { isPersistableNotificationQuery } from '@/lib/notifications/keys';
import { isPersistablePeopleQuery } from '@/lib/people/keys';
import { isPersistablePointsQuery } from '@/lib/points/keys';

/**
 * Whether a query with this key may be written to the persisted cache (SQLite). Signed media links, notification texts and
 * counts, student records, the guardian screens, points, attendance, the feed and the announcements all stay in memory only:
 * they describe children's school life. What a teacher needs offline is stored by `lib/offline` in its own owner-scoped table.
 */
export function isPersistableQuery(queryKey: readonly unknown[]): boolean {
  return (
    shouldPersistQuery(queryKey) &&
    isPersistableNotificationQuery(queryKey) &&
    isPersistablePeopleQuery(queryKey) &&
    isPersistableFamilyQuery(queryKey) &&
    isPersistableFeedQuery(queryKey) &&
    isPersistableAnnouncementQuery(queryKey) &&
    isPersistablePointsQuery(queryKey) &&
    isPersistableAttendanceQuery(queryKey)
  );
}
