/**
 * The school communications centre (Implementation Plan M10, Database_Design §4.12, spec §125). Announcements are composed
 * for an explicit audience (school, campus, grade, class or chosen people; guardians, staff and, when the school allows it,
 * students), published (at once or scheduled), edited with revisions, and withdrawn without being erased.
 * <ul>
 * <li><b>Recipients</b> are resolved when the announcement is published into a snapshot ({@code announcement_recipients}),
 * reaching guardians through their children: ACTIVE, not custody-restricted links with {@code can_receive_notifications}
 * to students ACTIVE in the audience, one entry per person however many children. Reading follows the audience rule
 * <em>now</em> (a guardian linked later can open an announcement through the school's list), see
 * {@code AnnouncementAccess}.</li>
 * <li><b>Delivery</b> goes through {@link com.nemaz.school.notifications.NotificationService} in chunks that are resumable
 * and idempotent; email and SMS are optional, switched by school settings, and SMS goes through {@link SmsAdapter}s
 * (none is chosen yet) with per-announcement and per-day caps. Delivery, reading and acknowledgement are tracked
 * separately.</li>
 * <li><b>Urgent and safety</b> announcements need {@code school.announcements.urgent}, are pushed through quiet hours and
 * cannot be muted by preferences; other announcements wait for the end of the recipient's quiet hours (push, email,
 * SMS) while the in-app item is immediate.</li>
 * </ul>
 * Other modules use the events of this package and the SPIs {@link SmsAdapter} and {@link SmsOptOuts}.
 */
@ApplicationModule(displayName = "Communications")
package com.nemaz.school.communications;

import org.springframework.modulith.ApplicationModule;
