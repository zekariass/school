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

	/** Both values may be null when the user never chose one. */
	record UserPreferences(String languageCode, String timezone) {
	}

	/** Names, avatar and the personal fields that school roles may show (callers decide who sees what). */
	record UserProfileView(UUID id, String displayName, String givenName, String middleName, String familyName,
			UUID avatarMediaId, java.time.LocalDate dateOfBirth, String gender, String username,
			String preferredLanguageCode, String maskedPhone, String maskedEmail, boolean managed, boolean active) {
	}

}
