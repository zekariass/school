package com.nemaz.school.safeguarding.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentSummary;

/** The database row of an incident as the services read it (the text columns stay encrypted here). */
final class IncidentRows {

	private IncidentRows() {
	}

	static final String COLUMNS = """
			i.id, i.school_id, i.raised_by, i.source_case_id, i.concerned_student_id, i.involved_user_ids, i.category,
			i.severity, i.status, i.assigned_to, i.narrative_enc, i.referred_to_enc, i.referred_at, i.guardian_informed_at,
			i.closed_at, i.legal_hold, i.created_at, i.updated_at""";

	record IncidentRow(UUID id, UUID schoolId, UUID raisedBy, UUID sourceCaseId, UUID concernedStudentId,
			List<UUID> involved, String category, String severity, String status, UUID assignedTo, String narrativeEnc,
			String referredToEnc, Instant referredAt, Instant guardianInformedAt, Instant closedAt, boolean legalHold,
			Instant createdAt, Instant updatedAt) {

		IncidentSummary summary() {
			return new IncidentSummary(this.id, this.schoolId, this.category, this.severity, this.status, this.assignedTo,
					this.concernedStudentId, this.sourceCaseId, this.legalHold, this.guardianInformedAt, this.referredAt,
					this.createdAt, this.updatedAt, this.closedAt);
		}

	}

	static IncidentRow incident(ResultSet rs, int row) throws SQLException {
		List<UUID> involved = new ArrayList<>();
		java.sql.Array array = rs.getArray("involved_user_ids");
		if (array != null) {
			for (Object o : (Object[]) array.getArray()) {
				involved.add((UUID) o);
			}
		}
		return new IncidentRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "raised_by"),
				Rows.uuid(rs, "source_case_id"), Rows.uuid(rs, "concerned_student_id"), involved, rs.getString("category"),
				rs.getString("severity"), rs.getString("status"), Rows.uuid(rs, "assigned_to"), rs.getString("narrative_enc"),
				rs.getString("referred_to_enc"), Rows.instant(rs, "referred_at"), Rows.instant(rs, "guardian_informed_at"),
				Rows.instant(rs, "closed_at"), rs.getBoolean("legal_hold"), Rows.instant(rs, "created_at"),
				Rows.instant(rs, "updated_at"));
	}

}
