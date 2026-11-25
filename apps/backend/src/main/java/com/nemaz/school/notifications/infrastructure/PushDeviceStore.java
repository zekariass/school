package com.nemaz.school.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.notifications.domain.PushDevice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code push_devices}. A revoked device keeps its row (deliveries refer to it). */
@Component
public class PushDeviceStore {

	private static final String COLUMNS = """
			id, user_id, session_family_id, platform, provider, push_token, app_version, device_model, locale, created_at,
			last_seen_at""";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	PushDeviceStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	/** The user who owns {@code token} now (revoked or not), if the token is known. */
	public Optional<UUID> ownerOf(String token) {
		return this.jdbc.queryForList("select user_id from push_devices where push_token = ?", UUID.class, token)
			.stream()
			.findFirst();
	}

	public Optional<UUID> findIdByToken(String token) {
		return this.jdbc.queryForList("select id from push_devices where push_token = ?", UUID.class, token)
			.stream()
			.findFirst();
	}

	/** Registers or refreshes a device by token; a token seen before moves to {@code userId} and is active again. */
	public PushDevice upsert(UUID userId, UUID familyId, String platform, String provider, String token,
			String appVersion, String deviceModel, String locale, Instant now) {
		return this.jdbc.queryForObject("""
				insert into push_devices (id, user_id, session_family_id, platform, provider, push_token, app_version,
				                          device_model, locale, created_at, last_seen_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (push_token) do update set user_id = excluded.user_id,
				  session_family_id = excluded.session_family_id, platform = excluded.platform,
				  provider = excluded.provider, app_version = excluded.app_version,
				  device_model = excluded.device_model, locale = excluded.locale, last_seen_at = excluded.last_seen_at,
				  revoked_at = null
				returning """ + " " + COLUMNS, (rs, i) -> map(rs), Ids.newId(), userId, familyId, platform, provider,
				token, appVersion, deviceModel, locale, Timestamp.from(now), Timestamp.from(now));
	}

	/** An active device of the user. */
	public Optional<PushDevice> findActive(UUID userId, UUID id) {
		return this.jdbc
			.query("select " + COLUMNS + " from push_devices where id = ? and user_id = ?", (rs, i) -> map(rs), id,
					userId)
			.stream()
			.findFirst();
	}

	public Map<UUID, List<PushDevice>> activeOf(Collection<UUID> userIds) {
		Map<UUID, List<PushDevice>> byUser = new HashMap<>();
		if (userIds.isEmpty()) {
			return byUser;
		}
		this.named.query("select " + COLUMNS + " from push_devices where user_id in (:users) and revoked_at is null order by created_at",
				new MapSqlParameterSource("users", userIds), (rs) -> {
					PushDevice device = map(rs);
					byUser.computeIfAbsent(device.userId(), (u) -> new java.util.ArrayList<>()).add(device);
				});
		return byUser;
	}

	public boolean revoke(UUID id, Instant now) {
		return this.jdbc.update("update push_devices set revoked_at = ? where id = ? and revoked_at is null",
				Timestamp.from(now), id) > 0;
	}

	public int revokeFamilies(UUID userId, Collection<UUID> familyIds, Instant now) {
		if (familyIds.isEmpty()) {
			return 0;
		}
		return this.named.update("""
				update push_devices set revoked_at = :now
				where user_id = :user and session_family_id in (:families) and revoked_at is null""",
				new MapSqlParameterSource("now", Timestamp.from(now)).addValue("user", userId)
					.addValue("families", familyIds));
	}

	private static PushDevice map(ResultSet rs) throws SQLException {
		Timestamp seen = rs.getTimestamp("last_seen_at");
		return new PushDevice(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("session_family_id", UUID.class), rs.getString("platform"), rs.getString("provider"),
				rs.getString("push_token"), rs.getString("app_version"), rs.getString("device_model"),
				rs.getString("locale"), rs.getTimestamp("created_at").toInstant(),
				(seen != null) ? seen.toInstant() : null);
	}

}
