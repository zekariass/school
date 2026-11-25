package com.nemaz.school.consent;

import java.util.UUID;

/**
 * A consent decision was recorded (published in the recording transaction). {@code schoolId} is null for an adult's
 * own TERMS/PRIVACY. Listeners recompute what they need through {@link ConsentService}; the event carries no names.
 * Guardian link changes (revocation, restriction) also change effective statuses: those publish
 * {@code guardians.GuardianLinkChanged}.
 */
public record ConsentChanged(UUID recordId, UUID subjectUserId, UUID schoolId, ConsentType consentType,
		String decision, UUID actorId) {
}
