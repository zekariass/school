package com.nemaz.school.moderation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.text.PlainText;
import com.nemaz.school.common.tx.AdvisoryLocks;
import com.nemaz.school.moderation.ReasonCodes;
import com.nemaz.school.moderation.ReportIntake;
import com.nemaz.school.moderation.TargetRef;
import com.nemaz.school.moderation.TargetTypes;
import com.nemaz.school.moderation.application.CaseRows.CaseRow;
import com.nemaz.school.moderation.application.ModerationModels.ReportReceipt;
import com.nemaz.school.moderation.application.ModerationModels.ReportRequest;
import com.nemaz.school.system.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Intake of reports (docs/Safeguarding_Rules.md S1) and the creation and merging of cases (S2.1, S2.2, S2.3).
 * <ul>
 * <li>{@link #file} is {@code POST /moderation/reports}: the target must be visible to the reporter (through the owning module's
 * {@code ReportTarget}), else the same generic 404 as the content itself; a second report of an open target by the same person
 * answers the first; 20 reports a day, 10 for students (settings).</li>
 * <li>{@link #ingest} is the idempotent path of the existing hooks ({@code ContentReported}, {@code MessageReported}), called
 * from listeners in the owning modules: the {@code source_key} is unique, so redelivery changes nothing.</li>
 * <li>One open case per target (unique index, serialized by an advisory lock): further reports join it, raise the counters and,
 * past the threshold, the priority. The priority starts HIGH for a SAFETY_CONCERN, a report by a student about an adult, a message
 * of a safeguarded conversation, and HARASSMENT; HIGH alerts the school's leads at once, with a generic text.</li>
 * <li>A case about a school administrator, moderator or safeguarding lead goes to the PLATFORM queue (S2.3, decision 7).</li>
 * </ul>
 * The details of a report are stored (bounded, cleaned) and shown only to those who may see the reporter; they never reach events,
 * logs, audit rows or notifications.
 */
@Service
public class ReportService implements ReportIntake {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	static final int MAX_DETAILS = 1000;

	private final JdbcClient jdbc;

	private final TargetRegistry registry;

	private final PeopleLookup people;

	private final CaseAccess access;

	private final ModerationSettings settings;

	private final ModerationNotifier notifier;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final Clock clock;

	ReportService(JdbcClient jdbc, TargetRegistry registry, PeopleLookup people, CaseAccess access, ModerationSettings settings,
			ModerationNotifier notifier, RateLimiter limiter, AuditLog audit, Clock clock) {
		this.jdbc = jdbc;
		this.registry = registry;
		this.people = people;
		this.access = access;
		this.settings = settings;
		this.notifier = notifier;
		this.limiter = limiter;
		this.audit = audit;
		this.clock = clock;
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the API path

	/**
	 * Files a report as {@code user}. Returns the new report, or the person's earlier open report of the same target
	 * ({@code created} false: the controller answers 200 instead of 201).
	 */
	@Transactional
	public Result file(CurrentUser user, ReportRequest request) {
		String type = request.targetType();
		if (type == null || !TargetTypes.ALL.contains(type)) {
			throw ApiException.badRequest("moderation.target_type_invalid");
		}
		String reason = request.reasonCode();
		if (reason == null || !ReasonCodes.ALL.contains(reason)) {
			throw ApiException.badRequest("moderation.reason_invalid");
		}
		String details = cleanDetails(request.details());
		if (!this.registry.supported(type)) {
			throw ApiException.badRequest("moderation.target_type_unsupported");
		}
		// A target the reporter cannot see answers the generic 404, whatever the reason (S1.1).
		TargetRef ref = this.registry.resolveFor(user, type, request.targetId())
			.orElseThrow(() -> ApiException.notFound("moderation.target_not_found"));
		this.access.requireFeature(ref.schoolId());
		if (user.userId().equals(ref.subjectUserId())) {
			throw ApiException.conflict("moderation.cannot_report_own");
		}
		Optional<ReportReceipt> existing = openReport(user.userId(), type, ref.id());
		if (existing.isPresent()) {
			return new Result(existing.get(), false);
		}
		boolean student = this.people.isStudent(user.userId());
		this.limiter.consume("moderation-report", user.userId().toString(), this.settings.dailyLimit(ref.schoolId(), student),
				Duration.ofDays(1), "moderation.report_rate_limited");
		UUID reportId = Ids.newId();
		Created created = create(reportId, user.userId(), ref, reason, details, null);
		this.registry.accepted(reportId, user.userId(), ref, reason, details);
		return new Result(new ReportReceipt(reportId, created.createdAt()), true);
	}

	public record Result(ReportReceipt receipt, boolean created) {
	}

	private String cleanDetails(String raw) {
		if (raw == null) {
			return null;
		}
		String cleaned = PlainText.clean(raw);
		if (cleaned.length() > MAX_DETAILS) {
			throw ApiException.badRequest("moderation.details_too_long");
		}
		return cleaned.isEmpty() ? null : cleaned;
	}

	private Optional<ReportReceipt> openReport(UUID reporterId, String type, UUID targetId) {
		return this.jdbc.sql("""
				select id, created_at from content_reports
				where reporter_id = :u and target_type = :t and target_id = :id and status = 'OPEN' limit 1""")
			.param("u", reporterId)
			.param("t", type)
			.param("id", targetId)
			.query((rs, i) -> new ReportReceipt(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant()))
			.optional();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the event path

	@Override
	@Transactional
	public void ingest(String sourceKey, String targetType, UUID targetId, UUID reporterId, String reasonCode, String details) {
		// two deliveries of one event may run at the same time: the second waits for the first to commit, then sees its row
		AdvisoryLocks.acquire(this.jdbc, "moderation.source:" + sourceKey);
		boolean seen = this.jdbc.sql("select exists (select 1 from content_reports where source_key = :k)")
			.param("k", sourceKey)
			.query(Boolean.class)
			.single();
		if (seen) {
			return; // redelivery: nothing changes
		}
		String reason = ReasonCodes.ALL.contains(reasonCode) ? reasonCode : ReasonCodes.OTHER;
		TargetRef ref = this.registry.describe(targetType, targetId).orElse(null);
		if (ref == null) {
			log.info("Report {} ignored: its target is gone", sourceKey);
			return;
		}
		if (openReport(reporterId, targetType, targetId).isPresent()) {
			return; // a person's second report of an open target adds nothing
		}
		create(Ids.newId(), reporterId, ref, reason, details, sourceKey);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the case

	private record Created(UUID caseId, Instant createdAt) {
	}

	/** Stores the report in the (new or existing) open case of the target, keeps the counters and priority, alerts and thanks. */
	private Created create(UUID reportId, UUID reporterId, TargetRef ref, String reason, String details, String sourceKey) {
		AdvisoryLocks.acquire(this.jdbc, "moderation.case:" + ref.type() + ":" + ref.id());
		CaseRow existing = this.jdbc.sql("select " + CaseRows.COLUMNS + " from moderation_cases c where c.target_type = :t and c.target_id = :id and c.status in ('OPEN', 'ASSIGNED')")
			.param("t", ref.type())
			.param("id", ref.id())
			.query(CaseRows::caseRow)
			.optional()
			.orElse(null);
		boolean studentReportsAdult = this.people.isStudent(reporterId)
				&& ref.subjectUserId() != null && !this.people.isStudent(ref.subjectUserId());
		String priority = initialPriority(reason, studentReportsAdult, ref.safeguardedConversation());
		UUID caseId;
		Instant now = now();
		if (existing == null) {
			caseId = Ids.newId();
			boolean platform = ref.schoolId() == null
					|| (ref.subjectUserId() != null && this.people.isOversight(ref.subjectUserId(), ref.schoolId()));
			this.jdbc.sql("""
					insert into moderation_cases (id, school_id, queue, target_type, target_id, subject_user_id, priority,
					       created_at, updated_at)
					values (:id, :school, :queue, :type, :target, :subject, :priority, :now, :now)""")
				.param("id", caseId)
				.param("school", ref.schoolId())
				.param("queue", platform ? "PLATFORM" : "SCHOOL")
				.param("type", ref.type())
				.param("target", ref.id())
				.param("subject", ref.subjectUserId())
				.param("priority", priority)
				.param("now", Timestamp.from(now))
				.update();
			this.registry.moderationTarget(ref.type()).ifPresent((t) -> t.hold(ref.type(), ref.id(), caseId));
		}
		else {
			caseId = existing.id();
		}
		String role = this.people.roleAt(reporterId, ref.schoolId());
		this.jdbc.sql("""
				insert into content_reports (id, case_id, reporter_id, reporter_role, school_id, target_type, target_id,
				       reason_code, details, source_key, created_at)
				values (:id, :case, :reporter, :role, :school, :type, :target, :reason, :details, :key, :now)""")
			.param("id", reportId)
			.param("case", caseId)
			.param("reporter", reporterId)
			.param("role", role)
			.param("school", ref.schoolId())
			.param("type", ref.type())
			.param("target", ref.id())
			.param("reason", reason)
			.param("details", details)
			.param("key", sourceKey)
			.param("now", Timestamp.from(now))
			.update();
		int reporters = this.jdbc.sql("select count(distinct reporter_id) from content_reports where case_id = :c")
			.param("c", caseId)
			.query(Integer.class)
			.single();
		this.jdbc.sql("""
				update moderation_cases set report_count = report_count + 1, reporter_count = :reporters, updated_at = :now
				where id = :id""")
			.param("reporters", reporters)
			.param("now", Timestamp.from(now))
			.param("id", caseId)
			.update();
		raisePriority(caseId, ref, priority, reporters);
		this.audit.record(AuditEntry.of("moderation.report.create", AuditOutcome.SUCCESS)
			.actor(reporterId)
			.target("moderation_case", caseId)
			.school(ref.schoolId())
			.metadata("targetType", ref.type())
			.metadata("reason", reason));
		this.notifier.acknowledge(reporterId, ref.schoolId(), reportId);
		return new Created(caseId, now);
	}

	/** SAFETY_CONCERN, a student reporting an adult, a safeguarded conversation and HARASSMENT start HIGH. */
	static String initialPriority(String reason, boolean studentReportsAdult, boolean safeguardedConversation) {
		if (ReasonCodes.SAFETY_CONCERN.equals(reason) || studentReportsAdult || safeguardedConversation
				|| ReasonCodes.HARASSMENT.equals(reason)) {
			return "HIGH";
		}
		return "NORMAL";
	}

	/** Priority only goes up: by the report itself or by the number of distinct reporters; HIGH alerts the leads once. */
	private void raisePriority(UUID caseId, TargetRef ref, String reportPriority, int reporters) {
		CaseRow row = this.access.find(caseId);
		String target = reportPriority;
		if (reporters >= this.settings.priorityThreshold(ref.schoolId()) && CaseRows.rank(target) < CaseRows.rank("HIGH")) {
			target = "HIGH";
		}
		if (CaseRows.rank(target) > CaseRows.rank(row.priority())) {
			this.jdbc.sql("update moderation_cases set priority = :p, updated_at = :now where id = :id")
				.param("p", target)
				.param("now", Timestamp.from(now()))
				.param("id", caseId)
				.update();
		}
		String effective = (CaseRows.rank(target) > CaseRows.rank(row.priority())) ? target : row.priority();
		if (CaseRows.rank(effective) >= CaseRows.rank("HIGH") && row.leadAlertedAt() == null) {
			this.jdbc.sql("update moderation_cases set lead_alerted_at = :now where id = :id")
				.param("now", Timestamp.from(now()))
				.param("id", caseId)
				.update();
			Set<UUID> exclude = new HashSet<>();
			if (ref.subjectUserId() != null) {
				exclude.add(ref.subjectUserId());
			}
			this.jdbc.sql("select reporter_id from content_reports where case_id = :c")
				.param("c", caseId)
				.query(UUID.class)
				.list()
				.forEach(exclude::add);
			this.notifier.alertLeads(caseId, ref.schoolId(), exclude);
		}
	}

}
