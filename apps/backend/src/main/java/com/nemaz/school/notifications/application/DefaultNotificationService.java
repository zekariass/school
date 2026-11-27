package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.realtime.RealtimePublisher;
import com.nemaz.school.notifications.NotificationsCreated;
import com.nemaz.school.notifications.domain.NotificationRow;
import com.nemaz.school.notifications.domain.NotificationType;
import com.nemaz.school.notifications.infrastructure.DeliveryStore;
import com.nemaz.school.notifications.infrastructure.NotificationStore;
import com.nemaz.school.notifications.infrastructure.NotificationTypeStore;
import com.nemaz.school.notifications.infrastructure.PreferenceStore;

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

	private final PreferenceStore preferences;

	private final DeliveryStore deliveries;

	private final RealtimePublisher realtime;

	private final UserZones zones;

	DefaultNotificationService(NotificationTypeStore types, NotificationStore store, UserDirectory users,
			NotificationRenderer renderer, ApplicationEventPublisher events, Clock clock, PreferenceStore preferences,
			DeliveryStore deliveries, RealtimePublisher realtime, UserZones zones) {
		this.zones = zones;
		this.realtime = realtime;
		this.preferences = preferences;
		this.deliveries = deliveries;
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
			Locale locale = this.renderer.localeOf(preferences.languageCode());
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

	@Override
	@Transactional(readOnly = true)
	public boolean hasUnread(UUID userId, String typeCode, String sourceType, UUID sourceId) {
		return this.store.existsUnreadForSource(userId, typeCode, sourceType, sourceId);
	}

	@Override
	@Transactional
	public int markReadForSource(UUID userId, Collection<String> typeCodes, String sourceType, UUID sourceId) {
		List<UUID> ids = this.store.markReadBySource(userId, typeCodes, sourceType, sourceId, this.clock.instant());
		if (!ids.isEmpty()) {
			int unread = this.store.unreadCount(userId);
			AfterCommit.run(() -> this.realtime.toUser(userId, RealtimePublisher.NOTIFICATIONS_QUEUE,
					RealtimeEvents.Read.ids(ids, unread)));
		}
		return ids.size();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Instant> quietHoursEnds(Collection<UUID> userIds) {
		Map<UUID, Instant> ends = new HashMap<>();
		if (userIds.isEmpty()) {
			return ends;
		}
		Instant now = this.clock.instant();
		Map<UUID, PreferenceStore.QuietHours> hours = this.preferences.quietHours(userIds);
		Map<UUID, UserPreferences> prefs = this.users.preferences(hours.keySet());
		hours.forEach((userId, window) -> QuietHoursPolicy
			.endOfQuietHours(now, this.zones.zone(prefs.get(userId)), window.start(), window.end())
			.ifPresent((end) -> ends.put(userId, end)));
		return ends;
	}

	@Override
	@Transactional
	public int cancelHeldBackPushes(String sourceType, UUID sourceId) {
		return this.deliveries.suppressHeldBack(sourceType, sourceId, this.clock.instant());
	}

}
