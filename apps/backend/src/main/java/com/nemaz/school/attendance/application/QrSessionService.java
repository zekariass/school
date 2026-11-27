package com.nemaz.school.attendance.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.attendance.application.CheckinModels.OpenClassQrSessionRequest;
import com.nemaz.school.attendance.application.CheckinModels.OpenStaffQrSessionRequest;
import com.nemaz.school.attendance.application.CheckinModels.QrCode;
import com.nemaz.school.attendance.application.CheckinModels.QrCodes;
import com.nemaz.school.attendance.application.CheckinModels.QrSessionDetail;
import com.nemaz.school.attendance.application.CheckinModels.QrSessionEntry;
import com.nemaz.school.attendance.application.CheckinModels.QrSessionView;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotating QR sessions (M7e): a code that a teacher (a class roll) or a manager (the staff of a campus or the school, for the
 * day) shows on a screen and that changes every {@code attendance.qr.rotation_seconds}. The code of a window is
 * {@code HMAC(server key, qr_session_id | window)} ({@link AttendanceKeys}); nothing secret is stored per session, and it
 * is handed only to the people who run the session ({@link #codes}), with about ten minutes of windows ahead so that the
 * display keeps rotating without a connection. Opening one closes the other open codes of the same roll (an old photograph
 * dies with it). The teacher sees who checked in and can take one entry off ({@link #removeEntry}).
 * <p>
 * <b>Residual risk (documented, accepted by the product, D17):</b> a student can photograph the code and send it to a friend
 * who is not in the room. The mitigations are the short window, the live list and the teacher's right to correct the roll.
 */
@Service
@Transactional
public class QrSessionService {

	static final int DEFAULT_CLASS_MINUTES = 15;

	static final int MAX_CLASS_MINUTES = 120;

	static final int DEFAULT_STAFF_MINUTES = 60;

	static final int MAX_STAFF_MINUTES = 480;

	/** How far ahead the display gets its codes. */
	static final Duration HORIZON = Duration.ofMinutes(10);

	private static final String SESSION_COLUMNS = """
			s.id, s.school_id, s.subject_kind, s.class_id, s.attendance_date, s.slot, s.subject_id, s.campus_id, s.opened_by,
			s.opens_at, s.closes_at, s.rotation_s, s.late_after_min, s.status""";

	private final AttendanceAccess access;

	private final AttendanceSettings settings;

	private final AttendanceKeys keys;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final RollService rolls;

	private final StaffAttendanceService staff;

	QrSessionService(AttendanceAccess access, AttendanceSettings settings, AttendanceKeys keys, SchoolTime time, JdbcClient jdbc,
			AuditLog audit, RollService rolls, StaffAttendanceService staff) {
		this.access = access;
		this.settings = settings;
		this.keys = keys;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
		this.rolls = rolls;
		this.staff = staff;
	}

	/** A stored session. {@code kind} is STUDENT or STAFF. */
	record SessionRow(UUID id, UUID schoolId, String kind, UUID classId, LocalDate date, String slot, UUID subjectId,
			UUID campusId, UUID openedBy, Instant opensAt, Instant closesAt, int rotationSeconds, Integer lateAfterMinutes,
			String status) {

		boolean student() {
			return "STUDENT".equals(this.kind);
		}

		/** Open for check-ins at {@code now}: not closed, not before it opens, not after its time ran out. */
		boolean openAt(Instant now) {
			return "OPEN".equals(this.status) && !now.isBefore(this.opensAt) && now.isBefore(this.closesAt);
		}

	}

	// --- opening ----------------------------------------------------------------------------

	/** Opens a code for today's roll of the class; the people who may take the roll. */
	public QrSessionView openForClass(CurrentUser user, UUID schoolId, UUID classId, OpenClassQrSessionRequest cmd) {
		ClassRef ref = this.access.takeableClass(user, schoolId, classId);
		this.access.requireActiveSchool(schoolId);
		this.access.requireMethod(schoolId, AttendanceSettings.METHOD_QR_ROTATING);
		requireCodes();
		String slot = (cmd.slot() == null || cmd.slot().isBlank()) ? "DAY" : cmd.slot();
		if (!this.settings.slots(schoolId).contains(slot)) {
			throw ApiException.badRequest("attendance.slot_not_enabled");
		}
		if (cmd.subjectId() != null) {
			this.rolls.requirePeriodSubject(schoolId, classId, slot, cmd.subjectId());
		}
		int minutes = minutes(cmd.durationMinutes(), DEFAULT_CLASS_MINUTES, MAX_CLASS_MINUTES);
		Integer late = lateAfter(cmd.lateAfterMinutes());
		ZoneId zone = this.time.zoneOf(ref);
		Instant now = this.time.now();
		LocalDate today = this.time.today(zone);
		if (!now.isBefore(this.rolls.locksAt(schoolId, today, zone))) {
			throw ApiException.conflict("attendance.session_locked");
		}
		this.jdbc.sql("""
				update attendance_qr_sessions set status = 'CLOSED', closed_at = :now, closed_by = :by
				where class_id = :class and attendance_date = :date and slot = :slot and status = 'OPEN'""")
			.param("now", Timestamp.from(now))
			.param("by", user.userId())
			.param("class", classId)
			.param("date", java.sql.Date.valueOf(today))
			.param("slot", slot)
			.update();
		UUID id = insert(schoolId, "STUDENT", classId, today, slot, cmd.subjectId(), null, user.userId(), now, minutes, late);
		this.audit.record(AuditEntry.of("attendance.qr_session.open", AuditOutcome.SUCCESS)
			.target("attendance_qr_session", id)
			.school(schoolId)
			.metadata("kind", "STUDENT")
			.metadata("classId", classId.toString())
			.metadata("date", today.toString())
			.metadata("slot", slot)
			.metadata("minutes", minutes));
		return view(load(schoolId, id), 0);
	}

	/** Opens a code for today's staff of a campus (or the whole school); {@code school.staff_attendance.manage}. */
	public QrSessionView openForStaff(CurrentUser user, UUID schoolId, OpenStaffQrSessionRequest cmd) {
		this.staff.requireManager(user, schoolId);
		this.access.requireActiveSchool(schoolId);
		this.access.requireMethod(schoolId, AttendanceSettings.METHOD_QR_ROTATING);
		requireCodes();
		if (cmd.campusId() != null) {
			boolean known = this.jdbc.sql("select exists (select 1 from campuses where id = :c and school_id = :s)")
				.param("c", cmd.campusId())
				.param("s", schoolId)
				.query(Boolean.class)
				.single();
			if (!known) {
				throw ApiException.notFound("campus.not_found");
			}
		}
		int minutes = minutes(cmd.durationMinutes(), DEFAULT_STAFF_MINUTES, MAX_STAFF_MINUTES);
		Integer late = (cmd.lateAfterMinutes() != null) ? lateAfter(cmd.lateAfterMinutes())
				: (this.settings.staffLateAfterMinutes(schoolId) > 0) ? this.settings.staffLateAfterMinutes(schoolId) : null;
		Instant now = this.time.now();
		LocalDate today = this.time.today(this.time.zoneOf(schoolId, cmd.campusId()));
		this.jdbc.sql("""
				update attendance_qr_sessions set status = 'CLOSED', closed_at = :now, closed_by = :by
				where school_id = :school and subject_kind = 'STAFF' and attendance_date = :date and status = 'OPEN'
				  and campus_id is not distinct from :campus""")
			.param("now", Timestamp.from(now))
			.param("by", user.userId())
			.param("school", schoolId)
			.param("date", java.sql.Date.valueOf(today))
			.param("campus", cmd.campusId())
			.update();
		UUID id = insert(schoolId, "STAFF", null, today, null, null, cmd.campusId(), user.userId(), now, minutes, late);
		this.audit.record(AuditEntry.of("attendance.qr_session.open", AuditOutcome.SUCCESS)
			.target("attendance_qr_session", id)
			.school(schoolId)
			.metadata("kind", "STAFF")
			.metadata("campusId", (cmd.campusId() != null) ? cmd.campusId().toString() : null)
			.metadata("date", today.toString())
			.metadata("minutes", minutes));
		return view(load(schoolId, id), 0);
	}

	// --- running ----------------------------------------------------------------------------

	/** The session and its live list (who checked in, oldest first). */
	@Transactional(readOnly = true)
	public QrSessionDetail detail(CurrentUser user, UUID schoolId, UUID sessionId) {
		SessionRow session = authorize(user, schoolId, sessionId);
		return detail(session);
	}

	/**
	 * The codes of the current window and the windows ahead (up to ten minutes and the end of the session), for the people
	 * who run it only; 409 {@code attendance.qr_session_closed} once it was closed or its time ran out.
	 */
	@Transactional(readOnly = true)
	public QrCodes codes(CurrentUser user, UUID schoolId, UUID sessionId) {
		SessionRow session = authorize(user, schoolId, sessionId);
		requireCodes();
		Instant now = this.time.now();
		if (!"OPEN".equals(session.status()) || !now.isBefore(session.closesAt())) {
			throw ApiException.conflict("attendance.qr_session_closed");
		}
		long rotation = session.rotationSeconds();
		long first = now.getEpochSecond() / rotation;
		Instant until = now.plus(HORIZON).isBefore(session.closesAt()) ? now.plus(HORIZON) : session.closesAt();
		long last = Math.max(first, (until.getEpochSecond() - 1) / rotation);
		List<QrCode> codes = new ArrayList<>();
		for (long window = first; window <= last; window++) {
			String code = this.keys.code(sessionId, window);
			codes.add(new QrCode(Instant.ofEpochSecond(window * rotation), Instant.ofEpochSecond((window + 1) * rotation), code,
					sessionId + "." + code));
		}
		return new QrCodes(sessionId, now, session.rotationSeconds(), codes);
	}

	/** Closes the session (idempotent): its codes stop working at once. */
	public QrSessionView close(CurrentUser user, UUID schoolId, UUID sessionId) {
		SessionRow session = authorize(user, schoolId, sessionId);
		if ("OPEN".equals(session.status())) {
			this.jdbc.sql("update attendance_qr_sessions set status = 'CLOSED', closed_at = :now, closed_by = :by where id = :id and status = 'OPEN'")
				.param("now", Timestamp.from(this.time.now()))
				.param("by", user.userId())
				.param("id", sessionId)
				.update();
			this.audit.record(AuditEntry.of("attendance.qr_session.close", AuditOutcome.SUCCESS)
				.target("attendance_qr_session", sessionId)
				.school(schoolId)
				.metadata("kind", session.kind()));
		}
		return view(load(schoolId, sessionId), acceptedCount(sessionId));
	}

	/**
	 * Takes one accepted check-in off the roll (or the staff day): the mark it wrote is deleted while nobody has changed it
	 * since (409 {@code attendance.checkin_changed} otherwise), the removal is appended (append-only) and audited, and the
	 * person cannot check in again to this session (one accepted check-in per person per session, even a removed one:
	 * the teacher marks them by hand). A notification already sent for a late mark is not recalled.
	 */
	public QrSessionDetail removeEntry(CurrentUser user, UUID schoolId, UUID sessionId, UUID checkinId) {
		SessionRow session = authorize(user, schoolId, sessionId);
		Boolean removable = this.jdbc.sql("""
				select not exists (select 1 from attendance_checkin_removals r where r.checkin_id = c.id)
				from attendance_checkins c
				where c.id = :id and c.school_id = :school and c.qr_session_id = :session and c.result = 'ACCEPTED'""")
			.param("id", checkinId)
			.param("school", schoolId)
			.param("session", sessionId)
			.query(Boolean.class)
			.optional()
			.orElse(null);
		if (removable == null || !removable) {
			throw ApiException.notFound("attendance.checkin_not_found");
		}
		boolean removed = session.student() ? this.rolls.removeCheckinMark(checkinId) : this.staff.removeCheckinRecord(checkinId);
		if (!removed) {
			throw ApiException.conflict("attendance.checkin_changed");
		}
		this.jdbc.sql("""
				insert into attendance_checkin_removals (id, school_id, checkin_id, removed_by, removed_at)
				values (:id, :school, :checkin, :by, :now)""")
			.param("id", Ids.newId())
			.param("school", schoolId)
			.param("checkin", checkinId)
			.param("by", user.userId())
			.param("now", Timestamp.from(this.time.now()))
			.update();
		this.audit.record(AuditEntry.of("attendance.checkin.remove", AuditOutcome.SUCCESS)
			.target("attendance_checkin", checkinId)
			.school(schoolId)
			.metadata("qrSessionId", sessionId.toString())
			.metadata("kind", session.kind()));
		return detail(session);
	}

	// --- reading a session (also for CheckinService) ----------------------------------------

	/** The session of the school; 404 {@code attendance.qr_session_not_found} for an unknown id or another school's. */
	SessionRow load(UUID schoolId, UUID sessionId) {
		return this.jdbc.sql("select " + SESSION_COLUMNS + " from attendance_qr_sessions s where s.id = :id and s.school_id = :school")
			.param("id", sessionId)
			.param("school", schoolId)
			.query((rs, i) -> new SessionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), rs.getString("subject_kind"),
					Rows.uuid(rs, "class_id"), Rows.date(rs, "attendance_date"), rs.getString("slot"), Rows.uuid(rs, "subject_id"),
					Rows.uuid(rs, "campus_id"), Rows.uuid(rs, "opened_by"), Rows.instant(rs, "opens_at"), Rows.instant(rs, "closes_at"),
					rs.getInt("rotation_s"), Rows.integer(rs, "late_after_min"), rs.getString("status")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("attendance.qr_session_not_found"));
	}

	/**
	 * Who may run the session: for a class code the people who may take that roll, for a staff code the staff attendance
	 * managers. The school must be visible (404); a class the caller cannot see is as unknown as the session (404).
	 */
	private SessionRow authorize(CurrentUser user, UUID schoolId, UUID sessionId) {
		this.access.gate().requireVisible(user, schoolId);
		SessionRow session = load(schoolId, sessionId);
		if (session.student()) {
			if (!this.access.classes().canView(user, session.classId()) && !this.access.reader(user, schoolId)) {
				throw ApiException.notFound("attendance.qr_session_not_found");
			}
			this.access.takeableClass(user, schoolId, session.classId());
		}
		else {
			this.staff.requireManager(user, schoolId);
		}
		return session;
	}

	QrSessionView view(SessionRow s, int accepted) {
		ZoneId zone = s.student() ? this.access.classes().find(s.classId()).map(this.time::zoneOf).orElse(this.time.zoneOfSchool(s.schoolId()))
				: this.time.zoneOf(s.schoolId(), s.campusId());
		Instant now = this.time.now();
		return new QrSessionView(s.id(), s.kind(), s.classId(), s.date(), s.slot(), s.campusId(), s.openedBy(), s.opensAt(),
				s.closesAt(), s.rotationSeconds(), s.lateAfterMinutes(), s.status(), s.openAt(now), accepted, zone.getId());
	}

	// --- internals --------------------------------------------------------------------------

	private QrSessionDetail detail(SessionRow session) {
		List<EntryRow> rows = this.jdbc.sql("""
				select c.id, coalesce(c.student_id, c.staff_id) as person_id, coalesce(c.student_id, st.user_id) as user_id,
				       c.marked_status, c.minutes_late, c.checked_in_at,
				       exists (select 1 from attendance_checkin_removals r where r.checkin_id = c.id) as removed
				from attendance_checkins c
				left join school_staff st on st.id = c.staff_id
				where c.qr_session_id = :session and c.result = 'ACCEPTED'
				order by c.checked_in_at, c.id""")
			.param("session", session.id())
			.query((rs, i) -> new EntryRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "person_id"), Rows.uuid(rs, "user_id"),
					rs.getString("marked_status"), Rows.integer(rs, "minutes_late"), Rows.instant(rs, "checked_in_at"),
					rs.getBoolean("removed")))
			.list();
		Set<UUID> userIds = new LinkedHashSet<>();
		rows.forEach((r) -> userIds.add(r.userId()));
		Map<UUID, PersonCard> cards = userIds.isEmpty() ? Map.of() : this.access.people().cards(userIds);
		List<QrSessionEntry> entries = new ArrayList<>();
		int accepted = 0;
		for (EntryRow row : rows) {
			PersonCard card = cards.get(row.userId());
			entries.add(new QrSessionEntry(row.id(), row.personId(), (card != null) ? card.displayName() : "",
					(card != null) ? card.avatar() : null, row.status(), row.minutesLate(), row.checkedInAt(), row.removed()));
			if (!row.removed()) {
				accepted++;
			}
		}
		return new QrSessionDetail(view(session, accepted), entries);
	}

	private int acceptedCount(UUID sessionId) {
		return this.jdbc.sql("""
				select count(*) from attendance_checkins c
				where c.qr_session_id = :s and c.result = 'ACCEPTED'
				  and not exists (select 1 from attendance_checkin_removals r where r.checkin_id = c.id)""")
			.param("s", sessionId)
			.query(Integer.class)
			.single();
	}

	private UUID insert(UUID schoolId, String kind, UUID classId, LocalDate date, String slot, UUID subjectId, UUID campusId,
			UUID openedBy, Instant now, int minutes, Integer lateAfter) {
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into attendance_qr_sessions (id, school_id, subject_kind, class_id, attendance_date, slot, subject_id, campus_id,
				                                    opened_by, opens_at, closes_at, rotation_s, late_after_min)
				values (:id, :school, :kind, :class, :date, :slot, :subject, :campus, :by, :opens, :closes, :rotation, :late)""")
			.param("id", id)
			.param("school", schoolId)
			.param("kind", kind)
			.param("class", classId)
			.param("date", java.sql.Date.valueOf(date))
			.param("slot", slot)
			.param("subject", subjectId)
			.param("campus", campusId)
			.param("by", openedBy)
			.param("opens", Timestamp.from(now))
			.param("closes", Timestamp.from(now.plus(Duration.ofMinutes(minutes))))
			.param("rotation", this.settings.rotationSeconds(schoolId))
			.param("late", lateAfter)
			.update();
		return id;
	}

	private void requireCodes() {
		if (!this.keys.codesAvailable()) {
			throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "attendance.secrets_unavailable");
		}
	}

	private static int minutes(Integer requested, int fallback, int max) {
		int minutes = (requested != null) ? requested : fallback;
		if (minutes < 1 || minutes > max) {
			throw ApiException.badRequest("attendance.qr_invalid_duration", max);
		}
		return minutes;
	}

	private static Integer lateAfter(Integer requested) {
		if (requested != null && (requested < 0 || requested > 600)) {
			throw ApiException.badRequest("attendance.qr_invalid_late_after");
		}
		return requested;
	}

	private record EntryRow(UUID id, UUID personId, UUID userId, String status, Integer minutesLate, Instant checkedInAt,
			boolean removed) {
	}

}
