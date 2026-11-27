/**
 * Attendance (Implementation Plan M7, Database_Design §4.6, R-27, spec §72, §104): roll calls per class, date and slot,
 * absence reports from guardians, the daily dashboard and summary reports, and the offline operation
 * {@code ATTENDANCE_MARK} of the sync endpoint.
 * <ul>
 * <li><b>Dates</b> are school-local calendar dates (the class campus's or the school's time zone); the lock window and
 * the future tolerance are computed from the server clock in that zone.</li>
 * <li><b>Roll semantics:</b> an omitted student is unmarked; listed students are upserted; {@code markAllPresent} marks
 * the students who have no record yet. Records carry a {@code version}; a write over a different existing record needs
 * that record's current version (or comes from the same device's own earlier edit), otherwise it is reported as a
 * conflict and nothing is overwritten.</li>
 * <li><b>Absence reports</b> are written by guardians; the note is read only by the reporter, the student's lead,
 * co and subject teachers and school attendance managers, and never leaves the database through events, notifications,
 * logs or audit metadata.</li>
 * <li><b>Per-period rolls (M7d):</b> a roll is one per class, date and slot ({@code DAY}, {@code AM}, {@code PM},
 * {@code P1..P8}, enabled with {@code attendance.slots}); a period roll may carry one of the class's subjects (periods
 * only; it stays with the roll), and an elective's saved student group narrows the roster of one request. The dashboard
 * and the per-period report count per slot and subject. One period's roll never touches another's.</li>
 * <li><b>Staff attendance (M7d, manual):</b> {@code staff_attendance_records} (one per staff member and school-local
 * day) and the append-only {@code staff_attendance_corrections}, behind {@code school.staff_attendance.manage} and
 * {@code .read}, the setting {@code attendance.staff.enabled} (default off) and the feature {@code attendance}. A staff
 * member reads their own records; a colleague's record does not exist for a teacher (404). Staff records are never read
 * by a class, points, badge or student query. No leave, pay or HR data (D14). The QR methods are M7e.</li>
 * <li><b>Access</b> goes through {@code academic.ClassAccess}, {@code academic.ClassMemberships} and
 * {@code guardians.GuardianAccess}; other modules use the {@link com.nemaz.school.attendance.AttendanceMarked} event.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Attendance")
package com.nemaz.school.attendance;

import org.springframework.modulith.ApplicationModule;
