package com.nemaz.school.identity;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * The school memberships of a user (teacher, student, other staff, guardian), contributed by the modules that own
 * them (people; guardians since M5) and shown in {@code GET /me}. Only ACTIVE memberships are returned.
 */
public interface AccountMemberships {

	List<SchoolMembership> activeFor(UUID userId);

	enum MembershipType {

		TEACHER, STUDENT, STAFF,

		/** At least one ACTIVE, not custody-restricted guardian link to an ACTIVE student of the school (M5). */
		GUARDIAN

	}

	/** {@code staffId} is set for TEACHER and STAFF, {@code studentId} (the student's user id) for STUDENT. */
	record SchoolMembership(@NotNull MembershipType type, @NotNull UUID schoolId, @NotNull String schoolSlug,
			@NotNull String schoolName, UUID staffId, UUID studentId, UUID campusId) {
	}

}
