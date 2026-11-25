package com.nemaz.school.attendance;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A student's attendance record was created or its status, lateness or note changed (published in the transaction that
 * wrote it, once per changed record). Drives the guardian notifications and the perfect-attendance badge; delivery is
 * at-least-once, so listeners must be idempotent. Carries ids, the status and the date only: never the note, a name, or
 * anything from an absence report.
 *
 * @param version the record's version after the change: ({@code recordId}, {@code version}) identifies this change
 * @param markedBy the user who made the mark
 */
public record AttendanceMarked(UUID recordId, int version, UUID sessionId, UUID schoolId, UUID classId, UUID studentId,
		LocalDate attendanceDate, AttendanceStatus status, UUID markedBy) {
}
