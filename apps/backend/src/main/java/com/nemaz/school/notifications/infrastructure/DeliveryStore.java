package com.nemaz.school.notifications.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code notification_deliveries}: one row per push device (or one per notification when there is none). */
@Component
public class DeliveryStore {

	public static final int MAX_ATTEMPTS = 3;

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private static final String JOB_SELECT = """
			select d.id as delivery_id, d.notification_id, d.push_device_id, n.user_id, p.provider, p.push_token,
			       n.title, n.body, n.data::text as data, n.priority, d.attempt_count
			from notification_deliveries d
			join notifications n on n.id = d.notification_id
			join push_devices p on p.id = d.push_device_id
			where d.channel = 'PUSH' and p.revoked_at is null""";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final JsonMapper json;

	DeliveryStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, JsonMapper json) {
		this.jdbc = jdbc;
		this.named = named;
		this.json = json;
	}

	/** Creates the QUEUED row of a device; false when it already exists (redelivered event). */
	public boolean insertQueued(UUID notificationId, UUID deviceId, String provider, Instant now) {
		return this.jdbc.update("""
				insert into notification_deliveries (id, notification_id, channel, push_device_id, provider, status, queued_at)
				values (?, ?, 'PUSH', ?, ?, 'QUEUED', ?)
				on conflict (notification_id, push_device_id) where push_device_id is not null do nothing""",
				Ids.newId(), notificationId, deviceId, provider, Timestamp.from(now)) > 0;
	}

	/** A SUPPRESSED row for a device (quiet hours) or, with a null device, for the whole notification. */
	public void insertSuppressed(UUID notificationId, UUID deviceId, String provider, String reason, Instant now) {
		if (deviceId != null) {
			this.jdbc.update("""
					insert into notification_deliveries (id, notification_id, channel, push_device_id, provider, status,
					                                     suppressed_reason, queued_at)
					values (?, ?, 'PUSH', ?, ?, 'SUPPRESSED', ?, ?)
					on conflict (notification_id, push_device_id) where push_device_id is not null do nothing""",
					Ids.newId(), notificationId, deviceId, provider, reason, Timestamp.from(now));
		}
		else {
			this.jdbc.update("""
					insert into notification_deliveries (id, notification_id, channel, status, suppressed_reason, queued_at)
					values (?, ?, 'PUSH', 'SUPPRESSED', ?, ?)
					on conflict (notification_id, channel) where push_device_id is null do nothing""", Ids.newId(),
					notificationId, reason, Timestamp.from(now));
		}
	}

	/** QUEUED deliveries of these notifications whose device is still active. */
	public List<DeliveryJob> queuedFor(Collection<UUID> notificationIds) {
		if (notificationIds.isEmpty()) {
			return List.of();
		}
		return this.named.query(JOB_SELECT + " and d.status = 'QUEUED' and d.notification_id in (:ids)",
				new MapSqlParameterSource("ids", notificationIds), (rs, i) -> job(rs));
	}

	/**
	 * Deliveries worth another attempt: QUEUED for over {@code queuedBefore} (the first attempt never
	 * finished), or FAILED with attempts left once the back-off (1 minute after the first failure, 5 after
	 * the second) has passed. Notifications older than {@code freshAfter} are not worth a push any more.
	 */
	public List<DeliveryJob> due(Instant now, Instant queuedBefore, Instant freshAfter, int limit) {
		return this.named.query(JOB_SELECT + """
				 and n.created_at > :fresh and (
				   (d.status = 'QUEUED' and d.queued_at < :queuedBefore)
				   or (d.status = 'FAILED' and d.attempt_count < :max and d.last_attempt_at +
				       case when d.attempt_count <= 1 then interval '1 minute' else interval '5 minutes' end <= :now))
				order by d.queued_at limit :limit""",
				new MapSqlParameterSource("fresh", Timestamp.from(freshAfter))
					.addValue("queuedBefore", Timestamp.from(queuedBefore))
					.addValue("max", MAX_ATTEMPTS)
					.addValue("now", Timestamp.from(now))
					.addValue("limit", limit),
				(rs, i) -> job(rs));
	}

	public void markSent(UUID deliveryId, String providerMessageId, Instant now) {
		this.jdbc.update("""
				update notification_deliveries set status = 'SENT', attempt_count = attempt_count + 1,
				  provider_message_id = ?, last_error = null, last_attempt_at = ?, sent_at = ?
				where id = ?""", providerMessageId, Timestamp.from(now), Timestamp.from(now), deliveryId);
	}

	public void markFailed(UUID deliveryId, String error, Instant now) {
		this.jdbc.update("""
				update notification_deliveries set status = 'FAILED', attempt_count = attempt_count + 1,
				  last_error = ?, last_attempt_at = ? where id = ?""", error, Timestamp.from(now), deliveryId);
	}

	/** Pending pushes of a device that now belongs to another user must not reach the new owner. */
	public int blockPendingOfDevice(UUID deviceId, UUID newOwnerId) {
		return this.jdbc.update("""
				update notification_deliveries d set status = 'SUPPRESSED', suppressed_reason = 'BLOCKED'
				from notifications n
				where n.id = d.notification_id and d.push_device_id = ? and n.user_id <> ?
				  and d.status in ('QUEUED', 'FAILED')""", deviceId, newOwnerId);
	}

	private DeliveryJob job(java.sql.ResultSet rs) throws java.sql.SQLException {
		String data = rs.getString("data");
		return new DeliveryJob(rs.getObject("delivery_id", UUID.class), rs.getObject("notification_id", UUID.class),
				rs.getObject("push_device_id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("provider"), rs.getString("push_token"), rs.getString("title"), rs.getString("body"),
				(data != null) ? this.json.readValue(data, MAP) : Map.of(), "URGENT".equals(rs.getString("priority")),
				rs.getInt("attempt_count"));
	}

	public record DeliveryJob(UUID deliveryId, UUID notificationId, UUID deviceId, UUID userId, String provider,
			String token, String title, String body, Map<String, Object> data, boolean urgent, int attemptCount) {
	}

}
