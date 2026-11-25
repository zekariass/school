package com.nemaz.school.notifications.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.notifications.domain.NotificationRow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** An inbox item as clients see it (REST and the real-time {@code notification.created} event). */
public record NotificationView(@NotNull UUID id, @NotNull String typeCode, UUID schoolId, @NotNull String title,
		String body,
		@NotNull @Schema(description = "Routing data; 'path' is a relative in-app path") Map<String, Object> data,
		String sourceType, UUID sourceId, @NotNull @Schema(allowableValues = { "NORMAL", "URGENT" }) String priority,
		Instant readAt, @NotNull Instant createdAt) {

	static NotificationView of(NotificationRow row) {
		return new NotificationView(row.id(), row.typeCode(), row.schoolId(), row.title(), row.body(), row.data(),
				row.sourceType(), row.sourceId(), row.priority(), row.readAt(), row.createdAt());
	}

}
