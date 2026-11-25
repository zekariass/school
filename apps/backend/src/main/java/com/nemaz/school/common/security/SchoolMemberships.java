package com.nemaz.school.common.security;

import java.util.UUID;

/**
 * Who belongs to which school as staff or student (R-02: memberships are not role assignments).
 * Implemented by the people module; it lives in the shared kernel so that the academic and identity
 * modules can ask without depending on it. Deny by default: no membership, no access.
 */
public interface SchoolMemberships {

	/** True when the school exists (a platform-wide grant covers ids that do not exist). */
	boolean schoolExists(UUID schoolId);

	/** True when the user is ACTIVE staff or an ACTIVE student of the school. */
	boolean isActiveMember(UUID userId, UUID schoolId);

	/**
	 * True when {@code reader} may see the avatar of {@code subject}: both are ACTIVE members of one
	 * school and, if the subject is a student, the reader is staff of that school or shares a class with
	 * the student.
	 */
	boolean mayViewAvatar(UUID readerUserId, UUID subjectUserId);

}
