package com.nemaz.school.portfolio.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioCapabilities;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioHistoryEntry;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemType;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioViews.Relation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and lists (rule book P7). Every list is filtered in SQL by {@link PortfolioAudience#viewable} (the same predicate as the single
 * read) plus the list's own condition, keyset paged, and shown through {@link PortfolioViews}. A school, student, class or item the
 * caller cannot see is 404; a member who may not use the list is 403.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioQueries {

	private final JdbcClient jdbc;

	private final PortfolioAccess access;

	private final PortfolioAudience audience;

	private final PortfolioViews views;

	private final PortfolioLedger ledger;

	private final PortfolioSettings settings;

	private final SchoolGate gate;

	private final AuditLog audit;

	PortfolioQueries(JdbcClient jdbc, PortfolioAccess access, PortfolioAudience audience, PortfolioViews views, PortfolioLedger ledger,
			PortfolioSettings settings, SchoolGate gate, AuditLog audit) {
		this.jdbc = jdbc;
		this.access = access;
		this.audience = audience;
		this.views = views;
		this.ledger = ledger;
		this.settings = settings;
		this.gate = gate;
		this.audit = audit;
	}

	/** Optional filters of the student's portfolio. */
	public record Filter(PortfolioStatus status, PortfolioItemType itemType, UUID classId, UUID subjectId, Boolean featured) {

		static Filter none() {
			return new Filter(null, null, null, null, null);
		}

	}

	// --- one item ----------------------------------------------------------------------------------

	/** One item. Oversight reads (only through {@code school.portfolios.review}) are audited (spec §131). */
	@Transactional
	public PortfolioItemView item(CurrentUser user, UUID schoolId, UUID itemId) {
		ItemRow item = visible(user, schoolId, itemId);
		if (this.audience.oversightOnly(user, itemId)) {
			this.audit.record(AuditEntry.of("portfolio.item.read_oversight", AuditOutcome.SUCCESS).target("portfolio_item", itemId).school(schoolId));
		}
		return this.views.view(user, item);
	}

	/** The status history: the student and the reviewers only. */
	public List<PortfolioHistoryEntry> history(CurrentUser user, UUID schoolId, UUID itemId) {
		ItemRow item = visible(user, schoolId, itemId);
		Relation rel = this.views.relations(user, List.of(item)).getFirst();
		if (!rel.self() && !rel.reviewer()) {
			throw ApiException.forbidden("portfolio.history_not_allowed");
		}
		return this.ledger.history(item);
	}

	private ItemRow visible(CurrentUser user, UUID schoolId, UUID itemId) {
		this.access.requireSchool(user, schoolId);
		return this.audience.find(user, itemId).filter((i) -> i.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
	}

	// --- lists -------------------------------------------------------------------------------------

	/** A student's portfolio: for the student, their own guardians, their teachers and reviewers; everybody else gets 404. */
	public CursorPage<PortfolioItemView> studentPortfolio(CurrentUser user, UUID schoolId, UUID studentId, Filter filter, String cursor,
			Integer limit) {
		this.access.requireSchool(user, schoolId);
		if (!mayOpenPortfolioOf(user, schoolId, studentId)) {
			throw ApiException.notFound("student.not_found");
		}
		Map<String, Object> params = new HashMap<>();
		StringBuilder where = new StringBuilder("i.school_id = :q_school and i.student_id = :q_student");
		params.put("q_school", schoolId);
		params.put("q_student", studentId);
		Filter f = (filter != null) ? filter : Filter.none();
		if (f.status() != null) {
			where.append(" and i.status = :q_status");
			params.put("q_status", f.status().name());
		}
		if (f.itemType() != null) {
			where.append(" and i.item_type = :q_type");
			params.put("q_type", f.itemType().name());
		}
		if (f.classId() != null) {
			where.append(" and i.class_id = :q_class");
			params.put("q_class", f.classId());
		}
		if (f.subjectId() != null) {
			where.append(" and i.subject_id = :q_subject");
			params.put("q_subject", f.subjectId());
		}
		if (f.featured() != null) {
			where.append(" and i.is_featured = :q_featured");
			params.put("q_featured", f.featured());
		}
		return newestFirst(user, List.of(schoolId), where.toString(), params, cursor, limit);
	}

	/** The items the caller created or that are about them, in every status they may see. */
	public CursorPage<PortfolioItemView> mine(CurrentUser user, UUID schoolId, PortfolioStatus status, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		Map<String, Object> params = new HashMap<>();
		params.put("q_school", schoolId);
		params.put("q_me", user.userId());
		String where = "i.school_id = :q_school and (i.student_id = :q_me or i.created_by = :q_me)";
		if (status != null) {
			where += " and i.status = :q_status";
			params.put("q_status", status.name());
		}
		return newestFirst(user, List.of(schoolId), where, params, cursor, limit);
	}

	/** SUBMITTED items of the class, oldest first, for reviewers (a subject teacher sees their subject's items only). */
	public CursorPage<PortfolioItemView> reviewQueue(CurrentUser user, UUID schoolId, UUID classId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		if (!this.access.reviewsClass(user, ref)) {
			throw ApiException.forbidden("portfolio.review_not_allowed");
		}
		SqlFragment reviewers = this.audience.reviewers(user, "i");
		SqlFragment visible = this.audience.viewable(user, "i", List.of(schoolId));
		int size = Cursors.clampLimit(limit);
		Map<String, Object> params = new HashMap<>(visible.params());
		params.putAll(reviewers.params());
		params.put("q_class", classId);
		String position = "";
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				params.put("q_at", Timestamp.from(Instant.parse(parts.get(0))));
				params.put("q_id", UUID.fromString(parts.get(1)));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
			position = " and (i.submitted_at, i.id) > (:q_at, :q_id)";
		}
		List<ItemRow> rows = this.jdbc.sql("select " + PortfolioRows.ITEM_COLUMNS + " from portfolio_items i where i.class_id = :q_class"
				+ " and i.status = 'SUBMITTED' and (" + visible.sql() + ") and (" + reviewers.sql() + ")" + position
				+ " order by i.submitted_at, i.id limit " + (size + 1))
			.params(params)
			.query(PortfolioRows::item)
			.list();
		CursorPage<ItemRow> page = CursorPage.fromOverfetch(rows, size,
				(r) -> Cursors.encode(r.submittedAt().toString(), r.id().toString()));
		return new CursorPage<>(this.views.views(user, page.items()), page.nextCursor());
	}

	/** Approved items of the class whose effective visibility is CLASS or SCHOOL, for the class's members. */
	public CursorPage<PortfolioItemView> classGallery(CurrentUser user, UUID schoolId, UUID classId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		this.access.viewableClass(user, schoolId, classId);
		SqlFragment rank = this.audience.effectiveRank(List.of(schoolId), "i");
		Map<String, Object> params = new HashMap<>(rank.params());
		params.put("q_class", classId);
		return newestFirst(user, List.of(schoolId), "i.class_id = :q_class and i.status = 'APPROVED' and " + rank.sql() + " >= 2", params,
				cursor, limit);
	}

	/** Approved items whose effective visibility is SCHOOL, for the school's active members. */
	public CursorPage<PortfolioItemView> schoolGallery(CurrentUser user, UUID schoolId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		if (!this.access.people().isActiveStudent(schoolId, user.userId()) && !isActiveStaff(user, schoolId)
				&& !this.gate.can(user, schoolId, Permissions.SCHOOL_PORTFOLIOS_REVIEW)) {
			throw ApiException.forbidden("portfolio.access_denied");
		}
		SqlFragment rank = this.audience.effectiveRank(List.of(schoolId), "i");
		Map<String, Object> params = new HashMap<>(rank.params());
		params.put("q_school", schoolId);
		return newestFirst(user, List.of(schoolId), "i.school_id = :q_school and i.status = 'APPROVED' and " + rank.sql() + " >= 3", params,
				cursor, limit);
	}

	/** A guardian's combined view across their children: APPROVED and guardian-visible items only, optionally one child. */
	public CursorPage<PortfolioItemView> myChildren(CurrentUser user, UUID studentId, String cursor, Integer limit) {
		Set<UUID> guarded = new LinkedHashSet<>(this.access.guardedStudents(user));
		if (studentId != null) {
			if (!guarded.contains(studentId)) {
				throw ApiException.notFound("student.not_found");
			}
			guarded = Set.of(studentId);
		}
		if (guarded.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		List<UUID> schools = this.jdbc.sql("select distinct school_id from school_students where student_id in (:s) and status = 'ACTIVE'")
			.param("s", guarded)
			.query(UUID.class)
			.list();
		if (schools.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		List<UUID> enabled = schools.stream().filter(this.settings::featureEnabled).toList();
		if (enabled.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		SqlFragment rank = this.audience.effectiveRank(enabled, "i");
		Map<String, Object> params = new HashMap<>(rank.params());
		params.put("q_children", guarded);
		params.put("q_schools", enabled);
		return newestFirst(user, enabled, "i.student_id in (:q_children) and i.school_id in (:q_schools) and i.status = 'APPROVED' and "
				+ rank.sql() + " >= 1", params, cursor, limit);
	}

	// --- capabilities ------------------------------------------------------------------------------

	public PortfolioCapabilities capabilities(CurrentUser user, UUID schoolId) {
		this.access.requireSchool(user, schoolId);
		boolean student = this.access.people().isActiveStudent(schoolId, user.userId()) && this.settings.studentCreate(schoolId)
				&& this.access.schoolActive(schoolId);
		boolean assistants = this.settings.assistantsCapture(schoolId);
		List<UUID> capture = this.jdbc.sql("""
				select ct.class_id from class_teachers ct join classes c on c.id = ct.class_id and c.school_id = :school
				where ct.teacher_id = :user and ct.status = 'ACTIVE' and c.status <> 'ARCHIVED'
				  and (ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER') or (ct.role = 'ASSISTANT' and :assistants))
				order by ct.class_id""")
			.param("school", schoolId)
			.param("user", user.userId())
			.param("assistants", assistants)
			.query(UUID.class)
			.list();
		SqlFragment managed = this.access.classes().manageableClasses(user, "c");
		boolean reviewer = this.gate.can(user, schoolId, Permissions.SCHOOL_PORTFOLIOS_REVIEW)
				|| this.jdbc.sql("""
						select exists (select 1 from class_teachers ct join classes c on c.id = ct.class_id
						  where c.school_id = :school and ct.teacher_id = :user and ct.status = 'ACTIVE'
						    and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))
						  or exists (select 1 from classes c where c.school_id = :school and (""" + managed.sql() + "))")
					.param("school", schoolId)
					.param("user", user.userId())
					.params(managed.params())
					.query(Boolean.class)
					.single();
		List<PortfolioItemType> supported = new ArrayList<>(List.of(PortfolioItemType.TEXT, PortfolioItemType.PHOTO,
				PortfolioItemType.DRAWING, PortfolioItemType.SCAN, PortfolioItemType.DOCUMENT));
		return new PortfolioCapabilities(student, !capture.isEmpty(), capture, reviewer, this.settings.maxVisibility(schoolId),
				this.settings.guardianComments(schoolId), false, supported,
				List.of(PortfolioItemType.AUDIO, PortfolioItemType.VIDEO));
	}

	// --- helpers -----------------------------------------------------------------------------------

	private boolean isActiveStaff(CurrentUser user, UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
	}

	/** Who may open a student's portfolio: the student, their own guardians, their teachers, class managers, oversight holders. */
	private boolean mayOpenPortfolioOf(CurrentUser user, UUID schoolId, UUID studentId) {
		if (user.userId().equals(studentId)) {
			return this.access.people().isActiveStudent(schoolId, studentId);
		}
		if (this.access.isGuardianOf(user, studentId)) {
			return this.access.people().isActiveStudent(schoolId, studentId);
		}
		if (this.gate.can(user, schoolId, Permissions.SCHOOL_PORTFOLIOS_REVIEW)) {
			return true;
		}
		if (this.access.memberships().teachesStudent(user.userId(), studentId)) {
			return true;
		}
		return this.access.memberships()
			.activeClassesOf(List.of(studentId))
			.getOrDefault(studentId, List.of())
			.stream()
			.anyMatch((c) -> this.access.classes().canManage(user, c.classId()));
	}

	/** Newest first by id (UUIDv7 ids are creation order), keyset paged. */
	private CursorPage<PortfolioItemView> newestFirst(CurrentUser user, List<UUID> schools, String where, Map<String, Object> params,
			String cursor, Integer limit) {
		SqlFragment visible = this.audience.viewable(user, "i", schools);
		int size = Cursors.clampLimit(limit);
		Map<String, Object> all = new HashMap<>(visible.params());
		all.putAll(params);
		String position = "";
		if (cursor != null) {
			try {
				all.put("q_after", UUID.fromString(Cursors.decode(cursor, 1).getFirst()));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
			position = " and i.id < :q_after";
		}
		List<ItemRow> rows = this.jdbc.sql("select " + PortfolioRows.ITEM_COLUMNS + " from portfolio_items i where (" + where + ") and ("
				+ visible.sql() + ")" + position + " order by i.id desc limit " + (size + 1))
			.params(all)
			.query(PortfolioRows::item)
			.list();
		CursorPage<ItemRow> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(r.id().toString()));
		return new CursorPage<>(this.views.views(user, page.items()), page.nextCursor());
	}

}
