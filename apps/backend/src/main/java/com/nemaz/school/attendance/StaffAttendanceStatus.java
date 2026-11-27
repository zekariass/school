package com.nemaz.school.attendance;

/**
 * How a staff member was marked for a day (Database_Design §4.34). {@code EXCUSED} is a status a manager sets, nothing
 * more: staff attendance carries no leave, pay or other HR data (D14).
 */
public enum StaffAttendanceStatus {

	PRESENT, ABSENT,
	/** Arrived late; {@code minutes_late} may say how late. */
	LATE, HALF_DAY, EXCUSED

}
