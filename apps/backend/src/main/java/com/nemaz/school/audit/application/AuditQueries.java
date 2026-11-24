package com.nemaz.school.audit.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the audit log newest first, keyset-paginated on (occurred_at desc, id desc). */
@Service
public class AuditQueries {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	AuditQueries(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public CursorPage<AuditRecord> search(AuditFilter filter, String cursor, Integer limit) {
		int pageSize = Cursors.clampLimit(limit);
		StringBuilder sql = new StringBuilder("""
				select id, occurred_at, actor_user_id, actor_roles, impersonator_user_id, school_id, action, target_type,
				       target_id, outcome, request_id, host(ip) as ip, user_agent, changes::text as changes,
				       metadata::text as metadata
				from audit_logs where true""");
		List<Object> args = new ArrayList<>();
		if (filter.actionPrefix() != null && !filter.actionPrefix().isBlank()) {
			sql.append(" and action like ? escape '\\'");
			args.add(escapeLike(filter.actionPrefix()) + "%");
		}
		if (filter.actorUserId() != null) {
			sql.append(" and actor_user_id = ?");
			args.add(filter.actorUserId());
		}
		if (filter.targetType() != null && !filter.targetType().isBlank()) {
			sql.append(" and target_type = ?");
			args.add(filter.targetType());
		}
		if (filter.targetId() != null) {
			sql.append(" and target_id = ?");
			args.add(filter.targetId());
		}
		if (filter.schoolId() != null) {
			sql.append(" and school_id = ?");
			args.add(filter.schoolId());
		}
		if (filter.from() != null) {
			sql.append(" and occurred_at >= ?");
			args.add(Timestamp.from(filter.from()));
		}
		if (filter.to() != null) {
			sql.append(" and occurred_at < ?");
			args.add(Timestamp.from(filter.to()));
		}
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				Instant at = Instant.parse(parts.get(0));
				long id = Long.parseLong(parts.get(1));
				sql.append(" and (occurred_at, id) < (?, ?)");
				args.add(Timestamp.from(at));
				args.add(id);
			}
			catch (DateTimeParseException | NumberFormatException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		sql.append(" order by occurred_at desc, id desc limit ?");
		args.add(pageSize + 1);
		List<AuditRecord> rows = this.jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
		return CursorPage.fromOverfetch(rows, pageSize,
				(row) -> Cursors.encode(row.occurredAt().toString(), Long.toString(row.id())));
	}

	private AuditRecord map(ResultSet rs) throws SQLException {
		java.sql.Array roles = rs.getArray("actor_roles");
		return new AuditRecord(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
				rs.getObject("actor_user_id", UUID.class),
				(roles != null) ? Arrays.asList((String[]) roles.getArray()) : null,
				rs.getObject("impersonator_user_id", UUID.class), rs.getObject("school_id", UUID.class),
				rs.getString("action"), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
				rs.getString("outcome"), rs.getString("request_id"), rs.getString("ip"), rs.getString("user_agent"),
				tree(rs.getString("changes")), tree(rs.getString("metadata")));
	}

	private JsonNode tree(String text) {
		return (text != null) ? this.json.readTree(text) : null;
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	public record AuditFilter(String actionPrefix, UUID actorUserId, String targetType, UUID targetId, UUID schoolId,
			Instant from, Instant to) {
	}

	public record AuditRecord(long id, Instant occurredAt, UUID actorUserId, List<String> actorRoles,
			UUID impersonatorUserId, UUID schoolId, String action, String targetType, UUID targetId, String outcome,
			String requestId, String ip, String userAgent, JsonNode changes, JsonNode metadata) {
	}

}
