package com.nemaz.school.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.notifications.domain.NotificationRow;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code notifications}: the inbox. Every query is scoped by {@code user_id}. */
@Component
public class NotificationStore {

	private static final String COLUMNS = """
			id, user_id, type_code, school_id, title, body, data::text as data, source_type, source_id, priority, read_at,
			created_at""";

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final JsonMapper json;

	NotificationStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, JsonMapper json) {
		this.jdbc = jdbc;
		this.named = named;
		this.json = json;
	}

	public void insert(List<NotificationRow> rows) {
		this.jdbc.batchUpdate("""
				insert into notifications (id, user_id, type_code, school_id, title, body, data, source_type, source_id,
				                           priority, created_at)
				values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", rows, rows.size(), (ps, r) -> {
			ps.setObject(1, r.id());
			ps.setObject(2, r.userId());
			ps.setString(3, r.typeCode());
			ps.setObject(4, r.schoolId());
			ps.setString(5, r.title());
			ps.setString(6, r.body());
			ps.setString(7, r.data().isEmpty() ? null : this.json.writeValueAsString(r.data()));
			ps.setString(8, r.sourceType());
			ps.setObject(9, r.sourceId());
			ps.setString(10, r.priority());
			ps.setTimestamp(11, Timestamp.from(r.createdAt()));
		});
	}

	public boolean existsForSource(UUID userId, String typeCode, String sourceType, UUID sourceId) {
		Boolean exists = this.jdbc.queryForObject("""
				select exists (select 1 from notifications
				               where user_id = ? and type_code = ? and source_type = ? and source_id = ?)""",
				Boolean.class, userId, typeCode, sourceType, sourceId);
		return Boolean.TRUE.equals(exists);
	}

	public boolean existsUnreadForSource(UUID userId, String typeCode, String sourceType, UUID sourceId) {
		Boolean exists = this.jdbc.queryForObject("""
				select exists (select 1 from notifications
				               where user_id = ? and type_code = ? and source_type = ? and source_id = ?
				                 and read_at is null and archived_at is null)""", Boolean.class, userId, typeCode, sourceType,
				sourceId);
		return Boolean.TRUE.equals(exists);
	}

	/** Marks the user's unread notifications of these types for the source as read; returns their ids. */
	public List<UUID> markReadBySource(UUID userId, Collection<String> typeCodes, String sourceType, UUID sourceId, Instant now) {
		if (typeCodes.isEmpty()) {
			return List.of();
		}
		return this.named.query("""
				update notifications set read_at = :now
				where user_id = :user and type_code in (:types) and source_type = :sourceType and source_id = :sourceId
				  and read_at is null returning id""",
				new MapSqlParameterSource("now", Timestamp.from(now)).addValue("user", userId)
					.addValue("types", typeCodes)
					.addValue("sourceType", sourceType)
					.addValue("sourceId", sourceId),
				(rs, i) -> rs.getObject("id", UUID.class));
	}

	public List<NotificationRow> findByIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return this.named.query("select " + COLUMNS + " from notifications where id in (:ids)",
				new MapSqlParameterSource("ids", ids), (rs, i) -> map(rs));
	}

	public Optional<NotificationRow> find(UUID userId, UUID id) {
		return this.jdbc
			.query("select " + COLUMNS + " from notifications where id = ? and user_id = ?", (rs, i) -> map(rs), id,
					userId)
			.stream()
			.findFirst();
	}

	/** Newest first by (created_at, id), after the keyset position when given; fetches {@code limit} rows. */
	public List<NotificationRow> list(UUID userId, boolean unreadOnly, Position after, int limit) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from notifications where user_id = :user and archived_at is null");
		MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("limit", limit);
		if (unreadOnly) {
			sql.append(" and read_at is null");
		}
		if (after != null) {
			sql.append(" and (created_at, id) < (:createdAt, :id)");
			params.addValue("createdAt", Timestamp.from(after.createdAt())).addValue("id", after.id());
		}
		sql.append(" order by created_at desc, id desc limit :limit");
		return this.named.query(sql.toString(), params, (rs, i) -> map(rs));
	}

	public int unreadCount(UUID userId) {
		Integer count = this.jdbc.queryForObject(
				"select count(*) from notifications where user_id = ? and read_at is null and archived_at is null",
				Integer.class, userId);
		return (count != null) ? count : 0;
	}

	public Map<UUID, Integer> unreadCounts(Collection<UUID> userIds) {
		Map<UUID, Integer> counts = new HashMap<>();
		userIds.forEach((id) -> counts.put(id, 0));
		if (userIds.isEmpty()) {
			return counts;
		}
		this.named.query("""
				select user_id, count(*) as unread from notifications
				where user_id in (:users) and read_at is null and archived_at is null group by user_id""",
				new MapSqlParameterSource("users", userIds),
				(rs) -> {
					counts.put(rs.getObject("user_id", UUID.class), rs.getInt("unread"));
				});
		return counts;
	}

	/** Marks one notification of the user read; false when the user has no such notification. */
	public boolean markRead(UUID userId, UUID id, Instant now) {
		return this.jdbc.update("""
				update notifications set read_at = coalesce(read_at, ?) where id = ? and user_id = ?""",
				Timestamp.from(now), id, userId) > 0;
	}

	public int markAllRead(UUID userId, Instant now) {
		return this.jdbc.update("""
				update notifications set read_at = ? where user_id = ? and read_at is null and archived_at is null""",
				Timestamp.from(now), userId);
	}

	public boolean archive(UUID userId, UUID id, Instant now) {
		return this.jdbc.update("""
				update notifications set archived_at = coalesce(archived_at, ?) where id = ? and user_id = ?""",
				Timestamp.from(now), id, userId) > 0;
	}

	private NotificationRow map(ResultSet rs) throws SQLException {
		String data = rs.getString("data");
		Timestamp read = rs.getTimestamp("read_at");
		return new NotificationRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("type_code"), rs.getObject("school_id", UUID.class), rs.getString("title"),
				rs.getString("body"), (data != null) ? this.json.readValue(data, MAP) : Map.of(),
				rs.getString("source_type"), rs.getObject("source_id", UUID.class), rs.getString("priority"),
				(read != null) ? read.toInstant() : null, rs.getTimestamp("created_at").toInstant());
	}

	/** A keyset position in the inbox order. Timestamps have microsecond precision in PostgreSQL. */
	public record Position(Instant createdAt, UUID id) {

		public long micros() {
			return ChronoUnit.MICROS.between(Instant.EPOCH, this.createdAt);
		}

		public static Position of(long micros, UUID id) {
			return new Position(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
		}

	}

}
