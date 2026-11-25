package com.nemaz.school.attendance;

/** How a student was marked in a roll call. */
public enum AttendanceStatus {

	PRESENT, ABSENT,
	/** Arrived late; {@code minutes_late} may say how late. */
	LATE,
	/** Absent with the school's permission (for example an acknowledged absence report). */
	EXCUSED, LEFT_EARLY

}
