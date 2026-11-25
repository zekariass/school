package com.nemaz.school.notifications;

import java.util.List;
import java.util.UUID;

/**
 * Inbox rows were written. Handled after commit: real-time delivery to open apps, then push
 * fan-out. Delivered at least once, so handling is idempotent (the client de-duplicates inbox
 * items by id; push deliveries are unique per notification and device).
 */
public record NotificationsCreated(List<UUID> notificationIds) {

	public NotificationsCreated {
		notificationIds = List.copyOf(notificationIds);
	}

}
