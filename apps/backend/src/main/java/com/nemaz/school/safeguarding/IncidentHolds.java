package com.nemaz.school.safeguarding;

import java.util.UUID;

/** Whether a person's data sits in an incident that is under legal hold (asked through {@code moderation.LegalHold}). */
public interface IncidentHolds {

	/** True when the user is the concerned student, an involved person or the raiser of a held incident. */
	boolean userInHeldIncident(UUID userId);

}
