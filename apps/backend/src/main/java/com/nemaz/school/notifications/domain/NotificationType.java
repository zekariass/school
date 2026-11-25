package com.nemaz.school.notifications.domain;

import java.util.List;

/** A row of {@code notification_types} (data, not an enum: R-26). */
public record NotificationType(String code, String category, List<String> defaultChannels, boolean canBeUrgent,
		boolean userCanDisable) {

	public static final String IN_APP = "IN_APP";

	public static final String PUSH = "PUSH";

	public boolean defaultEnabled(String channel) {
		return this.defaultChannels.contains(channel);
	}

}
