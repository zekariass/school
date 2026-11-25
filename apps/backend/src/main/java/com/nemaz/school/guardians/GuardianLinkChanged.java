package com.nemaz.school.guardians;

import java.util.UUID;

/**
 * A guardian link changed (published in the changing transaction). {@code schoolId} is the school through which it
 * changed (the verifying or inviting school), null when none applies. Never carries notes or contact details.
 */
public record GuardianLinkChanged(UUID linkId, UUID studentId, UUID guardianId, UUID schoolId, Change change,
		UUID actorId) {

	public enum Change {

		/** A new PENDING link waits for verification. */
		REQUESTED,

		/** The link became ACTIVE (approved, auto-verified or created by the school). */
		ACTIVATED,

		REJECTED,

		REVOKED,

		/** Flags, relationship, primary or custody restriction changed. */
		UPDATED

	}

}
