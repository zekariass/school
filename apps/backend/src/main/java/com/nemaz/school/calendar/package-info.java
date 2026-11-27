/**
 * Calendar and events (Implementation Plan M11, Database_Design §4.14, R-17, spec §63).
 * <ul>
 * <li><b>Native events</b> have a scope (school, campus, grade level, class, personal, or invitees for parent meetings),
 * a time zone of their own and either instants (timed) or calendar dates (all-day), an RFC 5545 RRULE subset expanded
 * server-side for the requested window only, per-occurrence overrides (cancel, modify), RSVP and soft deletion; cancelling
 * never deletes. Text is plain text.</li>
 * <li><b>The calendar API returns a union (R-17):</b> every {@link CalendarSource} contributes the items of its own source,
 * already filtered by the caller's access. The sources of this module are the events, the school's holidays and the
 * academic year and period boundaries; later milestones (assignment due dates, M17, and the published timetable, M37) add theirs by declaring a
 * {@code CalendarSource} bean, with no change here.</li>
 * <li><b>Access</b> goes through {@code academic.ClassAccess} and its rules, {@code guardians.GuardianAccess} (parent
 * meetings: the guardians' links are re-checked at every read) and the permission {@code school.calendar.manage}.</li>
 * <li><b>Notifications:</b> parent-meeting invitations, changes and cancellations always; the same for ordinary events when
 * the school's settings ask for it; {@code UPCOMING_EVENT} reminders from a scheduled job, once per person per occurrence.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Calendar")
package com.nemaz.school.calendar;

import org.springframework.modulith.ApplicationModule;
