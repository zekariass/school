package com.nemaz.school.attendance.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.attendance.AbsenceReported;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.system.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Absence reports: a guardian tells the school ahead of time that the child will be away.
 * <ul>
 * <li><b>Who files:</b> an ACTIVE, not custody-restricted guardian of an ACTIVE student of the school (404 for anybody
 * else: nothing about the child is revealed). At most 20 reports a day per guardian.</li>
 * <li><b>Dates:</b> school-local calendar dates, at most 7 days back (a late report of yesterday's illness is useful),
 * at most 90 days ahead, at most 61 days long.</li>
 * <li><b>Who decides:</b> a LEAD, CO or SUBJECT teacher of a class the student is ACTIVE in, or an attendance manager,
 * once (SUBMITTED to ACKNOWLEDGED or DECLINED, audited). The reporter may withdraw a report (CANCELLED).</li>
 * <li><b>What an acknowledgement does:</b> it annotates the roll for those dates ("acknowledged absence") and lets
 * {@code markAllPresent} and the roll's {@code suggestedStatus} pre-fill EXCUSED; it never writes a mark by itself, so
 * the teacher's roll stays the record of who was actually there.</li>
 * <li><b>Privacy:</b> reason and note are health related. They are read by the reporter, the student's lead, co and
 * subject teachers and attendance managers only: not by assistants, the student, other guardians of the child, class
 * managers who are not attendance managers. They are never in events, notifications, logs or audit metadata.</li>
 * </ul>
 */
@Service
@Transactional
public class AbsenceReportService {

	static final Set<String> REASONS = Set.of("ILLNESS", "FAMILY", "APPOINTMENT", "OTHER");

	static final int MAX_DAYS_BACK = 7;

	static final int MAX_DAYS_AHEAD = 90;

	static final int MAX_LENGTH_DAYS = 60;

	private static final int REPORTS_PER_DAY = 20;

	private final AttendanceAccess access;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final ApplicationEventPublisher events;

	AbsenceReportService(AttendanceAccess access, SchoolTime time, JdbcClient jdbc, AuditLog audit, RateLimiter limiter,
			ApplicationEventPublisher events) {
		this.access = access;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
		this.limiter = limiter;
		this.events = events;
	}

	public AbsenceReportView create(CurrentUser user, UUID schoolId, UUID studentId, AbsenceReportRequest r) {
		if (!this.access.guardians().isActiveGuardian(user.userId(), studentId) || !this.access.activeStudent(schoolId, studentId)) {
			throw ApiException.notFound("student.not_found");
		}
		this.access.requireFeature(schoolId);
		if (r.reasonCode() == null || !REASONS.contains(r.reasonCode())) {
			throw ApiException.badRequest("absence.invalid_reason");
		}
		ZoneId zone = this.time.zoneOfSchool(schoolId);
		LocalDate today = this.time.today(zone);
		if (r.toDate().isBefore(r.fromDate()) || r.fromDate().isBefore(today.minusDays(MAX_DAYS_BACK))
				|| r.toDate().isAfter(today.plusDays(MAX_DAYS_AHEAD))
				|| r.toDate().toEpochDay() - r.fromDate().toEpochDay() > MAX_LENGTH_DAYS) {
			throw ApiException.badRequest("absence.invalid_dates");
		}
		this.limiter.consume("absence-report", user.userId().toString(), REPORTS_PER_DAY, Duration.ofDays(1),
				"absence.rate_limited");
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(this.time.now());
		String note = (r.note() == null || r.note().isBlank()) ? null : r.note().strip();
		this.jdbc.sql("""
				insert into absence_reports (id, school_id, student_id, reported_by, from_date, to_date, reason_code, note,
				                             created_at, updated_at)
				values (:id, :school, :student, :by, :from, :to, :reason, :note, :now, :now)""")
			.param("id", id)
			.param("school", schoolId)
			.param("student", studentId)
			.param("by", user.userId())
			.param("from", java.sql.Date.valueOf(r.fromDate()))
			.param("to", java.sql.Date.valueOf(r.toDate()))
			.param("reason", r.reasonCode())
			.param("note", note)
			.param("now", now)
			.update();
		this.audit.record(AuditEntry.of("absence.report.create", AuditOutcome.SUCCESS)
			.target("absence_report", id)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("from", r.fromDate().toString())
			.metadata("to", r.toDate().toString()));
		this.events.publishEvent(new AbsenceReported(id, schoolId, studentId, r.fromDate(), r.toDate(), user.userId()));
		return load(id).map((row) -> view(row, true)).orElseThrow();
	}

	/** The reports about a student: a guardian sees the ones they filed, deciders and readers see all (with the note). */
	@Transactional(readOnly = true)
	public CursorPage<AbsenceReportView> forStudent(CurrentUser user, UUID schoolId, UUID studentId, String cursor,
			Integer limit) {
		boolean guardian = this.access.guardians().isActiveGuardian(user.userId(), studentId);
		boolean decider = false;
		if (!guardian) {
			if (!this.access.gate().canSee(user, schoolId) || !this.access.knownStudent(schoolId, studentId)) {
				throw ApiException.notFound("student.not_found");
			}
			decider = this.access.mayDecideFor(user, schoolId, studentId);
			if (!decider) {
				throw (this.access.reader(user, schoolId) || this.access.memberships().teachesStudent(user.userId(), studentId))
						? ApiException.forbidden("absence.access_denied") : ApiException.notFound("student.not_found");
			}
		}
		else if (!this.access.knownStudent(schoolId, studentId)) {
			throw ApiException.notFound("student.not_found");
		}
		else {
			decider = this.access.mayDecideFor(user, schoolId, studentId);
		}
		this.access.requireFeature(schoolId);
		boolean all = decider;
		int pageSize = Cursors.clampLimit(limit);
		LocalDate afterFrom = null;
		UUID afterId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				afterFrom = LocalDate.parse(parts.get(0));
				afterId = UUID.fromString(parts.get(1));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<ReportRow> rows = this.jdbc.sql(SELECT + """
				 where school_id = :s and student_id = :st and (:all or reported_by = :u)
				  and (cast(:af as date) is null or (from_date, id) < (cast(:af as date), cast(:aid as uuid)))
				order by from_date desc, id desc limit :n""")
			.param("s", schoolId)
			.param("st", studentId)
			.param("all", all)
			.param("u", user.userId())
			.param("af", (afterFrom != null) ? java.sql.Date.valueOf(afterFrom) : null)
			.param("aid", afterId)
			.param("n", pageSize + 1)
			.query(AbsenceReportService::row)
			.list();
		return CursorPage.fromOverfetch(rows, pageSize, (r) -> Cursors.encode(r.fromDate().toString(), r.id().toString()))
			.map((r) -> view(r, all || r.reportedBy().equals(user.userId())));
	}

	/** The reports of the students currently ACTIVE in a class that touch [from, to] (default: a week back to two months ahead). */
	@Transactional(readOnly = true)
	public CursorPage<AbsenceReportView> forClass(CurrentUser user, UUID schoolId, UUID classId, String status,
			LocalDate from, LocalDate to, String cursor, Integer limit) {
		ClassRef ref = this.access.readableClass(user, schoolId, classId);
		boolean notes = this.access.mayReadNotes(user, ref);
		if (status != null && !Set.of("SUBMITTED", "ACKNOWLEDGED", "DECLINED", "CANCELLED").contains(status)) {
			throw ApiException.badRequest("absence.invalid_status");
		}
		LocalDate today = this.time.today(this.time.zoneOf(ref));
		LocalDate start = (from != null) ? from : today.minusDays(MAX_DAYS_BACK);
		LocalDate end = (to != null) ? to : today.plusDays(60);
		if (end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > 400) {
			throw ApiException.badRequest("absence.invalid_dates");
		}
		int pageSize = Cursors.clampLimit(limit);
		LocalDate afterFrom = null;
		UUID afterId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				afterFrom = LocalDate.parse(parts.get(0));
				afterId = UUID.fromString(parts.get(1));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<ReportRow> rows = this.jdbc.sql(SELECT + """
				 where school_id = :s
				  and student_id in (select student_id from class_students where class_id = :c and status = 'ACTIVE')
				  and (case when cast(:status as text) is null then status in ('SUBMITTED', 'ACKNOWLEDGED') else status = :status end)
				  and from_date <= :to and to_date >= :from
				  and (cast(:af as date) is null or (from_date, id) > (cast(:af as date), cast(:aid as uuid)))
				order by from_date, id limit :n""")
			.param("s", schoolId)
			.param("c", classId)
			.param("status", status)
			.param("from", java.sql.Date.valueOf(start))
			.param("to", java.sql.Date.valueOf(end))
			.param("af", (afterFrom != null) ? java.sql.Date.valueOf(afterFrom) : null)
			.param("aid", afterId)
			.param("n", pageSize + 1)
			.query(AbsenceReportService::row)
			.list();
		Map<UUID, PersonCard> cards = this.access.people().cards(rows.stream().map(ReportRow::studentId).distinct().toList());
		return CursorPage.fromOverfetch(rows, pageSize, (r) -> Cursors.encode(r.fromDate().toString(), r.id().toString()))
			.map((r) -> view(r, notes || r.reportedBy().equals(user.userId()), cards.get(r.studentId())));
	}

	public AbsenceReportView acknowledge(CurrentUser user, UUID schoolId, UUID reportId, String note) {
		return decide(user, schoolId, reportId, "ACKNOWLEDGED", note);
	}

	public AbsenceReportView decline(CurrentUser user, UUID schoolId, UUID reportId, String note) {
		return decide(user, schoolId, reportId, "DECLINED", note);
	}

	private AbsenceReportView decide(CurrentUser user, UUID schoolId, UUID reportId, String decision, String note) {
		ReportRow report = visible(user, schoolId, reportId);
		this.access.requireFeature(schoolId);
		if (!this.access.mayDecideFor(user, schoolId, report.studentId())) {
			throw ApiException.forbidden("absence.access_denied");
		}
		if (!"SUBMITTED".equals(report.status())) {
			throw ApiException.conflict("absence.already_decided");
		}
		String decisionNote = (note == null || note.isBlank()) ? null : note.strip();
		Timestamp now = Timestamp.from(this.time.now());
		int updated = this.jdbc.sql("""
				update absence_reports set status = :status, decided_by = :by, decided_at = :now, decision_note = :note,
				       updated_at = :now, version = version + 1
				where id = :id and status = 'SUBMITTED'""")
			.param("status", decision)
			.param("by", user.userId())
			.param("now", now)
			.param("note", decisionNote)
			.param("id", reportId)
			.update();
		if (updated == 0) {
			throw ApiException.conflict("absence.already_decided");
		}
		this.audit.record(AuditEntry.of("absence.report." + decision.toLowerCase(), AuditOutcome.SUCCESS)
			.target("absence_report", reportId)
			.school(schoolId)
			.metadata("studentId", report.studentId().toString()));
		return load(reportId).map((row) -> view(row, true)).orElseThrow();
	}

	/** The reporter withdraws a report that is still SUBMITTED or ACKNOWLEDGED. */
	public AbsenceReportView cancel(CurrentUser user, UUID schoolId, UUID reportId) {
		ReportRow report = visible(user, schoolId, reportId);
		if (!report.reportedBy().equals(user.userId())) {
			throw ApiException.forbidden("absence.access_denied");
		}
		if (!Set.of("SUBMITTED", "ACKNOWLEDGED").contains(report.status())) {
			throw ApiException.conflict("absence.already_decided");
		}
		this.jdbc.sql("update absence_reports set status = 'CANCELLED', updated_at = :now, version = version + 1 where id = :id")
			.param("now", Timestamp.from(this.time.now()))
			.param("id", reportId)
			.update();
		this.audit.record(AuditEntry.of("absence.report.cancel", AuditOutcome.SUCCESS)
			.target("absence_report", reportId)
			.school(schoolId)
			.metadata("studentId", report.studentId().toString()));
		return load(reportId).map((row) -> view(row, true)).orElseThrow();
	}

	/** The report if the caller may know of it (reporter, decider, attendance reader); 404 otherwise. */
	private ReportRow visible(CurrentUser user, UUID schoolId, UUID reportId) {
		ReportRow report = load(reportId).filter((r) -> r.schoolId().equals(schoolId))
			.orElseThrow(() -> ApiException.notFound("absence.not_found"));
		if (!report.reportedBy().equals(user.userId()) && !this.access.mayDecideFor(user, schoolId, report.studentId())
				&& !this.access.memberships().teachesStudent(user.userId(), report.studentId())
				&& !this.access.reader(user, schoolId)) {
			throw ApiException.notFound("absence.not_found");
		}
		return report;
	}

	private java.util.Optional<ReportRow> load(UUID id) {
		return this.jdbc.sql(SELECT + " where id = :id").param("id", id).query(AbsenceReportService::row).optional();
	}

	private static final String SELECT = """
			select id, school_id, student_id, reported_by, from_date, to_date, reason_code, note, status, decided_by,
			       decided_at, decision_note, created_at from absence_reports""";

	private static ReportRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new ReportRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "student_id"),
				Rows.uuid(rs, "reported_by"), Rows.date(rs, "from_date"), Rows.date(rs, "to_date"), rs.getString("reason_code"),
				rs.getString("note"), rs.getString("status"), Rows.uuid(rs, "decided_by"), Rows.instant(rs, "decided_at"),
				rs.getString("decision_note"), Rows.instant(rs, "created_at"));
	}

	private static AbsenceReportView view(ReportRow r, boolean details) {
		return view(r, details, null);
	}

	private static AbsenceReportView view(ReportRow r, boolean details, PersonCard student) {
		return new AbsenceReportView(r.id(), r.studentId(), (student != null) ? student.displayName() : null, r.fromDate(),
				r.toDate(), r.status(), details ? r.reasonCode() : null, details ? r.note() : null, r.reportedBy(),
				r.decidedBy(), r.decidedAt(), r.decisionNote(), r.createdAt());
	}

	private record ReportRow(UUID id, UUID schoolId, UUID studentId, UUID reportedBy, LocalDate fromDate, LocalDate toDate,
			String reasonCode, String note, String status, UUID decidedBy, Instant decidedAt, String decisionNote,
			Instant createdAt) {
	}

	// --- shapes -----------------------------------------------------------------------------

	@Schema(description = "A guardian's absence report. reasonCode ILLNESS, FAMILY, APPOINTMENT or OTHER")
	public record AbsenceReportRequest(@NotNull(message = "validation.required") LocalDate fromDate,
			@NotNull(message = "validation.required") LocalDate toDate,
			@NotNull(message = "validation.required") @Size(max = 20, message = "validation.tooLong") String reasonCode,
			@Size(max = 500, message = "validation.tooLong") @Schema(description = "Health related: read only by the reporter, the student's lead, co and subject teachers and attendance managers") String note) {

		@Override
		public String toString() {
			return "AbsenceReportRequest[redacted]";
		}

	}

	public record AbsenceDecisionRequest(@Size(max = 200, message = "validation.tooLong") String note) {

		@Override
		public String toString() {
			return "AbsenceDecisionRequest[redacted]";
		}

	}

	public record AbsenceReportView(@NotNull UUID id, @NotNull UUID studentId, String studentName, @NotNull LocalDate fromDate,
			@NotNull LocalDate toDate, @NotNull @Schema(description = "SUBMITTED, ACKNOWLEDGED, DECLINED or CANCELLED") String status,
			@Schema(description = "Only for the reporter, the student's lead, co and subject teachers and attendance managers") String reasonCode,
			@Schema(description = "Only for the reporter, the student's lead, co and subject teachers and attendance managers") String note,
			@NotNull UUID reportedBy, UUID decidedBy, Instant decidedAt, String decisionNote, @NotNull Instant createdAt) {
	}

}
