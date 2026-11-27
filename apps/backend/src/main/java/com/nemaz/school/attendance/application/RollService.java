package com.nemaz.school.attendance.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.attendance.AttendanceMarked;
import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.application.AttendanceModels.AbsenceAnnotationView;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceCounts;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceMarkInput;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRecordOutcome;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRecordView;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollRequest;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollStudent;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollView;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceSubmittedMark;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceWriteResult;
import com.nemaz.school.attendance.application.AttendanceModels.WriteContext;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.tx.AdvisoryLocks;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking and reading rolls: the one writer of {@code attendance_sessions} and {@code attendance_records}, used by the
 * online endpoint and by the ATTENDANCE_MARK sync operation alike (so the two can never disagree).
 * <ul>
 * <li><b>Validation first, then writes:</b> authorization, the date (school-local, not in the future beyond
 * {@link #FUTURE_TOLERANCE}), the slot (enabled for the school), the lock window and every listed student (must have
 * belonged to the class on that date) are checked before anything is written, so a refused request writes nothing and
 * publishes nothing.</li>
 * <li><b>Semantics:</b> listed students are upserted; omitted students are untouched (unmarked stay unmarked);
 * {@code markAllPresent} marks the students without a record who are not listed.</li>
 * <li><b>Conflicts:</b> a mark that differs from an existing record is written only when the writer saw that record's
 * current version (its {@code expectedVersion}, or the roll's {@code baseVersion} while nobody changed the roll since,
 * or the same device's own earlier edit); otherwise it is reported as CONFLICT with both sides and nothing is
 * overwritten. The other marks of the request are still written. A mark identical to the stored one is UNCHANGED, never a
 * conflict, so two teachers who agree do not clash. The device time ({@code recorded_at}) is stored but never decides
 * who wins.</li>
 * <li><b>Lock:</b> a roll locks {@code attendance.lock_after_hours} after the end of its date (school time). Afterwards
 * only the lead teacher, class managers and attendance managers may write, with an {@code overrideReason} (audited).</li>
 * </ul>
 */
@Service
@Transactional
public class RollService {

	/** Rolls for the next local day may be taken this long before midnight (clock and time-zone skew of devices). */
	static final Duration FUTURE_TOLERANCE = Duration.ofHours(1);

	static final int MAX_RECORDS = 500;

	static final int MAX_DAYS_BACK = 400;

	private static final String RECORD_COLUMNS = """
			r.id, r.student_id, r.status, r.minutes_late, r.note, r.version, r.recorded_by, r.recorded_at, r.source,
			r.device_id, r.absence_report_id""";

	private final AttendanceAccess access;

	private final AttendanceSettings settings;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	RollService(AttendanceAccess access, AttendanceSettings settings, SchoolTime time, JdbcClient jdbc, AuditLog audit,
			ApplicationEventPublisher events) {
		this.access = access;
		this.settings = settings;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
		this.events = events;
	}

	// --- reading ----------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public AttendanceRollView roll(CurrentUser user, UUID schoolId, UUID classId, LocalDate date, String slot, UUID groupId) {
		ClassRef ref = this.access.readableClass(user, schoolId, classId);
		ZoneId zone = this.time.zoneOf(ref);
		List<String> slots = this.settings.slots(schoolId);
		String effectiveSlot = (slot == null || slot.isBlank()) ? slots.getFirst() : slot;
		if (!slots.contains(effectiveSlot)) {
			throw ApiException.badRequest("attendance.slot_not_enabled");
		}
		LocalDate day = (date != null) ? date : this.time.today(zone);
		Instant locksAt = locksAt(schoolId, day, zone);
		boolean locked = !this.time.now().isBefore(locksAt);
		Set<UUID> groupMembers = (groupId != null) ? groupMembers(schoolId, classId, groupId) : null;
		Optional<SessionRow> session = session(classId, day, effectiveSlot);
		Map<UUID, RecordRow> records = new HashMap<>();
		session.ifPresent((s) -> records(s.id()).forEach((r) -> records.put(r.studentId(), r)));
		Set<UUID> eligible = new HashSet<>(this.access.memberships()
			.studentIdsDuring(classId, this.time.startOf(day, zone), this.time.endOf(day, zone)));
		// A student who has a record stays on the roll even if the class membership data says otherwise.
		eligible.addAll(records.keySet());
		if (groupMembers != null) {
			// An elective's group: the roll shows its members only (another group's marks of the same roll stay out of sight).
			eligible.retainAll(groupMembers);
		}
		Map<UUID, PersonCard> cards = this.access.people().cards(eligible);
		boolean notes = this.access.mayReadNotes(user, ref);
		Map<UUID, ReportRow> reports = reportsCovering(schoolId, eligible, day);
		List<AttendanceRollStudent> students = new ArrayList<>();
		Map<AttendanceStatus, Integer> tally = new EnumMap<>(AttendanceStatus.class);
		for (UUID studentId : eligible) {
			RecordRow record = records.get(studentId);
			ReportRow report = reports.get(studentId);
			PersonCard card = cards.get(studentId);
			if (record != null) {
				tally.merge(record.status(), 1, Integer::sum);
			}
			AttendanceStatus suggested = (record == null && report != null && "ACKNOWLEDGED".equals(report.status()))
					? AttendanceStatus.EXCUSED : null;
			students.add(new AttendanceRollStudent(studentId, (card != null) ? card.displayName() : "", (card != null) ? card.avatar() : null,
					(card != null) ? card.avatarConfig() : null, (record != null) ? view(record) : null, suggested,
					(report != null) ? annotation(report, notes) : null));
		}
		students.sort(Comparator.comparing((AttendanceRollStudent s) -> s.displayName().toLowerCase())
			.thenComparing(AttendanceRollStudent::studentId));
		UUID subjectId = session.map(SessionRow::subjectId).orElse(null);
		return new AttendanceRollView(classId, day, effectiveSlot, slots, subjectId,
				(subjectId != null) ? subjectName(subjectId) : null, groupId, session.map(SessionRow::id).orElse(null),
				session.map(SessionRow::version).orElse(null), session.map(SessionRow::takenBy).orElse(null),
				session.map(SessionRow::takenAt).orElse(null), locked, locksAt, this.access.mayOverrideLock(user, ref),
				zone.getId(), students, AttendanceCounts.of(students.size(), tally));
	}

	// --- writing ----------------------------------------------------------------------------

	/** The online endpoint: the device time is the server's. */
	public AttendanceWriteResult write(CurrentUser user, UUID schoolId, UUID classId, AttendanceRollRequest cmd) {
		return write(user, schoolId, classId, cmd, WriteContext.online(this.time.now()));
	}

	/** Writes marks to the roll of the class on the date (see the class comment); the caller's role decides what is allowed. */
	public AttendanceWriteResult write(CurrentUser user, UUID schoolId, UUID classId, AttendanceRollRequest cmd,
			WriteContext ctx) {
		ClassRef ref = this.access.takeableClass(user, schoolId, classId);
		List<AttendanceMarkInput> listed = (cmd.records() != null) ? cmd.records() : List.of();
		boolean markAll = Boolean.TRUE.equals(cmd.markAllPresent());
		if (listed.isEmpty() && !markAll) {
			throw ApiException.badRequest("attendance.nothing_to_record");
		}
		if (listed.size() > MAX_RECORDS) {
			throw ApiException.badRequest("attendance.too_many_records");
		}
		Set<UUID> seen = new HashSet<>();
		for (AttendanceMarkInput in : listed) {
			if (!seen.add(in.studentId())) {
				throw ApiException.badRequest("attendance.duplicate_student");
			}
			if (in.minutesLate() != null
					&& (in.status() != AttendanceStatus.LATE || in.minutesLate() < 1 || in.minutesLate() > 600)) {
				throw ApiException.badRequest("attendance.invalid_minutes_late");
			}
		}
		List<String> slots = this.settings.slots(schoolId);
		String slot = (cmd.slot() == null || cmd.slot().isBlank()) ? "DAY" : cmd.slot();
		if (!slots.contains(slot)) {
			throw ApiException.badRequest("attendance.slot_not_enabled");
		}
		ZoneId zone = this.time.zoneOf(ref);
		Instant now = this.time.now();
		LocalDate date = cmd.date();
		if (date.isAfter(this.time.dateOf(now.plus(FUTURE_TOLERANCE), zone))) {
			throw ApiException.conflict("attendance.date_in_future");
		}
		if (date.isBefore(this.time.today(zone).minusDays(MAX_DAYS_BACK))) {
			throw ApiException.badRequest("attendance.date_too_old");
		}
		boolean locked = !now.isBefore(locksAt(schoolId, date, zone));
		String overrideReason = null;
		if (locked) {
			if (!this.access.mayOverrideLock(user, ref)) {
				throw ApiException.conflict("attendance.session_locked");
			}
			overrideReason = (cmd.overrideReason() == null) ? "" : cmd.overrideReason().strip();
			if (overrideReason.isEmpty()) {
				throw ApiException.badRequest("attendance.override_reason_required");
			}
		}
		if (cmd.subjectId() != null) {
			requirePeriodSubject(schoolId, classId, slot, cmd.subjectId());
		}
		Set<UUID> eligible = new HashSet<>(this.access.memberships()
			.studentIdsDuring(classId, this.time.startOf(date, zone), this.time.endOf(date, zone)));
		for (AttendanceMarkInput in : listed) {
			if (!eligible.contains(in.studentId())) {
				throw ApiException.conflict("attendance.student_not_in_class");
			}
		}
		if (cmd.groupId() != null) {
			Set<UUID> groupMembers = groupMembers(schoolId, classId, cmd.groupId());
			for (AttendanceMarkInput in : listed) {
				if (!groupMembers.contains(in.studentId())) {
					throw ApiException.conflict("attendance.student_not_in_group");
				}
			}
			eligible.retainAll(groupMembers);
		}
		AdvisoryLocks.acquire(this.jdbc, "attendance:" + classId + ":" + date + ":" + slot);
		Optional<SessionRow> existingSession = session(classId, date, slot);
		if (cmd.subjectId() != null && existingSession.isPresent()
				&& !cmd.subjectId().equals(existingSession.get().subjectId())) {
			// One roll per class, date and slot: it belongs to the subject it was created for (nothing is overwritten).
			throw ApiException.conflict("attendance.subject_mismatch");
		}
		Map<UUID, RecordRow> existing = new HashMap<>();
		existingSession.ifPresent((s) -> records(s.id()).forEach((r) -> existing.put(r.studentId(), r)));
		Map<UUID, UUID> acknowledged = acknowledgedReports(schoolId, eligible, date);
		List<MarkInput> marks = new ArrayList<>();
		for (AttendanceMarkInput in : listed) {
			marks.add(new MarkInput(in.studentId(), in.status(), in.minutesLate(), clean(in.note()), in.expectedVersion(), false));
		}
		if (markAll) {
			for (UUID studentId : eligible) {
				if (seen.contains(studentId) || existing.containsKey(studentId)) {
					continue;
				}
				AttendanceStatus status = acknowledged.containsKey(studentId) ? AttendanceStatus.EXCUSED : AttendanceStatus.PRESENT;
				marks.add(new MarkInput(studentId, status, null, null, null, true));
			}
		}
		if (marks.isEmpty() && existingSession.isEmpty()) {
			throw ApiException.conflict("attendance.nothing_to_record");
		}
		SessionRow session = existingSession.orElseGet(() -> createSession(user, schoolId, classId, date, slot,
				cmd.subjectId(), ctx));
		int baseVersion = existingSession.map(SessionRow::version).orElse(0);
		List<AttendanceRecordOutcome> outcomes = new ArrayList<>();
		int applied = 0;
		int unchanged = 0;
		int conflicts = 0;
		for (MarkInput mark : marks) {
			RecordRow current = existing.get(mark.studentId());
			UUID reportId = (mark.status() == AttendanceStatus.EXCUSED) ? acknowledged.get(mark.studentId()) : null;
			if (current == null) {
				RecordRow created = insertRecord(user, session, mark, reportId, ctx);
				publish(created, session, user);
				outcomes.add(new AttendanceRecordOutcome(mark.studentId(), "APPLIED", view(created), null));
				applied++;
			}
			else if (current.status() == mark.status() && Objects.equals(current.minutesLate(), mark.minutesLate())
					&& Objects.equals(current.note(), mark.note())) {
				outcomes.add(new AttendanceRecordOutcome(mark.studentId(), "UNCHANGED", view(current), null));
				unchanged++;
			}
			else if (informed(current, mark, cmd.baseVersion(), baseVersion, ctx, user)) {
				RecordRow updated = updateRecord(user, current, mark, reportId, ctx);
				if (updated == null) {
					outcomes.add(conflict(current, mark));
					conflicts++;
				}
				else {
					publish(updated, session, user);
					outcomes.add(new AttendanceRecordOutcome(mark.studentId(), "APPLIED", view(updated), null));
					applied++;
				}
			}
			else {
				outcomes.add(conflict(current, mark));
				conflicts++;
			}
		}
		int version = session.version();
		if (applied > 0) {
			version = touch(session.id());
		}
		if (applied > 0) {
			AuditEntry entry = AuditEntry.of((overrideReason != null) ? "attendance.roll.override" : "attendance.roll.save",
					AuditOutcome.SUCCESS)
				.target("attendance_session", session.id())
				.school(schoolId)
				.metadata("classId", classId.toString())
				.metadata("date", date.toString())
				.metadata("slot", slot)
				.metadata("source", ctx.source())
				.metadata("applied", applied)
				.metadata("unchanged", unchanged)
				.metadata("conflicts", conflicts);
			if (overrideReason != null) {
				entry.metadata("reason", overrideReason);
			}
			this.audit.record(entry);
		}
		return new AttendanceWriteResult(session.id(), classId, date, slot, version, overrideReason != null && applied > 0,
				applied, unchanged, conflicts, outcomes);
	}

	/** May this mark replace the stored, different record? Only if its writer saw that record (see the class comment). */
	private static boolean informed(RecordRow current, MarkInput mark, Integer baseVersion, int sessionVersion,
			WriteContext ctx, CurrentUser user) {
		if (mark.expectedVersion() != null) {
			return mark.expectedVersion() == current.version();
		}
		if (baseVersion != null && baseVersion == sessionVersion) {
			return true;
		}
		// The same device's own earlier edit of the same record, queued before it could learn the new version.
		return ctx.deviceId() != null && ctx.deviceId().equals(current.deviceId())
				&& user.userId().equals(current.recordedBy()) && !ctx.recordedAt().isBefore(current.recordedAt());
	}

	private AttendanceRecordOutcome conflict(RecordRow current, MarkInput mark) {
		return new AttendanceRecordOutcome(mark.studentId(), "CONFLICT", view(current),
				new AttendanceSubmittedMark(mark.status(), mark.minutesLate(), mark.note(), mark.expectedVersion()));
	}

	private void publish(RecordRow record, SessionRow session, CurrentUser user) {
		this.events.publishEvent(new AttendanceMarked(record.id(), record.version(), session.id(), session.schoolId(),
				session.classId(), record.studentId(), session.date(), record.status(), user.userId()));
	}

	// --- rows -------------------------------------------------------------------------------

	private SessionRow createSession(CurrentUser user, UUID schoolId, UUID classId, LocalDate date, String slot,
			UUID subjectId, WriteContext ctx) {
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into attendance_sessions (id, school_id, class_id, attendance_date, slot, subject_id, taken_by, taken_at,
				                                 client_op_id, created_at, updated_at)
				values (:id, :school, :class, :date, :slot, :subject, :by, :at, :op, :now, :now)""")
			.param("id", id)
			.param("school", schoolId)
			.param("class", classId)
			.param("date", java.sql.Date.valueOf(date))
			.param("slot", slot)
			.param("subject", subjectId)
			.param("by", user.userId())
			.param("at", Timestamp.from(ctx.recordedAt()))
			.param("op", ctx.clientOperationId())
			.param("now", Timestamp.from(this.time.now()))
			.update();
		return new SessionRow(id, schoolId, classId, date, slot, subjectId, 0, user.userId(), ctx.recordedAt());
	}

	private int touch(UUID sessionId) {
		return this.jdbc.sql("""
				update attendance_sessions set version = version + 1, updated_at = :now where id = :id returning version""")
			.param("now", Timestamp.from(this.time.now()))
			.param("id", sessionId)
			.query(Integer.class)
			.single();
	}

	private RecordRow insertRecord(CurrentUser user, SessionRow session, MarkInput mark, UUID reportId, WriteContext ctx) {
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(this.time.now());
		this.jdbc.sql("""
				insert into attendance_records (id, attendance_session_id, school_id, class_id, attendance_date, student_id,
				                                status, minutes_late, note, absence_report_id, recorded_by, recorded_at, source,
				                                device_id, client_op_id, created_at, updated_at)
				values (:id, :session, :school, :class, :date, :student, :status, :minutes, :note, :report, :by, :at, :source,
				        :device, :op, :now, :now)""")
			.param("id", id)
			.param("session", session.id())
			.param("school", session.schoolId())
			.param("class", session.classId())
			.param("date", java.sql.Date.valueOf(session.date()))
			.param("student", mark.studentId())
			.param("status", mark.status().name())
			.param("minutes", mark.minutesLate())
			.param("note", mark.note())
			.param("report", reportId)
			.param("by", user.userId())
			.param("at", Timestamp.from(ctx.recordedAt()))
			.param("source", ctx.source())
			.param("device", ctx.deviceId())
			.param("op", ctx.clientOperationId())
			.param("now", now)
			.update();
		return new RecordRow(id, mark.studentId(), mark.status(), mark.minutesLate(), mark.note(), 0, user.userId(),
				ctx.recordedAt(), ctx.source(), ctx.deviceId(), reportId);
	}

	/** Null when the record changed under us (cannot happen while the roll's advisory lock is held, but never overwrite). */
	private RecordRow updateRecord(CurrentUser user, RecordRow current, MarkInput mark, UUID reportId, WriteContext ctx) {
		int updated = this.jdbc.sql("""
				update attendance_records set status = :status, minutes_late = :minutes, note = :note, absence_report_id = :report,
				       recorded_by = :by, recorded_at = :at, source = :source, device_id = :device, client_op_id = :op,
				       version = version + 1, updated_at = :now
				where id = :id and version = :version""")
			.param("status", mark.status().name())
			.param("minutes", mark.minutesLate())
			.param("note", mark.note())
			.param("report", reportId)
			.param("by", user.userId())
			.param("at", Timestamp.from(ctx.recordedAt()))
			.param("source", ctx.source())
			.param("device", ctx.deviceId())
			.param("op", ctx.clientOperationId())
			.param("now", Timestamp.from(this.time.now()))
			.param("id", current.id())
			.param("version", current.version())
			.update();
		if (updated == 0) {
			return null;
		}
		return new RecordRow(current.id(), current.studentId(), mark.status(), mark.minutesLate(), mark.note(),
				current.version() + 1, user.userId(), ctx.recordedAt(), ctx.source(), ctx.deviceId(), reportId);
	}

	private Optional<SessionRow> session(UUID classId, LocalDate date, String slot) {
		return this.jdbc.sql("""
				select id, school_id, class_id, attendance_date, slot, subject_id, version, taken_by, taken_at from attendance_sessions
				where class_id = :c and attendance_date = :d and slot = :s""")
			.param("c", classId)
			.param("d", java.sql.Date.valueOf(date))
			.param("s", slot)
			.query((rs, i) -> new SessionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "class_id"),
					Rows.date(rs, "attendance_date"), rs.getString("slot"), Rows.uuid(rs, "subject_id"), rs.getInt("version"),
						Rows.uuid(rs, "taken_by"),
					Rows.instant(rs, "taken_at")))
			.optional();
	}

	private List<RecordRow> records(UUID sessionId) {
		return this.jdbc.sql("select " + RECORD_COLUMNS + " from attendance_records r where r.attendance_session_id = :s")
			.param("s", sessionId)
			.query(RollService::record)
			.list();
	}

	static RecordRow record(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new RecordRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "student_id"), AttendanceStatus.valueOf(rs.getString("status")),
				Rows.integer(rs, "minutes_late"), rs.getString("note"), rs.getInt("version"), Rows.uuid(rs, "recorded_by"),
				Rows.instant(rs, "recorded_at"), rs.getString("source"), Rows.uuid(rs, "device_id"),
				Rows.uuid(rs, "absence_report_id"));
	}

	/**
	 * A period roll's subject: one of the class's active subjects (400 {@code attendance.invalid_subject}), and only for
	 * the periods P1..P8 (400 {@code attendance.subject_requires_period}).
	 */
	private void requirePeriodSubject(UUID schoolId, UUID classId, String slot, UUID subjectId) {
		boolean taught = this.jdbc.sql("""
				select exists (select 1 from class_subjects cs join subjects s on s.id = cs.subject_id and s.school_id = cs.school_id
				               where cs.class_id = :class and cs.subject_id = :subject and cs.school_id = :school and s.is_active)""")
			.param("class", classId)
			.param("subject", subjectId)
			.param("school", schoolId)
			.query(Boolean.class)
			.single();
		if (!taught) {
			throw ApiException.badRequest("attendance.invalid_subject");
		}
		if (!slot.matches("P[1-8]")) {
			throw ApiException.badRequest("attendance.subject_requires_period");
		}
	}

	private String subjectName(UUID subjectId) {
		return this.jdbc.sql("select name from subjects where id = :id").param("id", subjectId).query(String.class).optional().orElse(null);
	}

	/** The members of a saved student group of the class (an elective's group); 400 {@code attendance.invalid_group} otherwise. */
	private Set<UUID> groupMembers(UUID schoolId, UUID classId, UUID groupId) {
		boolean known = this.jdbc.sql("select exists (select 1 from student_groups where id = :g and class_id = :c and school_id = :s)")
			.param("g", groupId)
			.param("c", classId)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw ApiException.badRequest("attendance.invalid_group");
		}
		return new HashSet<>(this.jdbc.sql("select student_id from student_group_members where group_id = :g")
			.param("g", groupId)
			.query(UUID.class)
			.list());
	}

	/** Reports covering the date for the students, SUBMITTED or ACKNOWLEDGED; an acknowledged one wins over a submitted one. */
	private Map<UUID, ReportRow> reportsCovering(UUID schoolId, Collection<UUID> studentIds, LocalDate date) {
		Map<UUID, ReportRow> reports = new LinkedHashMap<>();
		if (studentIds.isEmpty()) {
			return reports;
		}
		this.jdbc.sql("""
				select id, student_id, status, reason_code, note, from_date, to_date from absence_reports
				where school_id = :s and student_id in (:ids) and status in ('SUBMITTED', 'ACKNOWLEDGED')
				  and from_date <= :d and to_date >= :d
				order by case status when 'ACKNOWLEDGED' then 0 else 1 end desc, created_at, id""")
			.param("s", schoolId)
			.param("ids", studentIds)
			.param("d", java.sql.Date.valueOf(date))
			.query((rs, i) -> {
				reports.put(Rows.uuid(rs, "student_id"), new ReportRow(Rows.uuid(rs, "id"), rs.getString("status"),
						rs.getString("reason_code"), rs.getString("note"), Rows.date(rs, "from_date"), Rows.date(rs, "to_date")));
				return null;
			})
			.list();
		return reports;
	}

	/** Student to the acknowledged report that covers the date. */
	private Map<UUID, UUID> acknowledgedReports(UUID schoolId, Collection<UUID> studentIds, LocalDate date) {
		Map<UUID, UUID> result = new HashMap<>();
		reportsCovering(schoolId, studentIds, date).forEach((student, report) -> {
			if ("ACKNOWLEDGED".equals(report.status())) {
				result.put(student, report.id());
			}
		});
		return result;
	}

	Instant locksAt(UUID schoolId, LocalDate date, ZoneId zone) {
		return this.time.endOf(date, zone).plus(Duration.ofHours(this.settings.lockAfterHours(schoolId)));
	}

	private static String clean(String note) {
		return (note == null || note.isBlank()) ? null : note.strip();
	}

	static AttendanceRecordView view(RecordRow r) {
		return new AttendanceRecordView(r.id(), r.status(), r.minutesLate(), r.note(), r.version(), r.recordedBy(),
				r.recordedAt(), r.source(), r.absenceReportId());
	}

	private static AbsenceAnnotationView annotation(ReportRow report, boolean details) {
		return new AbsenceAnnotationView(report.id(), report.status(), details ? report.reasonCode() : null, report.fromDate(),
				report.toDate(), details ? report.note() : null);
	}

	// --- internal shapes --------------------------------------------------------------------

	record SessionRow(UUID id, UUID schoolId, UUID classId, LocalDate date, String slot, UUID subjectId, int version, UUID takenBy,
			Instant takenAt) {
	}

	record RecordRow(UUID id, UUID studentId, AttendanceStatus status, Integer minutesLate, String note, int version,
			UUID recordedBy, Instant recordedAt, String source, UUID deviceId, UUID absenceReportId) {
	}

	private record ReportRow(UUID id, String status, String reasonCode, String note, LocalDate fromDate, LocalDate toDate) {
	}

	private record MarkInput(UUID studentId, AttendanceStatus status, Integer minutesLate, String note, Integer expectedVersion,
			boolean generated) {
	}

}
