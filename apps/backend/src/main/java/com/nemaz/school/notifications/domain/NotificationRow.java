package com.nemaz.school.notifications.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An inbox row as stored. */
public record NotificationRow(UUID id, UUID userId, String typeCode, UUID schoolId, String title, String body,
		Map<String, Object> data, String sourceType, UUID sourceId, String priority, Instant readAt,
		Instant createdAt) {

	public boolean urgent() {
		return "URGENT".equals(this.priority);
	}

}
