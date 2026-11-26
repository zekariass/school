package com.nemaz.school.moderation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.text.PlainText;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.moderation.CaseDecided;
import com.nemaz.school.moderation.ModerationTarget;
import com.nemaz.school.moderation.ReasonCodes;
import com.nemaz.school.moderation.ReviewContent;
import com.nemaz.school.moderation.SchoolSuspension;
import com.nemaz.school.moderation.application.CaseRows.CaseRow;
import com.nemaz.school.moderation.application.ModerationModels.CaseDetail;
import com.nemaz.school.moderation.application.ModerationModels.DecisionRequest;
import com.nemaz.school.moderation.application.ModerationModels.HoldView;
import com.nemaz.school.safeguarding.IncidentIntake;
import com.nemaz.school.system.RateLimiter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Working a case (docs/Safeguarding_Rules.md S2.4 to S2.6, S3). Every method runs in one transaction, takes the case's row lock,
 * re-checks who may work it (queue permission and no conflict: 404 otherwise) and appends a row to the append-only
 * {@code moderation_actions}. Decisions carry a reason of 10 to 500 characters that lives only in that row (never in the audit
 * log, events or notifications). Hiding and removing are carried out by the owning module through {@link ModerationTarget};
 * the original is retained.
 */
@Service
public class CaseService {

	static final int MIN_REASON = 10;

	static final int MAX_REASON = 500;

	static final int CONTENT_READS_PER_HOUR = 60;

	private final JdbcClient jdbc;

	private final CaseAccess access;

	private final AccessControl accessControl;

	private final TargetRegistry registry;

	private final ObjectProvider<SchoolSuspension> suspensions;

	private final UserAccounts accounts;

	private final IncidentIntake incidents;

	private final ModerationNotifier notifier;

	private final CaseQueries queries;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	CaseService(JdbcClient jdbc, CaseAccess access, AccessControl accessControl, TargetRegistry registry,
			ObjectProvider<SchoolSuspension> suspensions, UserAccounts accounts, IncidentIntake incidents,
			ModerationNotifier notifier, CaseQueries queries, AuditLog audit, RateLimiter limiter,
			ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.accessControl = accessControl;
		this.registry = registry;
		this.suspensions = suspensions;
		this.accounts = accounts;
		this.incidents = incidents;
		this.notifier = notifier;
		this.queries = queries;
		this.audit = audit;
		this.limiter = limiter;
		this.events = events;
		this.clock = clock;
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
	}

	/** The case, workable by the caller, with its row locked. */
	private CaseRow locked(CurrentUser user, UUID caseId) {
		this.access.workable(user, caseId);
		return this.jdbc.sql("select " + CaseRows.COLUMNS + " from moderation_cases c where c.id = :id for update")
			.param("id", caseId)
			.query(CaseRows::caseRow)
			.single();
	}

	private static String reason(String raw) {
		String clean = (raw == null) ? "" : PlainText.clean(raw);
		if (clean.length() < MIN_REASON) {
			throw ApiException.badRequest("moderation.reason_required");
		}
		if (clean.length() > MAX_REASON) {
			throw ApiException.badRequest("moderation.reason_too_long");
		}
		return clean;
	}

	private UUID record(UUID caseId, UUID actor, String action, String level, String notes) {
		UUID id = Ids.newId();
		this.jdbc.sql("insert into moderation_actions (id, case_id, actor_id, action, level, notes, created_at) values (:id, :c, :a, :action, :level, :notes, :now)")
			.param("id", id)
			.param("c", caseId)
			.param("a", actor)
			.param("action", action)
			.param("level", level)
			.param("notes", notes)
			.param("now", now())
			.update();
		return id;
	}

	private void touch(UUID caseId) {
		this.jdbc.sql("update moderation_cases set updated_at = :now where id = :id").param("now", now()).param("id", caseId).update();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// assignment

	/** Assigns (or reassigns) the case; without an assignee the caller takes it. Audited, the assignee is notified. */
	@Transactional
	public CaseDetail assign(CurrentUser user, UUID caseId, UUID assigneeId) {
		CaseRow row = locked(user, caseId);
		if (!row.open()) {
			throw ApiException.conflict("moderation.case_closed");
		}
		UUID assignee = (assigneeId != null) ? assigneeId : user.userId();
		if (!assignee.equals(user.userId())) {
			CurrentUser other = this.access.asUser(assignee);
			if (!this.access.canWorkQueue(other, row) || this.access.conflicted(assignee, row)) {
				throw ApiException.badRequest("moderation.assignee_invalid");
			}
		}
		String action = (row.assignedTo() == null) ? "ASSIGN" : "REASSIGN";
		this.jdbc.sql("update moderation_cases set status = 'ASSIGNED', assigned_to = :a, updated_at = :now where id = :id")
			.param("a", assignee)
			.param("now", now())
			.param("id", caseId)
			.update();
		UUID actionId = record(caseId, user.userId(), action, null, null);
		if (!assignee.equals(user.userId())) {
			this.notifier.assigned(assignee, row.schoolId(), actionId);
		}
		this.audit.record(AuditEntry.of("moderation.case." + action.toLowerCase(), AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId())
			.metadata("assigneeId", assignee.toString()));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// decisions

	@Transactional
	public CaseDetail decide(CurrentUser user, UUID caseId, DecisionRequest request) {
		CaseRow row = locked(user, caseId);
		if (!row.open()) {
			throw ApiException.conflict("moderation.case_closed");
		}
		String decision = request.decision();
		if (decision == null || !ModerationModels.DECISIONS.contains(decision)) {
			throw ApiException.badRequest("moderation.decision_invalid");
		}
		String reason = reason(request.reason());
		String level = null;
		String finalStatus = "RESOLVED";
		switch (decision) {
			case "NO_ACTION" -> {
			}
			case "HIDE", "REMOVE" -> {
				ModerationTarget target = this.registry.moderationTarget(row.targetType())
					.orElseThrow(() -> ApiException.badRequest("moderation.decision_not_applicable"));
				target.takeDown(row.targetType(), row.targetId(), caseId, user.userId(), "REMOVE".equals(decision));
			}
			case "WARN_USER" -> {
				UUID warningId = warn(user, row);
				this.notifier.warned(row.subjectUserId(), row.schoolId(), warningId);
			}
			case "SUSPEND_USER" -> {
				level = suspend(user, row, request.level());
			}
			case "ESCALATE" -> {
				escalate(user, row, request, reason);
				finalStatus = "ESCALATED";
			}
			default -> throw ApiException.badRequest("moderation.decision_invalid");
		}
		this.jdbc.sql("""
				update moderation_cases set status = :status, decision = :decision, decided_by = :by, decided_at = :now,
				       resolved_at = :now, updated_at = :now where id = :id""")
			.param("status", finalStatus)
			.param("decision", decision)
			.param("by", user.userId())
			.param("now", now())
			.param("id", caseId)
			.update();
		// the reason of an escalation became the narrative of the incident: it is not kept in the case history
		record(caseId, user.userId(), decision, level, "ESCALATE".equals(decision) ? null : reason);
		closeReports(row);
		this.audit.record(AuditEntry.of("moderation.case.decide", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId())
			.metadata("decision", decision)
			.metadata("level", level));
		this.events.publishEvent(new CaseDecided(caseId, row.schoolId(), row.targetType(), row.targetId(), decision));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	private UUID warn(CurrentUser user, CaseRow row) {
		if (row.subjectUserId() == null) {
			throw ApiException.badRequest("moderation.decision_not_applicable");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("insert into account_warnings (id, user_id, school_id, case_id, issued_by, created_at) values (:id, :u, :s, :c, :by, :now)")
			.param("id", id)
			.param("u", row.subjectUserId())
			.param("s", row.schoolId())
			.param("c", row.id())
			.param("by", user.userId())
			.param("now", now())
			.update();
		return id;
	}

	/** SCHOOL (default, reversible, at the school) or PLATFORM (an account lock: platform.users.lock only). Returns the level. */
	private String suspend(CurrentUser user, CaseRow row, String requested) {
		if (row.subjectUserId() == null) {
			throw ApiException.badRequest("moderation.decision_not_applicable");
		}
		String level = (requested == null || requested.isBlank()) ? "SCHOOL" : requested;
		if ("PLATFORM".equals(level)) {
			if (!this.accessControl.can(user, Permissions.PLATFORM_USERS_LOCK, AccessScope.platform())) {
				throw ApiException.forbidden("moderation.platform_lock_not_allowed");
			}
			if (!this.accounts.lock(row.subjectUserId()) && !isLocked(row.subjectUserId())) {
				throw ApiException.conflict("moderation.user_not_lockable");
			}
			this.audit.record(AuditEntry.of("moderation.user.lock", AuditOutcome.SUCCESS)
				.target("user", row.subjectUserId())
				.school(row.schoolId())
				.metadata("caseId", row.id().toString()));
			return "PLATFORM";
		}
		if (!"SCHOOL".equals(level)) {
			throw ApiException.badRequest("moderation.level_invalid");
		}
		if (row.schoolId() == null) {
			throw ApiException.badRequest("moderation.decision_not_applicable");
		}
		boolean already = this.jdbc.sql("select exists (select 1 from school_suspensions where user_id = :u and school_id = :s and lifted_at is null)")
			.param("u", row.subjectUserId())
			.param("s", row.schoolId())
			.query(Boolean.class)
			.single();
		if (already) {
			throw ApiException.conflict("moderation.already_suspended");
		}
		boolean applied = false;
		List<UUID> affected = new ArrayList<>();
		for (SchoolSuspension suspension : this.suspensions.orderedStream().toList()) {
			SchoolSuspension.Result result = suspension.suspend(row.subjectUserId(), row.schoolId());
			applied |= result.applied();
			affected.addAll(result.affected());
		}
		if (!applied) {
			throw ApiException.conflict("moderation.subject_not_a_member");
		}
		this.jdbc.sql("""
				insert into school_suspensions (id, user_id, school_id, case_id, applied_by, applied_at, affected_links)
				values (:id, :u, :s, :c, :by, :now, cast(cast(:affected as text) as uuid[]))""")
			.param("id", Ids.newId())
			.param("u", row.subjectUserId())
			.param("s", row.schoolId())
			.param("c", row.id())
			.param("by", user.userId())
			.param("now", now())
			.param("affected", arrayLiteral(affected))
			.update();
		this.audit.record(AuditEntry.of("moderation.user.suspend", AuditOutcome.SUCCESS)
			.target("user", row.subjectUserId())
			.school(row.schoolId())
			.metadata("caseId", row.id().toString()));
		return "SCHOOL";
	}

	private boolean isLocked(UUID userId) {
		return this.jdbc.sql("select status = 'LOCKED' from users where id = :id").param("id", userId).query(Boolean.class).optional().orElse(false);
	}

	private void escalate(CurrentUser user, CaseRow row, DecisionRequest request, String reason) {
		String category = (request.category() != null && !request.category().isBlank()) ? request.category() : "CONDUCT";
		String severity = (request.severity() != null && !request.severity().isBlank()) ? request.severity()
				: ("CRITICAL".equals(row.priority()) ? "CRITICAL" : "HIGH");
		UUID incident = this.incidents.raiseFromCase(row.schoolId(), row.id(), user.userId(), category, severity, reason);
		this.jdbc.sql("update moderation_cases set safeguarding_incident_id = :i where id = :id")
			.param("i", incident)
			.param("id", row.id())
			.update();
		this.audit.record(AuditEntry.of("moderation.case.escalate", AuditOutcome.SUCCESS)
			.target("moderation_case", row.id())
			.school(row.schoolId()));
	}

	/** Closes the case's reports and sends each reporter the single generic "reviewed" notice (no outcome, no names). */
	private void closeReports(CaseRow row) {
		record R(UUID id, UUID reporter) {
		}
		List<R> reports = this.jdbc.sql("select id, reporter_id from content_reports where case_id = :c and status = 'OPEN' order by created_at, id")
			.param("c", row.id())
			.query((rs, i) -> new R(rs.getObject("id", UUID.class), rs.getObject("reporter_id", UUID.class)))
			.list();
		this.jdbc.sql("update content_reports set status = 'CLOSED' where case_id = :c and status = 'OPEN'").param("c", row.id()).update();
		for (R report : reports) {
			this.notifier.reviewed(report.reporter(), row.schoolId(), report.id());
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// reversals

	/** A resolved case is opened again with a reason. A case escalated to safeguarding is reopened by leads only. */
	@Transactional
	public CaseDetail reopen(CurrentUser user, UUID caseId, String rawReason) {
		CaseRow row = locked(user, caseId);
		String reason = reason(rawReason);
		if (row.open()) {
			throw ApiException.conflict("moderation.case_not_resolved");
		}
		if ("ESCALATED".equals(row.status()) && !this.access.isLead(user, row)) {
			throw ApiException.forbidden("moderation.case_escalated");
		}
		try {
			this.jdbc.sql("""
					update moderation_cases set status = 'OPEN', assigned_to = null, decision = null, decided_by = null,
					       decided_at = null, resolved_at = null, updated_at = :now where id = :id""")
				.param("now", now())
				.param("id", caseId)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("moderation.open_case_exists");
		}
		record(caseId, user.userId(), "REOPEN", null, reason);
		this.audit.record(AuditEntry.of("moderation.case.reopen", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId()));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	/** Undoes a HIDE of this case (the same or a more senior moderator); a REMOVE is not reversible by the case. */
	@Transactional
	public CaseDetail restore(CurrentUser user, UUID caseId, String rawReason) {
		CaseRow row = locked(user, caseId);
		String reason = reason(rawReason);
		String last = this.jdbc.sql("select action from moderation_actions where case_id = :c and action in ('HIDE', 'REMOVE', 'RESTORE') order by created_at desc, id desc limit 1")
			.param("c", caseId)
			.query(String.class)
			.optional()
			.orElse(null);
		if ("REMOVE".equals(last)) {
			throw ApiException.conflict("moderation.removal_not_reversible");
		}
		if (!"HIDE".equals(last)) {
			throw ApiException.conflict("moderation.nothing_to_restore");
		}
		ModerationTarget target = this.registry.moderationTarget(row.targetType())
			.orElseThrow(() -> ApiException.badRequest("moderation.decision_not_applicable"));
		target.restore(row.targetType(), row.targetId(), caseId, user.userId());
		record(caseId, user.userId(), "RESTORE", null, reason);
		touch(caseId);
		this.audit.record(AuditEntry.of("moderation.case.restore", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId()));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	/** Lifts the suspension this case decided: a school suspension (this queue) or a platform lock (platform.users.lock). */
	@Transactional
	public CaseDetail liftSuspension(CurrentUser user, UUID caseId, String rawReason) {
		CaseRow row = locked(user, caseId);
		String reason = reason(rawReason);
		if (row.subjectUserId() == null) {
			throw ApiException.conflict("moderation.nothing_to_lift");
		}
		record Active(UUID id, List<UUID> affected) {
		}
		Active active = this.jdbc.sql("select id, affected_links from school_suspensions where case_id = :c and lifted_at is null limit 1")
			.param("c", caseId)
			.query((rs, i) -> {
				List<UUID> ids = new ArrayList<>();
				java.sql.Array array = rs.getArray("affected_links");
				if (array != null) {
					for (Object o : (Object[]) array.getArray()) {
						ids.add((UUID) o);
					}
				}
				return new Active(rs.getObject("id", UUID.class), ids);
			})
			.optional()
			.orElse(null);
		String action;
		String level;
		if (active != null) {
			for (SchoolSuspension suspension : this.suspensions.orderedStream().toList()) {
				suspension.lift(row.subjectUserId(), row.schoolId(), active.affected());
			}
			this.jdbc.sql("update school_suspensions set lifted_at = :now, lifted_by = :by where id = :id")
				.param("now", now())
				.param("by", user.userId())
				.param("id", active.id())
				.update();
			action = "LIFT_SUSPENSION";
			level = "SCHOOL";
		}
		else {
			boolean platformLock = this.jdbc.sql("""
					select action = 'SUSPEND_USER' and level = 'PLATFORM' from moderation_actions
					where case_id = :c and action in ('SUSPEND_USER', 'UNLOCK_USER') order by created_at desc, id desc limit 1""")
				.param("c", caseId)
				.query(Boolean.class)
				.optional()
				.orElse(false);
			if (!platformLock) {
				throw ApiException.conflict("moderation.nothing_to_lift");
			}
			if (!this.accessControl.can(user, Permissions.PLATFORM_USERS_LOCK, AccessScope.platform())) {
				throw ApiException.forbidden("moderation.platform_lock_not_allowed");
			}
			this.accounts.unlock(row.subjectUserId());
			this.audit.record(AuditEntry.of("moderation.user.unlock", AuditOutcome.SUCCESS)
				.target("user", row.subjectUserId())
				.school(row.schoolId())
				.metadata("caseId", caseId.toString()));
			action = "UNLOCK_USER";
			level = "PLATFORM";
		}
		record(caseId, user.userId(), action, level, reason);
		touch(caseId);
		this.audit.record(AuditEntry.of("moderation.case.lift_suspension", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId())
			.metadata("level", level));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	/** Hands a school case up to the platform queue (conflicts, or a matter above the school); the school queue no longer shows it. */
	@Transactional
	public CaseDetail referUp(CurrentUser user, UUID caseId, String rawReason) {
		CaseRow row = locked(user, caseId);
		String reason = reason(rawReason);
		if (!row.open()) {
			throw ApiException.conflict("moderation.case_closed");
		}
		if (!"SCHOOL".equals(row.queue())) {
			throw ApiException.conflict("moderation.already_platform");
		}
		this.jdbc.sql("update moderation_cases set queue = 'PLATFORM', status = 'OPEN', assigned_to = null, updated_at = :now where id = :id")
			.param("now", now())
			.param("id", caseId)
			.update();
		record(caseId, user.userId(), "REFER_UP", "PLATFORM", reason);
		this.audit.record(AuditEntry.of("moderation.case.refer_up", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId()));
		// the school moderator who handed it up no longer works it: answer with the bare summary
		return new CaseDetail(this.queries.summary(this.access.find(caseId), java.util.Map.of()), null, List.of(), List.of(), false, null);
	}

	/** Safeguarding leads set the priority; CRITICAL on a SAFETY_CONCERN case raises the incident automatically (S4.1). */
	@Transactional
	public CaseDetail setPriority(CurrentUser user, UUID caseId, String priority) {
		CaseRow row = locked(user, caseId);
		if (!this.access.isLead(user, row)) {
			throw ApiException.forbidden("moderation.priority_not_allowed");
		}
		if (priority == null || !ModerationModels.PRIORITIES.contains(priority)) {
			throw ApiException.badRequest("moderation.priority_invalid");
		}
		this.jdbc.sql("update moderation_cases set priority = :p, updated_at = :now where id = :id")
			.param("p", priority)
			.param("now", now())
			.param("id", caseId)
			.update();
		record(caseId, user.userId(), "SET_PRIORITY", null, null);
		boolean safety = this.jdbc.sql("select exists (select 1 from content_reports where case_id = :c and reason_code = :r)")
			.param("c", caseId)
			.param("r", ReasonCodes.SAFETY_CONCERN)
			.query(Boolean.class)
			.single();
		if ("CRITICAL".equals(priority) && safety && row.incidentId() == null) {
			UUID incident = this.incidents.raiseFromCase(row.schoolId(), caseId, user.userId(), "OTHER", "CRITICAL",
					"Raised automatically from a CRITICAL safety-concern report. The report itself is in the moderation case.");
			this.jdbc.sql("update moderation_cases set safeguarding_incident_id = :i where id = :id")
				.param("i", incident)
				.param("id", caseId)
				.update();
		}
		this.audit.record(AuditEntry.of("moderation.case.priority", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId())
			.metadata("priority", priority));
		return this.queries.detailOf(user, this.access.find(caseId));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// legal hold and the case-bound read

	/** Legal hold, set or lifted by a lead of the school or a platform moderator or administrator (audited). */
	@Transactional
	public HoldView setLegalHold(CurrentUser user, UUID caseId, boolean held) {
		CaseRow row = this.access.find(caseId);
		if (row == null || !this.access.canHold(user, row)) {
			throw CaseAccess.notFound();
		}
		if (row.legalHold() != held) {
			this.jdbc.sql("update moderation_cases set legal_hold = :h, legal_hold_by = :by, legal_hold_at = :now, updated_at = :now where id = :id")
				.param("h", held)
				.param("by", user.userId())
				.param("now", now())
				.param("id", caseId)
				.update();
			record(caseId, user.userId(), held ? "HOLD_SET" : "HOLD_LIFTED", null, null);
			this.audit.record(AuditEntry.of(held ? "moderation.case.hold_set" : "moderation.case.hold_lifted", AuditOutcome.SUCCESS)
				.target("moderation_case", caseId)
				.school(row.schoolId()));
		}
		return new HoldView(caseId, held);
	}

	/**
	 * The case-bound read of the reported target and its immediate context through the owning module (S3): audited (reviewer,
	 * case, target; never text) and rate limited. Messages answer a pointer to the audited messaging review instead of text.
	 */
	@Transactional
	public ReviewContent readContent(CurrentUser user, UUID caseId) {
		CaseRow row = this.access.workable(user, caseId);
		this.limiter.consume("moderation-content", user.userId().toString(), CONTENT_READS_PER_HOUR, Duration.ofHours(1),
				"moderation.read_rate_limited");
		ReviewContent content = this.registry.moderationTarget(row.targetType())
			.map((t) -> t.read(row.targetType(), row.targetId()))
			.orElseGet(() -> ReviewContent.text(List.of()));
		this.audit.record(AuditEntry.of("moderation.case.content_read", AuditOutcome.SUCCESS)
			.target("moderation_case", caseId)
			.school(row.schoolId())
			.metadata("reviewerId", user.userId().toString())
			.metadata("targetType", row.targetType())
			.metadata("targetId", row.targetId().toString())
			.metadata("items", content.items().size()));
		return content;
	}

	private static String arrayLiteral(List<UUID> ids) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < ids.size(); i++) {
			sb.append(i == 0 ? "" : ",").append(ids.get(i));
		}
		return sb.append('}').toString();
	}

}
