package com.nemaz.school.moderation.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;

/** The database row of a moderation case as the services read it. */
final class CaseRows {

	private CaseRows() {
	}

	static final String COLUMNS = """
			c.id, c.school_id, c.queue, c.target_type, c.target_id, c.subject_user_id, c.priority, c.status, c.assigned_to,
			c.decision, c.decided_by, c.decided_at, c.report_count, c.reporter_count, c.lead_alerted_at,
			c.safeguarding_incident_id, c.legal_hold, c.resolved_at, c.created_at, c.updated_at""";

	record CaseRow(UUID id, UUID schoolId, String queue, String targetType, UUID targetId, UUID subjectUserId, String priority,
			String status, UUID assignedTo, String decision, UUID decidedBy, Instant decidedAt, int reportCount,
			int reporterCount, Instant leadAlertedAt, UUID incidentId, boolean legalHold, Instant resolvedAt, Instant createdAt,
			Instant updatedAt) {

		boolean open() {
			return "OPEN".equals(this.status) || "ASSIGNED".equals(this.status);
		}

	}

	static CaseRow caseRow(ResultSet rs, int row) throws SQLException {
		return new CaseRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), rs.getString("queue"), rs.getString("target_type"),
				Rows.uuid(rs, "target_id"), Rows.uuid(rs, "subject_user_id"), rs.getString("priority"), rs.getString("status"),
				Rows.uuid(rs, "assigned_to"), rs.getString("decision"), Rows.uuid(rs, "decided_by"),
				Rows.instant(rs, "decided_at"), rs.getInt("report_count"), rs.getInt("reporter_count"),
				Rows.instant(rs, "lead_alerted_at"), Rows.uuid(rs, "safeguarding_incident_id"), rs.getBoolean("legal_hold"),
				Rows.instant(rs, "resolved_at"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"));
	}

	static int rank(String priority) {
		return switch (priority) {
			case "CRITICAL" -> 3;
			case "HIGH" -> 2;
			case "NORMAL" -> 1;
			default -> 0;
		};
	}

}
