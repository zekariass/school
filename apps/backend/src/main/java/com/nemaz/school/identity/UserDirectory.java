package com.nemaz.school.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Read-only facts about users that other modules need, such as the language to write to them in. */
public interface UserDirectory {

	/** Preferences of the given users (unknown ids are absent from the result). */
	Map<UUID, UserPreferences> preferences(Collection<UUID> userIds);

	/** Both values may be null when the user never chose one. */
	record UserPreferences(String languageCode, String timezone) {
	}

}
