package com.nemaz.school.notifications.domain;

import java.util.List;

/**
 * A row of {@code notification_types} (data, not an enum: R-26). {@code deferInQuietHours}: a push inside the
 * recipient's quiet hours waits for their end instead of being dropped. {@code urgentOverridesPreferences}: an URGENT
 * notification of the type is pushed even when the recipient switched the type's push off (in-app is always on).
 */
public record NotificationType(String code, String category, List<String> defaultChannels, boolean canBeUrgent,
		boolean userCanDisable, boolean deferInQuietHours, boolean urgentOverridesPreferences) {

	public static final String IN_APP = "IN_APP";

	public static final String PUSH = "PUSH";

	public boolean defaultEnabled(String channel) {
		return this.defaultChannels.contains(channel);
	}

}
