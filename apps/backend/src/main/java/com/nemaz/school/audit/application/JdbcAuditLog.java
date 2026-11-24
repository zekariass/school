package com.nemaz.school.audit.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.common.web.RequestIds;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** {@link AuditLog} as a plain insert; JdbcTemplate joins the caller's (JPA) transaction. */
@Service
class JdbcAuditLog implements AuditLog {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final Clock clock;

	JdbcAuditLog(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public void record(AuditEntry entry) {
		CurrentUser user = CurrentUser.current().orElse(null);
		UUID actor;
		String[] roles;
		if (entry.actorOverridden()) {
			actor = entry.actorUserId();
			roles = (user != null && user.userId().equals(actor)) ? user.roleCodes().toArray(String[]::new) : null;
		}
		else {
			actor = (user != null) ? user.userId() : null;
			roles = (user != null) ? user.roleCodes().toArray(String[]::new) : null;
		}
		this.jdbc.update("""
				insert into audit_logs (occurred_at, actor_user_id, actor_roles, school_id, action, target_type, target_id,
				                        outcome, request_id, ip, user_agent, changes, metadata)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?::jsonb, ?::jsonb)""",
				Timestamp.from(this.clock.instant()), actor, roles, entry.schoolId(), entry.action(), entry.targetType(),
				entry.targetId(), entry.outcome().name(), RequestIds.current(), ClientRequests.ip(),
				ClientRequests.userAgent(), (entry.changes() != null) ? this.json.writeValueAsString(entry.changes()) : null,
				entry.metadata().isEmpty() ? null : this.json.writeValueAsString(entry.metadata()));
	}

}
