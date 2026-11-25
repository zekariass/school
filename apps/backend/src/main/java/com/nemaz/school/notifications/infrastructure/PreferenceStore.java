package com.nemaz.school.notifications.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code notification_preferences} and {@code user_notification_settings}. */
@Component
public class PreferenceStore {

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	PreferenceStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	/** The users' stored choice for one channel of one type (users without a row are absent). */
	public Map<UUID, Boolean> channelChoices(String typeCode, String channel, Collection<UUID> userIds) {
		Map<UUID, Boolean> choices = new HashMap<>();
		if (userIds.isEmpty()) {
			return choices;
		}
		this.named.query("""
				select user_id, enabled from notification_preferences
				where type_code = :type and channel = :channel and user_id in (:users)""",
				new MapSqlParameterSource("type", typeCode).addValue("channel", channel).addValue("users", userIds),
				(rs) -> {
					choices.put(rs.getObject("user_id", UUID.class), rs.getBoolean("enabled"));
				});
		return choices;
	}

	/** All stored choices of one user, keyed {@code TYPE/CHANNEL}. */
	public Map<String, Boolean> choicesOf(UUID userId) {
		Map<String, Boolean> choices = new HashMap<>();
		this.jdbc.query("select type_code, channel, enabled from notification_preferences where user_id = ?",
				(rs) -> {
					choices.put(rs.getString("type_code") + "/" + rs.getString("channel"), rs.getBoolean("enabled"));
				}, userId);
		return choices;
	}

	public void save(UUID userId, String typeCode, String channel, boolean enabled) {
		this.jdbc.update("""
				insert into notification_preferences (user_id, type_code, channel, enabled) values (?, ?, ?, ?)
				on conflict (user_id, type_code, channel) do update set enabled = excluded.enabled""", userId, typeCode,
				channel, enabled);
	}

	public Map<UUID, QuietHours> quietHours(Collection<UUID> userIds) {
		Map<UUID, QuietHours> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		this.named.query("""
				select user_id, quiet_hours_start, quiet_hours_end from user_notification_settings
				where user_id in (:users) and quiet_hours_start is not null""",
				new MapSqlParameterSource("users", userIds), (rs) -> {
					result.put(rs.getObject("user_id", UUID.class), new QuietHours(
							rs.getObject("quiet_hours_start", LocalTime.class), rs.getObject("quiet_hours_end", LocalTime.class)));
				});
		return result;
	}

	public Settings settings(UUID userId) {
		List<Settings> rows = this.jdbc.query("""
				select quiet_hours_start, quiet_hours_end, digest_mode from user_notification_settings where user_id = ?""",
				(rs, i) -> {
					return new Settings(rs.getObject("quiet_hours_start", LocalTime.class),
							rs.getObject("quiet_hours_end", LocalTime.class), rs.getString("digest_mode"));
				}, userId);
		return rows.isEmpty() ? new Settings(null, null, "OFF") : rows.getFirst();
	}

	public void saveSettings(UUID userId, Settings settings, Instant now) {
		this.jdbc.update("""
				insert into user_notification_settings (user_id, quiet_hours_start, quiet_hours_end, digest_mode, updated_at)
				values (?, ?, ?, ?, ?)
				on conflict (user_id) do update set quiet_hours_start = excluded.quiet_hours_start,
				  quiet_hours_end = excluded.quiet_hours_end, digest_mode = excluded.digest_mode,
				  updated_at = excluded.updated_at""", userId,
				settings.quietHoursStart(), settings.quietHoursEnd(), settings.digestMode(), Timestamp.from(now));
	}

	public record QuietHours(LocalTime start, LocalTime end) {
	}

	public record Settings(LocalTime quietHoursStart, LocalTime quietHoursEnd, String digestMode) {
	}

}
