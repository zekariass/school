package com.nemaz.school.moderation.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.moderation.application.CaseRows.CaseRow;
import com.nemaz.school.moderation.application.ModerationModels.ActionView;
import com.nemaz.school.moderation.application.ModerationModels.CaseDetail;
import com.nemaz.school.moderation.application.ModerationModels.CaseFilter;
import com.nemaz.school.moderation.application.ModerationModels.CaseSummary;
import com.nemaz.school.moderation.application.ModerationModels.ReportEntry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queues and the case page (docs/Safeguarding_Rules.md S2.7). Every list is filtered in SQL, on the server: a school's queue
 * holds only cases of that school's SCHOOL queue, the platform queue only PLATFORM cases, and a moderator never sees a case about
 * themselves, their own content, their child or one they reported. Keyset paged (priority, then newest). A case is references and
 * enums only; reporter identity and the report details appear only for safeguarding leads and platform moderators.
 */
@Service
@Transactional(readOnly = true)
public class CaseQueries {

	private static final String VISIBLE_TO_ME = """
			 and (c.subject_user_id is null or c.subject_user_id <> :me)
			 and not exists (select 1 from student_guardians sg where sg.guardian_id = :me and sg.student_id = c.subject_user_id
			                 and sg.status in ('PENDING', 'ACTIVE'))
			 and not exists (select 1 from content_reports r where r.case_id = c.id and r.reporter_id = :me)""";

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	private final CaseAccess access;

	private final PeopleLookup people;

	CaseQueries(JdbcClient jdbc, SchoolGate gate, CaseAccess access, PeopleLookup people) {
		this.jdbc = jdbc;
		this.gate = gate;
		this.access = access;
		this.people = people;
	}

	/** The queue of a school (school.moderation.manage at the school; a member without it gets 403, a stranger 404). */
	public CursorPage<CaseSummary> schoolQueue(CurrentUser user, UUID schoolId, CaseFilter filter, String cursor, Integer limit) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_MODERATION_MANAGE);
		this.access.requireFeature(schoolId);
		return page(user, "c.queue = 'SCHOOL' and c.school_id = :school", Map.of("school", schoolId), filter, cursor, limit);
	}

	/** The platform queue (platform.moderation.manage, checked by the controller). */
	public CursorPage<CaseSummary> platformQueue(CurrentUser user, CaseFilter filter, String cursor, Integer limit) {
		return page(user, "c.queue = 'PLATFORM'", Map.of(), filter, cursor, limit);
	}

	private CursorPage<CaseSummary> page(CurrentUser user, String where, Map<String, Object> params, CaseFilter filter,
			String cursor, Integer limit) {
		int size = Cursors.clampLimit(limit);
		Integer cursorRank = null;
		Timestamp cursorAt = null;
		UUID cursorId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 3);
			try {
				cursorRank = Integer.parseInt(parts.get(0));
				cursorAt = Timestamp.from(Instant.EPOCH.plus(Long.parseLong(parts.get(1)), ChronoUnit.MICROS));
				cursorId = UUID.fromString(parts.get(2));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		String status = (filter.status() == null || filter.status().isBlank()) ? "OPEN" : filter.status();
		String statusSql = switch (status) {
			case "OPEN" -> "c.status in ('OPEN', 'ASSIGNED')";
			case "ALL" -> "true";
			case "ASSIGNED", "RESOLVED", "ESCALATED" -> "c.status = '" + status + "'";
			default -> throw ApiException.badRequest("moderation.filter_invalid");
		};
		if (filter.priority() != null && !filter.priority().isBlank() && !ModerationModels.PRIORITIES.contains(filter.priority())) {
			throw ApiException.badRequest("moderation.filter_invalid");
		}
		var statement = this.jdbc.sql("select " + CaseRows.COLUMNS + " from moderation_cases c where " + where + " and " + statusSql
				+ " " + VISIBLE_TO_ME + """
				 and (cast(:priority as text) is null or c.priority = :priority)
				 and (cast(:target_type as text) is null or c.target_type = :target_type)
				 and (cast(:cr as int) is null or ((case c.priority when 'CRITICAL' then 3 when 'HIGH' then 2 when 'NORMAL' then 1 else 0 end),
				        c.created_at, c.id) < (cast(:cr as int), cast(:ca as timestamptz), cast(:ci as uuid)))
				order by (case c.priority when 'CRITICAL' then 3 when 'HIGH' then 2 when 'NORMAL' then 1 else 0 end) desc,
				         c.created_at desc, c.id desc limit :n""")
			.param("me", user.userId())
			.param("priority", blank(filter.priority()))
			.param("target_type", blank(filter.targetType()))
			.param("cr", cursorRank)
			.param("ca", cursorAt)
			.param("ci", cursorId)
			.param("n", size + 1);
		for (Map.Entry<String, Object> p : params.entrySet()) {
			statement = statement.param(p.getKey(), p.getValue());
		}
		List<CaseRow> rows = statement.query(CaseRows::caseRow).list();
		boolean more = rows.size() > size;
		List<CaseRow> items = more ? rows.subList(0, size) : rows;
		Map<UUID, Map<String, Integer>> reasons = reasons(items);
		List<CaseSummary> summaries = new ArrayList<>();
		for (CaseRow row : items) {
			summaries.add(summary(row, reasons.getOrDefault(row.id(), Map.of())));
		}
		CaseRow last = items.isEmpty() ? null : items.getLast();
		String next = (more && last != null) ? Cursors.encode(String.valueOf(CaseRows.rank(last.priority())),
				String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, last.createdAt())), last.id().toString()) : null;
		return new CursorPage<>(summaries, next);
	}

	CaseSummary summary(CaseRow row, Map<String, Integer> reasons) {
		String subjectRole = (row.subjectUserId() != null) ? this.people.roleAt(row.subjectUserId(), row.schoolId()) : null;
		return new CaseSummary(row.id(), row.schoolId(), row.queue(), row.targetType(), row.targetId(), row.priority(),
				row.status(), row.assignedTo(), row.reportCount(), row.reporterCount(), reasons, subjectRole, row.decision(),
				row.legalHold(), row.createdAt(), row.updatedAt(), row.resolvedAt());
	}

	private Map<UUID, Map<String, Integer>> reasons(List<CaseRow> rows) {
		Map<UUID, Map<String, Integer>> byCase = new LinkedHashMap<>();
		if (rows.isEmpty()) {
			return byCase;
		}
		this.jdbc.sql("select case_id, reason_code, count(*) as n from content_reports where case_id in (:ids) group by case_id, reason_code")
			.param("ids", rows.stream().map(CaseRow::id).toList())
			.query((rs, i) -> {
				byCase.computeIfAbsent(Rows.uuid(rs, "case_id"), (k) -> new LinkedHashMap<>()).put(rs.getString("reason_code"), rs.getInt("n"));
				return 0;
			})
			.list();
		return byCase;
	}

	/** The case page: 404 for everybody who may not work the case. */
	public CaseDetail detail(CurrentUser user, UUID caseId) {
		CaseRow row = this.access.workable(user, caseId);
		return detailOf(user, row);
	}

	CaseDetail detailOf(CurrentUser user, CaseRow row) {
		boolean identity = this.access.sees(user, row);
		List<ReportEntry> reports = this.jdbc.sql("select reason_code, reporter_role, created_at, reporter_id, details from content_reports where case_id = :c order by created_at, id")
			.param("c", row.id())
			.query((rs, i) -> new ReportEntry(rs.getString("reason_code"), rs.getString("reporter_role"), Rows.instant(rs, "created_at"),
					identity ? Rows.uuid(rs, "reporter_id") : null, identity ? rs.getString("details") : null))
			.list();
		List<ActionView> actions = this.jdbc.sql("select id, actor_id, action, level, notes, created_at from moderation_actions where case_id = :c order by created_at, id")
			.param("c", row.id())
			.query((rs, i) -> new ActionView(Rows.uuid(rs, "id"), Rows.uuid(rs, "actor_id"), rs.getString("action"), rs.getString("level"),
					rs.getString("notes"), Rows.instant(rs, "created_at")))
			.list();
		Map<String, Integer> reasons = reasons(List.of(row)).getOrDefault(row.id(), Map.of());
		boolean incidentVisible = this.access.canReadIncidents(user, row);
		return new CaseDetail(summary(row, reasons), row.subjectUserId(), reports, actions, identity,
				incidentVisible ? row.incidentId() : null);
	}

	private static String blank(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

}
