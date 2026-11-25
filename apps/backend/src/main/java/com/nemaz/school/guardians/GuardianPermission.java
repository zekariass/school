package com.nemaz.school.guardians;

/**
 * What an ACTIVE, not custody-restricted guardian link allows, one value per permission flag of
 * {@code student_guardians} (R-29). A custody restriction overrides all of them.
 */
public enum GuardianPermission {

	/** Classes, attendance, points, grades, class content (spec §102). */
	VIEW_ACADEMICS,

	MESSAGE_STAFF,

	RECEIVE_NOTIFICATIONS,

	/** Home points (D2). */
	AWARD_POINTS,

	/** Account-participation and media-publication consent for the child. */
	GIVE_CONSENT,

	/** Marketplace bookings and payments (P4/P5). */
	BOOK_AND_PAY

}
