package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserPreferences;
import com.nemaz.school.notifications.domain.NotificationRow;
import com.nemaz.school.notifications.domain.NotificationType;
import com.nemaz.school.notifications.domain.PushDevice;
import com.nemaz.school.notifications.infrastructure.DeliveryStore;
import com.nemaz.school.notifications.infrastructure.NotificationTypeStore;
import com.nemaz.school.notifications.infrastructure.PreferenceStore;
import com.nemaz.school.notifications.infrastructure.PreferenceStore.QuietHours;
import com.nemaz.school.notifications.infrastructure.PushDeviceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Decides, per recipient and device, what happens to a push, and records it in
 * {@code notification_deliveries}:
 * <ol>
 * <li>PUSH switched off for the type (or not wanted by default): nothing is sent; when the type
 * is pushed by default this is recorded as SUPPRESSED (PREFERENCE). Locked types ignore any stored
 * opt-out.</li>
 * <li>No active device: SUPPRESSED (NO_DEVICE).</li>
 * <li>Each device: inside the user's quiet hours (their timezone, default Africa/Addis_Ababa) the
 * push is SUPPRESSED (QUIET_HOURS), unless the notification is URGENT and its type may be urgent;
 * for types that {@code defer_in_quiet_hours} (announcements) it is instead QUEUED with {@code not_before} =
 * the end of the quiet hours, and {@link DeliveryRetryJob} sends it then; otherwise QUEUED, then sent.</li>
 * <li>The floor: an URGENT notification of a type with {@code urgent_overrides_preferences} is pushed even when the
 * user switched the type's push off.</li>
 * </ol>
 * Each row is unique per notification and device, so handling the same event twice adds nothing.
 */
@Component
class PushFanout {

	static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Addis_Ababa");

	private static final Logger log = LoggerFactory.getLogger(PushFanout.class);

	private final NotificationTypeStore types;

	private final PreferenceStore preferences;

	private final PushDeviceStore devices;

	private final DeliveryStore deliveries;

	private final UserDirectory users;

	private final PushDispatcher dispatcher;

	private final Clock clock;

	PushFanout(NotificationTypeStore types, PreferenceStore preferences, PushDeviceStore devices,
			DeliveryStore deliveries, UserDirectory users, PushDispatcher dispatcher, Clock clock) {
		this.types = types;
		this.preferences = preferences;
		this.devices = devices;
		this.deliveries = deliveries;
		this.users = users;
		this.dispatcher = dispatcher;
		this.clock = clock;
	}

	void fanOut(List<NotificationRow> rows) {
		Instant now = this.clock.instant();
		Set<UUID> userIds = rows.stream().map(NotificationRow::userId).collect(Collectors.toSet());
		Map<UUID, List<PushDevice>> devicesByUser = this.devices.activeOf(userIds);
		Map<UUID, QuietHours> quiet = this.preferences.quietHours(userIds);
		Map<UUID, UserPreferences> userPrefs = this.users.preferences(userIds);
		Map<String, NotificationType> typeCache = new HashMap<>();
		Map<String, Map<UUID, Boolean>> choiceCache = new HashMap<>();
		for (NotificationRow row : rows) {
			NotificationType type = typeCache.computeIfAbsent(row.typeCode(),
					(code) -> this.types.find(code).orElse(null));
			if (type == null) {
				continue;
			}
			Map<UUID, Boolean> choices = choiceCache.computeIfAbsent(row.typeCode(),
					(code) -> this.preferences.channelChoices(code, NotificationType.PUSH, userIds));
			boolean byDefault = type.defaultEnabled(NotificationType.PUSH);
			boolean bypass = row.urgent() && type.canBeUrgent();
			boolean wanted = type.userCanDisable() ? choices.getOrDefault(row.userId(), byDefault) : byDefault;
			// the floor: an URGENT notification of such a type is pushed whatever the user chose (in-app is always on)
			if (!wanted && !(bypass && type.urgentOverridesPreferences() && byDefault)) {
				if (byDefault) {
					this.deliveries.insertSuppressed(row.id(), null, null, "PREFERENCE", now);
				}
				continue;
			}
			List<PushDevice> userDevices = devicesByUser.getOrDefault(row.userId(), List.of());
			if (userDevices.isEmpty()) {
				this.deliveries.insertSuppressed(row.id(), null, null, "NO_DEVICE", now);
				continue;
			}
			QuietHours hours = quiet.get(row.userId());
			Instant heldUntil = (bypass || hours == null) ? null
					: QuietHoursPolicy
						.endOfQuietHours(now, zone(userPrefs.get(row.userId())), hours.start(), hours.end())
						.orElse(null);
			for (PushDevice device : userDevices) {
				if (heldUntil == null) {
					this.deliveries.insertQueued(row.id(), device.id(), device.provider(), now);
				}
				else if (type.deferInQuietHours()) {
					// the in-app item is already there; the push waits for the end of the quiet hours
					this.deliveries.insertQueued(row.id(), device.id(), device.provider(), now, heldUntil);
				}
				else {
					this.deliveries.insertSuppressed(row.id(), device.id(), device.provider(), "QUIET_HOURS", now);
				}
			}
		}
		this.dispatcher.dispatch(this.deliveries.queuedFor(rows.stream().map(NotificationRow::id).toList(), now));
	}

	static ZoneId zone(UserPreferences preferences) {
		if (preferences == null || preferences.timezone() == null || preferences.timezone().isBlank()) {
			return DEFAULT_ZONE;
		}
		try {
			return ZoneId.of(preferences.timezone());
		}
		catch (DateTimeException ex) {
			log.warn("Unknown user timezone {}; using {}", preferences.timezone(), DEFAULT_ZONE);
			return DEFAULT_ZONE;
		}
	}

}
