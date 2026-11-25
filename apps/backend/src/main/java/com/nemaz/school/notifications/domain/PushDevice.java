package com.nemaz.school.notifications.domain;

import java.time.Instant;
import java.util.UUID;

public record PushDevice(UUID id, UUID userId, UUID sessionFamilyId, String platform, String provider,
		String pushToken, String appVersion, String deviceModel, String locale, Instant createdAt,
		Instant lastSeenAt) {
}
