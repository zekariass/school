package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.notifications.domain.NotificationType;
import com.nemaz.school.notifications.infrastructure.NotificationTypeStore;
import com.nemaz.school.notifications.infrastructure.PreferenceStore;
import com.nemaz.school.notifications.infrastructure.PreferenceStore.Settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's choices: per type and channel (IN_APP is always on, PUSH can be switched off unless
 * the type is locked) and quiet hours. A stored choice wins over the type's default; locked
 * types ({@code user_can_disable = false}) cannot be switched off.
 */
@Service
public class PreferenceService {

	/** Channels a user can see. EMAIL and SMS are not delivered yet, so they are not offered. */
	static final List<String> CHANNELS = List.of(NotificationType.IN_APP, NotificationType.PUSH);

	private static final List<String> CATEGORY_ORDER = List.of("CLASSROOM", "MESSAGING", "SCHOOL", "LEARNING",
			"MARKETPLACE", "ACCOUNT", "SAFETY");

	private final NotificationTypeStore types;

	private final PreferenceStore store;

	private final UserDirectory users;

	private final NotificationRenderer renderer;

	private final Clock clock;

	PreferenceService(NotificationTypeStore types, PreferenceStore store, UserDirectory users,
			NotificationRenderer renderer, Clock clock) {
		this.types = types;
		this.store = store;
		this.users = users;
		this.renderer = renderer;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Preferences preferences(UUID userId, Locale locale) {
		Map<String, Boolean> stored = this.store.choicesOf(userId);
		Map<String, List<TypeView>> byCategory = new LinkedHashMap<>();
		CATEGORY_ORDER.forEach((c) -> byCategory.put(c, new ArrayList<>()));
		for (NotificationType type : this.types.all()) {
			List<ChannelView> channels = new ArrayList<>();
			for (String channel : CHANNELS) {
				boolean locked = isLocked(type, channel);
				boolean enabled = locked ? type.defaultEnabled(channel) || NotificationType.IN_APP.equals(channel)
						: stored.getOrDefault(type.code() + "/" + channel, type.defaultEnabled(channel));
				channels.add(new ChannelView(channel, enabled, locked));
			}
			byCategory.computeIfAbsent(type.category(), (c) -> new ArrayList<>())
				.add(new TypeView(type.code(),
						this.renderer.label("notification." + type.code().toLowerCase() + ".label", locale),
						type.canBeUrgent(), type.userCanDisable(), channels));
		}
		List<CategoryView> categories = new ArrayList<>();
		byCategory.forEach((code, views) -> {
			if (!views.isEmpty()) {
				categories.add(new CategoryView(code,
						this.renderer.label("notification.category." + code.toLowerCase(), locale), views));
			}
		});
		return new Preferences(categories);
	}

	@Transactional
	public Preferences change(UUID userId, List<Change> changes, Locale locale) {
		Map<String, NotificationType> byCode = new LinkedHashMap<>();
		this.types.all().forEach((t) -> byCode.put(t.code(), t));
		for (Change change : changes) {
			NotificationType type = byCode.get(change.typeCode());
			if (type == null) {
				throw ApiException.badRequest("notification.type_unknown");
			}
			if (!CHANNELS.contains(change.channel())) {
				throw ApiException.badRequest("notification.channel_unsupported");
			}
			if (!change.enabled() && isLocked(type, change.channel())) {
				throw ApiException.badRequest("notification.preference_locked");
			}
		}
		for (Change change : changes) {
			this.store.save(userId, change.typeCode(), change.channel(), change.enabled());
		}
		return preferences(userId, locale);
	}

	@Transactional(readOnly = true)
	public SettingsView settings(UUID userId) {
		Settings settings = this.store.settings(userId);
		ZoneId zone = PushFanout.zone(this.users.preferences(List.of(userId)).get(userId));
		return new SettingsView(settings.quietHoursStart(), settings.quietHoursEnd(), settings.digestMode(),
				zone.getId());
	}

	@Transactional
	public SettingsView saveSettings(UUID userId, LocalTime start, LocalTime end, String digestMode) {
		if ((start == null) != (end == null)) {
			throw ApiException.badRequest("notification.quiet_hours_incomplete");
		}
		if (digestMode != null && !"OFF".equals(digestMode)) {
			throw ApiException.badRequest("notification.digest_unsupported");
		}
		this.store.saveSettings(userId, new Settings(start, end, "OFF"), this.clock.instant());
		return settings(userId);
	}

	/** IN_APP is always on; PUSH is locked when the type cannot be disabled. */
	private static boolean isLocked(NotificationType type, String channel) {
		return NotificationType.IN_APP.equals(channel) || !type.userCanDisable();
	}

	public record Change(String typeCode, String channel, boolean enabled) {
	}

	public record Preferences(@NotNull List<CategoryView> categories) {
	}

	public record CategoryView(@NotNull String code, @NotNull String label, @NotNull List<TypeView> types) {
	}

	public record TypeView(@NotNull String code, @NotNull String label, @NotNull boolean canBeUrgent,
			@NotNull boolean userCanDisable, @NotNull List<ChannelView> channels) {
	}

	/** {@code locked}: the user cannot change this channel. */
	public record ChannelView(@NotNull @Schema(allowableValues = { "IN_APP", "PUSH" }) String channel,
			@NotNull boolean enabled, @NotNull boolean locked) {
	}

	public record SettingsView(LocalTime quietHoursStart, LocalTime quietHoursEnd, String digestMode, String timezone) {
	}

}
