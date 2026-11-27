package com.nemaz.school.consent;

/**
 * Consent and policy types of Phase 1 (R-09). ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION are decided for a child by
 * their guardians (or recorded by the school); TERMS and PRIVACY are accepted by adults for themselves. AI
 * processing and research consent arrive with their features (there is no recording or transcription).
 */
public enum ConsentType {

	ACCOUNT_PARTICIPATION, MEDIA_PUBLICATION, TERMS, PRIVACY;

	/** Decided by guardians for a child (not by the adult for themself). */
	public boolean forChildren() {
		return this == ACCOUNT_PARTICIPATION || this == MEDIA_PUBLICATION;
	}

}
