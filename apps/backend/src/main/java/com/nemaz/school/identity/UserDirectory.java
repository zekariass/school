package com.nemaz.school.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Read-only facts about users that other modules need, such as the language to write to them in. */
public interface UserDirectory {

	/** Preferences of the given users (unknown ids are absent from the result). */
	Map<UUID, UserPreferences> preferences(Collection<UUID> userIds);

	/** Display data of the given users (unknown ids are absent from the result). Contact details are masked only. */
	Map<UUID, UserProfileView> profiles(Collection<UUID> userIds);

	/**
	 * The user's verified phone and email (null when missing or unverified) and whether the account is an ACTIVE
	 * STANDARD (not school-managed) account; empty for an unknown user. For proofs such as "the user who accepted an
	 * invitation has verified the invited phone"; never show these values to other people.
	 */
	java.util.Optional<VerifiedContacts> verifiedContacts(UUID userId);

	/**
	 * {@link #verifiedContacts} for many users (unknown users are absent). For channel delivery (email, SMS) of messages
	 * the user has not asked for one by one; never show the values.
	 */
	Map<UUID, VerifiedContacts> verifiedContactsOf(Collection<UUID> userIds);

	record VerifiedContacts(UUID userId, String phoneE164, String email, boolean activeStandardAccount) {

		@Override
		public String toString() {
			return "VerifiedContacts[redacted]";
		}

	}

	/** Each value may be null when the user never chose one; {@code homeCountryCode} is {@code users.home_country_code}. */
	record UserPreferences(String languageCode, String timezone, String homeCountryCode) {
	}

	/** Names, avatar and the personal fields that school roles may show (callers decide who sees what). */
	record UserProfileView(UUID id, String displayName, String givenName, String middleName, String familyName,
			UUID avatarMediaId, java.time.LocalDate dateOfBirth, String gender, String username,
			String preferredLanguageCode, String maskedPhone, String maskedEmail, boolean managed, boolean active) {
	}

}
