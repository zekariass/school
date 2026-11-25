package com.nemaz.school.notifications;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A notification to send.
 * <ul>
 * <li>{@code typeCode}: a row of {@code notification_types}.</li>
 * <li>{@code messageKey}: the prefix of the texts, {@code <key>.title} and {@code <key>.body} in
 * messages*.properties; defaults to {@code notification.<type in lower case>}. {@code args} are
 * the message arguments; a {@link LocalizedText} argument is resolved in each recipient's language.</li>
 * <li>{@code data}: client-side routing and ids. {@code path} must be a relative in-app path
 * ({@code /...}); no secrets, the whole map is stored and sent to push providers.</li>
 * </ul>
 */
public record NotificationRequest(String typeCode, Collection<UUID> recipients, String messageKey, List<Object> args,
		Map<String, Object> data, UUID schoolId, String sourceType, UUID sourceId, NotificationPriority priority) {

	private static final Pattern SAFE_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9\\-._~/%?=&#:@+]{0,300}$");

	public NotificationRequest {
		if (typeCode == null || typeCode.isBlank()) {
			throw new IllegalArgumentException("typeCode is required");
		}
		recipients = new LinkedHashSet<>(recipients);
		if (messageKey == null || messageKey.isBlank()) {
			messageKey = "notification." + typeCode.toLowerCase();
		}
		args = List.copyOf(args);
		data = (data == null) ? Map.of() : Map.copyOf(data);
		if (data.get("path") instanceof String path && !SAFE_PATH.matcher(path).matches()) {
			throw new IllegalArgumentException("data.path must be a relative app path starting with a single '/'");
		}
		if ((sourceType == null) != (sourceId == null)) {
			throw new IllegalArgumentException("sourceType and sourceId go together");
		}
		priority = (priority == null) ? NotificationPriority.NORMAL : priority;
	}

	public static Builder to(String typeCode, Collection<UUID> recipients) {
		return new Builder(typeCode, recipients);
	}

	public static Builder to(String typeCode, UUID recipient) {
		return new Builder(typeCode, List.of(recipient));
	}

	public static final class Builder {

		private final String typeCode;

		private final Collection<UUID> recipients;

		private String messageKey;

		private List<Object> args = List.of();

		private final Map<String, Object> data = new LinkedHashMap<>();

		private UUID schoolId;

		private String sourceType;

		private UUID sourceId;

		private NotificationPriority priority;

		private Builder(String typeCode, Collection<UUID> recipients) {
			this.typeCode = typeCode;
			this.recipients = recipients;
		}

		public Builder messageKey(String messageKey) {
			this.messageKey = messageKey;
			return this;
		}

		public Builder args(Object... args) {
			this.args = List.of(args);
			return this;
		}

		/** The in-app route to open when the notification is tapped, e.g. {@code /s/my-school/posts/123}. */
		public Builder path(String path) {
			this.data.put("path", path);
			return this;
		}

		public Builder data(String key, Object value) {
			this.data.put(key, value);
			return this;
		}

		public Builder school(UUID schoolId) {
			this.schoolId = schoolId;
			return this;
		}

		public Builder source(String sourceType, UUID sourceId) {
			this.sourceType = sourceType;
			this.sourceId = sourceId;
			return this;
		}

		public Builder urgent() {
			this.priority = NotificationPriority.URGENT;
			return this;
		}

		public NotificationRequest build() {
			return new NotificationRequest(this.typeCode, this.recipients, this.messageKey, this.args, this.data,
					this.schoolId, this.sourceType, this.sourceId, this.priority);
		}

	}

}
