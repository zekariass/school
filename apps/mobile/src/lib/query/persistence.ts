import { isPersistableAnnouncementQuery } from '@/lib/announcements/keys';
import { isPersistableAttendanceQuery } from '@/lib/attendance/keys';
import { isPersistableStaffAttendanceQuery } from '@/lib/attendance/staff/keys';
import { isPersistableCalendarQuery } from '@/lib/calendar/keys';
import { isPersistableFamilyQuery } from '@/lib/family/keys';
import { isPersistableFeedQuery } from '@/lib/feed/keys';
import { shouldPersistQuery } from '@/lib/media/url-cache';
import { isPersistableMessagingQuery } from '@/lib/messaging/keys';
import { isPersistableNotificationQuery } from '@/lib/notifications/keys';
import { isPersistablePeopleQuery } from '@/lib/people/keys';
import { isPersistablePointsQuery } from '@/lib/points/keys';
import { isPersistablePortfolioQuery } from '@/lib/portfolio/keys';

/**
 * Whether a query with this key may be written to the persisted cache (SQLite). Signed media links, notification texts and
 * counts, student records, the guardian screens, points, attendance (staff attendance too: employees' records), the feed, the announcements, the messaging, the calendar and the portfolio all stay in memory only:
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
    isPersistableMessagingQuery(queryKey) &&
    isPersistablePointsQuery(queryKey) &&
    isPersistableAttendanceQuery(queryKey) &&
    isPersistableStaffAttendanceQuery(queryKey) &&
    isPersistableCalendarQuery(queryKey) &&
    isPersistablePortfolioQuery(queryKey)
  );
}
