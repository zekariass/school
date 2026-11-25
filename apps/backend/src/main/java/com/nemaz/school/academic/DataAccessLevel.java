package com.nemaz.school.academic;

/**
 * How much of a student's personal data the caller may see. BASIC is name and avatar (what classmates see);
 * FULL adds date of birth, gender and username, for teachers of the student's classes, holders of
 * {@code school.students.manage} and the student themself.
 */
public enum DataAccessLevel {

	BASIC, FULL

}
