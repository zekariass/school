package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserPreferences;
import com.nemaz.school.notifications.NotificationPriority;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.notifications.NotificationsCreated;
import com.nemaz.school.notifications.domain.NotificationRow;
import com.nemaz.school.notifications.domain.NotificationType;
import com.nemaz.school.notifications.infrastructure.NotificationStore;
import com.nemaz.school.notifications.infrastructure.NotificationTypeStore;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultNotificationService implements NotificationService {

	private final NotificationTypeStore types;

	private final NotificationStore store;

	private final UserDirectory users;

	private final NotificationRenderer renderer;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	DefaultNotificationService(NotificationTypeStore types, NotificationStore store, UserDirectory users,
			NotificationRenderer renderer, ApplicationEventPublisher events, Clock clock) {
		this.types = types;
		this.store = store;
		this.users = users;
		this.renderer = renderer;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional
	public List<UUID> notify(NotificationRequest request) {
		NotificationType type = this.types.find(request.typeCode())
			.orElseThrow(() -> new IllegalArgumentException("Unknown notification type " + request.typeCode()));
		if (request.recipients().isEmpty()) {
			return List.of();
		}
		Map<UUID, UserPreferences> recipients = this.users.preferences(request.recipients());
		// URGENT only counts for types that may be urgent, so what is stored is what is honoured
		String priority = (request.priority() == NotificationPriority.URGENT && type.canBeUrgent()) ? "URGENT"
				: "NORMAL";
		// the clock's instant truncated to what PostgreSQL stores, so keyset cursors round-trip exactly
		var now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		Map<Locale, NotificationRenderer.Rendered> rendered = new HashMap<>();
		List<NotificationRow> rows = new ArrayList<>();
		for (UUID userId : request.recipients()) {
			UserPreferences preferences = recipients.get(userId);
			if (preferences == null) {
				continue;
			}
			Locale locale = NotificationRenderer.localeOf(preferences.languageCode());
			NotificationRenderer.Rendered text = rendered.computeIfAbsent(locale,
					(l) -> this.renderer.render(request.messageKey(), request.args(), l));
			rows.add(new NotificationRow(Ids.newId(), userId, type.code(), request.schoolId(), text.title(),
					text.body(), request.data(), request.sourceType(), request.sourceId(), priority, null, now));
		}
		if (rows.isEmpty()) {
			return List.of();
		}
		this.store.insert(rows);
		List<UUID> ids = rows.stream().map(NotificationRow::id).toList();
		this.events.publishEvent(new NotificationsCreated(ids));
		return ids;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean alreadyNotified(UUID userId, String typeCode, String sourceType, UUID sourceId) {
		return this.store.existsForSource(userId, typeCode, sourceType, sourceId);
	}

}
