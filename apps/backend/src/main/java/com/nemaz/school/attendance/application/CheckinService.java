package com.nemaz.school.attendance.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.StaffAttendanceStatus;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRecordView;
import com.nemaz.school.attendance.application.CheckinLedger.Attempt;
import com.nemaz.school.attendance.application.CheckinLedger.Stored;
import com.nemaz.school.attendance.application.CheckinModels.CheckinMethods;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResult;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResultCode;
import com.nemaz.school.attendance.application.CheckinModels.QrCheckinRequest;
import com.nemaz.school.attendance.application.CheckinModels.ScannedPerson;
import com.nemaz.school.attendance.application.CheckinModels.StaffScanRequest;
import com.nemaz.school.attendance.application.CheckinModels.StaticScanRequest;
import com.nemaz.school.attendance.application.CredentialService.ScanCredential;
import com.nemaz.school.attendance.application.QrSessionService.SessionRow;
import com.nemaz.school.attendance.application.RollService.CheckinMark;
import com.nemaz.school.attendance.application.RollService.CheckinOutcome;
import com.nemaz.school.attendance.application.StaffAttendanceService.StaffCheckin;
import com.nemaz.school.attendance.application.StaffAttendanceService.StaffCheckinOutcome;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.error.ProblemDetailFactory;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The QR check-in (M7e): a student's (or staff member's) own check-in on a rotating code, and a teacher's (manager's) scan of a
 * card. Every road ends in {@link RollService#markFromCheckin} (students) or {@link StaffAttendanceService#recordFromCheckin}
 * (staff), so a scan is evidence and never authority: the roll's own rules (date, lock window, class membership, the
 * conflict rule, PRESENT or LATE only) decide, and what they refuse is a logged result, not a write.
 * <p>
 * <b>Order</b> (cheapest and most restrictive first; every refusal before it is a thrown error that writes nothing, every
 * refusal after it a logged result): who the caller is and what they may do (404, 403) and the switches (feature, active
 * school, method) → rate limits → the credential or the code → the roll. A refused attempt that names a person is appended to
 * the ledger and answered with 409 and a body ({@link CheckinResult}); an unknown credential is answered the same way but
 * names nobody and is only audited. Attempts stopped by a rate limit (429) or by a thrown error are not ledger rows.
 * <p>
 * <b>What a failed card scan reveals</b> (the scanner is staff of the school): a token that is unknown, malformed, expired, or
 * belongs to another school or to the other kind of person all answer the same {@code REJECTED_INVALID} (an expired card
 * is still logged as REJECTED_EXPIRED with the person); only a card of this school that was revoked says so
 * ({@code REJECTED_REVOKED}), because the scanner is told to send the child to the office. Nothing is ever returned about a
 * credential of another school. Tokens, codes and hashes are never logged, audited or returned.
 */
@Service
@Transactional
public class CheckinService {

	private final AttendanceAccess access;

	private final AttendanceSettings settings;

	private final AttendanceKeys keys;

	private final AttendanceCheckinProperties limits;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final CheckinLedger ledger;

	private final CredentialService credentials;

	private final QrSessionService sessions;

	private final RollService rolls;

	private final StaffAttendanceService staff;

	private final ProblemDetailFactory problems;

	CheckinService(AttendanceAccess access, AttendanceSettings settings, AttendanceKeys keys, AttendanceCheckinProperties limits,
			SchoolTime time, JdbcClient jdbc, AuditLog audit, RateLimiter limiter, CheckinLedger ledger,
			CredentialService credentials, QrSessionService sessions, RollService rolls, StaffAttendanceService staff,
			ProblemDetailFactory problems) {
		this.access = access;
		this.settings = settings;
		this.keys = keys;
		this.limits = limits;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
		this.limiter = limiter;
		this.ledger = ledger;
		this.credentials = credentials;
		this.sessions = sessions;
		this.rolls = rolls;
		this.staff = staff;
		this.problems = problems;
	}

	/** Which methods are on at the school (any member who can see it; the screens decide what to offer). */
	@Transactional(readOnly = true)
	public CheckinMethods methods(CurrentUser user, UUID schoolId) {
		this.access.gate().requireVisible(user, schoolId);
		this.access.requireFeature(schoolId);
		List<String> methods = this.settings.methods(schoolId).stream().sorted().toList();
		return new CheckinMethods(methods, this.settings.rotationSeconds(schoolId), this.settings.staffEnabled(schoolId),
				this.settings.staffLateAfterMinutes(schoolId));
	}

	// --- rotating codes: the person checks in -----------------------------------------------

	/**
	 * A student's or staff member's own check-in on the code of a session, as the authenticated caller (never anonymous).
	 * The caller must be an ACTIVE student of the school (class code) or an ACTIVE staff member (staff code): 403
	 * {@code attendance.checkin_not_allowed} for anybody else, a guardian or a teacher included.
	 */
	public CheckinResult checkIn(CurrentUser user, UUID schoolId, UUID sessionId, QrCheckinRequest request) {
		this.limiter.consume("attendance-checkin", user.userId().toString(), this.limits.studentAttemptsPerMinute(),
				Duration.ofMinutes(1), "attendance.checkin_rate_limited");
		this.access.gate().requireVisible(user, schoolId);
		SessionRow session = this.sessions.load(schoolId, sessionId);
		this.access.requireFeature(schoolId);
		this.access.requireActiveSchool(schoolId);
		this.access.requireMethod(schoolId, AttendanceSettings.METHOD_QR_ROTATING);
		if (!this.keys.codesAvailable()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "attendance.secrets_unavailable");
		}
		return session.student() ? studentCheckIn(user, schoolId, session, request.code())
				: staffCheckIn(user, schoolId, session, request.code());
	}

	private CheckinResult studentCheckIn(CurrentUser user, UUID schoolId, SessionRow session, String code) {
		if (!this.access.activeStudent(schoolId, user.userId())) {
			throw ApiException.forbidden("attendance.checkin_not_allowed");
		}
		ClassRef ref = this.access.classes().find(session.classId()).orElseThrow(() -> ApiException.notFound("attendance.qr_session_not_found"));
		ZoneId zone = this.time.zoneOf(ref);
		Instant now = this.time.now();
		Attempt attempt = Attempt.of(schoolId, "STUDENT", user.userId(), session.classId(), session.date(), session.slot(),
				"QR_ROTATING", user.userId(), null, session.id(), now, null, null);
		if (!this.access.memberships()
			.studentIdsDuring(session.classId(), this.time.startOf(session.date(), zone), this.time.endOf(session.date(), zone))
			.contains(user.userId())) {
			return logged(attempt, CheckinResultCode.REJECTED_NOT_ENROLLED, null, null, null, null);
		}
		// Only people who may check in to this code count against its class allowance: an outsider cannot use it up.
		this.limiter.consume("attendance-checkin-session", session.id().toString(), this.limits.classAttemptsPerMinute(),
				Duration.ofMinutes(1), "attendance.checkin_rate_limited");
		if (!session.openAt(now) || !this.time.today(zone).equals(session.date())) {
			return logged(attempt, CheckinResultCode.REJECTED_WINDOW, null, null, null, null);
		}
		if (!codeMatches(session, now, code)) {
			return logged(attempt, CheckinResultCode.REJECTED_EXPIRED, null, null, null, null);
		}
		if (this.ledger.acceptedIn(session.id(), user.userId())) {
			return logged(attempt, CheckinResultCode.REJECTED_DUPLICATE, null, null, null, null);
		}
		int minutesLate = minutesLate(session, now);
		AttendanceStatus status = (minutesLate > 0) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
		CheckinOutcome outcome = this.rolls.markFromCheckin(user, new CheckinMark(attempt, ref, status,
				(minutesLate > 0) ? minutesLate : null, session.openedBy(), session.subjectId(), now));
		// A student never sees the stored mark of the roll (it may hold the teacher's note): only the result.
		return answer(outcome.result(), status, (minutesLate > 0) ? minutesLate : null, outcome.checkinId(), null, null);
	}

	private CheckinResult staffCheckIn(CurrentUser user, UUID schoolId, SessionRow session, String code) {
		UUID staffId = this.staff.activeStaffIdOf(schoolId, user.userId())
			.orElseThrow(() -> ApiException.forbidden("attendance.checkin_not_allowed"));
		this.staff.requireCheckinOn(schoolId);
		UUID campus = this.jdbc.sql("select campus_id from school_staff where id = :id").param("id", staffId).query(UUID.class).optional().orElse(null);
		ZoneId zone = this.time.zoneOf(schoolId, campus);
		Instant now = this.time.now();
		Attempt attempt = Attempt.of(schoolId, "STAFF", staffId, null, session.date(), null, "QR_ROTATING", user.userId(), null,
				session.id(), now, null, null);
		if (session.campusId() != null && !session.campusId().equals(campus)) {
			return logged(attempt, CheckinResultCode.REJECTED_NOT_ENROLLED, null, null, null, null);
		}
		this.limiter.consume("attendance-checkin-session", session.id().toString(), this.limits.classAttemptsPerMinute(),
				Duration.ofMinutes(1), "attendance.checkin_rate_limited");
		if (!session.openAt(now) || !this.time.today(zone).equals(session.date())) {
			return logged(attempt, CheckinResultCode.REJECTED_WINDOW, null, null, null, null);
		}
		if (!codeMatches(session, now, code)) {
			return logged(attempt, CheckinResultCode.REJECTED_EXPIRED, null, null, null, null);
		}
		if (this.ledger.acceptedIn(session.id(), staffId)) {
			return logged(attempt, CheckinResultCode.REJECTED_DUPLICATE, null, null, null, null);
		}
		int minutesLate = minutesLate(session, now);
		StaffAttendanceStatus status = (minutesLate > 0) ? StaffAttendanceStatus.LATE : StaffAttendanceStatus.PRESENT;
		StaffCheckinOutcome outcome = this.staff
			.recordFromCheckin(new StaffCheckin(attempt, status, (minutesLate > 0) ? minutesLate : null));
		return answer(outcome.result(), AttendanceStatus.valueOf(status.name()), (minutesLate > 0) ? minutesLate : null,
				outcome.checkinId(), null, null);
	}

	/** The code of the current or the previous window of the session (the one a slightly slow scan still shows). */
	private boolean codeMatches(SessionRow session, Instant now, String code) {
		long window = now.getEpochSecond() / session.rotationSeconds();
		return this.keys.matches(session.id(), List.of(window, window - 1), code);
	}

	/** Minutes after the code opened, when the session has a late threshold and the check-in is past it; else 0. */
	private static int minutesLate(SessionRow session, Instant now) {
		if (session.lateAfterMinutes() == null || !now.isAfter(session.opensAt().plus(Duration.ofMinutes(session.lateAfterMinutes())))) {
			return 0;
		}
		return (int) Math.max(1, Math.min(600, Duration.between(session.opensAt(), now).toMinutes()));
	}

	// --- static cards: the teacher scans ----------------------------------------------------

	/** What {@link #scan} needs besides the caller; {@code recordedAt} is the device time of an offline scan (clamped by sync). */
	record Scan(UUID schoolId, UUID classId, String token, LocalDate date, String slot, UUID subjectId, AttendanceStatus status,
			Integer minutesLate, UUID clientOpId, Instant clientTs, Instant recordedAt) {

		@Override
		public String toString() {
			return "Scan[redacted]";
		}

	}

	public CheckinResult scan(CurrentUser user, UUID schoolId, UUID classId, StaticScanRequest request) {
		Instant now = this.time.now();
		return scan(user, new Scan(schoolId, classId, request.token(), request.date(), request.slot(), request.subjectId(),
				request.status(), request.minutesLate(), request.clientOpId(), null, now));
	}

	/**
	 * A teacher (or class manager, or attendance manager: whoever may take the roll) scans a student's card for a class roll.
	 * The same call serves the offline queue ({@code ATTENDANCE_CHECKIN}); {@code clientOpId} makes a retry or a replay
	 * answer the first outcome again.
	 */
	CheckinResult scan(CurrentUser user, Scan scan) {
		UUID schoolId = scan.schoolId();
		ClassRef ref = this.access.takeableClass(user, schoolId, scan.classId());
		this.access.requireActiveSchool(schoolId);
		this.access.requireMethod(schoolId, AttendanceSettings.METHOD_QR_STATIC);
		if (!this.keys.credentialsAvailable()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "attendance.secrets_unavailable");
		}
		this.limiter.consume("attendance-scan", user.userId().toString(), this.limits.scansPerMinute(), Duration.ofMinutes(1),
				"attendance.scan_rate_limited");
		Optional<CheckinResult> replay = replay(user, schoolId, scan.clientOpId());
		if (replay.isPresent()) {
			return replay.get();
		}
		AttendanceStatus status = (scan.status() != null) ? scan.status() : AttendanceStatus.PRESENT;
		if (status != AttendanceStatus.PRESENT && status != AttendanceStatus.LATE) {
			throw ApiException.badRequest("attendance.checkin_status_invalid");
		}
		if (scan.minutesLate() != null && (status != AttendanceStatus.LATE || scan.minutesLate() < 1 || scan.minutesLate() > 600)) {
			throw ApiException.badRequest("attendance.invalid_minutes_late");
		}
		String slot = (scan.slot() == null || scan.slot().isBlank()) ? "DAY" : scan.slot();
		ZoneId zone = this.time.zoneOf(ref);
		LocalDate date = (scan.date() != null) ? scan.date() : this.time.today(zone);
		Instant now = this.time.now();
		ScanCredential credential = this.credentials.find(scan.token()).filter((c) -> c.schoolId().equals(schoolId) && c.studentId() != null).orElse(null);
		if (credential == null) {
			return invalid(user, schoolId, "QR_STATIC", "INVALID", null);
		}
		this.credentials.noteRead(credential, user.userId(), now);
		Attempt attempt = Attempt.of(schoolId, "STUDENT", credential.studentId(), scan.classId(), date, slot, "QR_STATIC", user.userId(),
				credential.id(), null, now, scan.clientTs(), scan.clientOpId());
		if (credential.revoked()) {
			refused(user, schoolId, "QR_STATIC", "REVOKED", credential.id());
			return logged(attempt, CheckinResultCode.REJECTED_REVOKED, null, null, person(credential.studentId(), false), null);
		}
		if (credential.expired(now)) {
			refused(user, schoolId, "QR_STATIC", "EXPIRED", credential.id());
			this.ledger.append(attempt, CheckinResultCode.REJECTED_EXPIRED, null, null);
			return answer(CheckinResultCode.REJECTED_INVALID, null, null, null, null, null);
		}
		this.credentials.used(credential, scan.token(), now);
		CheckinOutcome outcome = this.rolls.markFromCheckin(user,
				new CheckinMark(attempt, ref, status, scan.minutesLate(), user.userId(), scan.subjectId(), scan.recordedAt()));
		boolean enrolled = outcome.result() != CheckinResultCode.REJECTED_NOT_ENROLLED;
		return answer(outcome.result(), outcome.accepted() ? status : null, outcome.accepted() ? scan.minutesLate() : null,
				outcome.checkinId(), person(credential.studentId(), enrolled), outcome.record());
	}

	/** A manager scans a staff member's card for today (staff attendance rules; online only, like all of staff attendance). */
	public CheckinResult scanStaff(CurrentUser user, UUID schoolId, StaffScanRequest request) {
		this.staff.requireManager(user, schoolId);
		this.access.requireActiveSchool(schoolId);
		this.access.requireMethod(schoolId, AttendanceSettings.METHOD_QR_STATIC);
		if (!this.keys.credentialsAvailable()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "attendance.secrets_unavailable");
		}
		this.limiter.consume("attendance-scan", user.userId().toString(), this.limits.scansPerMinute(), Duration.ofMinutes(1),
				"attendance.scan_rate_limited");
		Optional<CheckinResult> replay = replay(user, schoolId, request.clientOpId());
		if (replay.isPresent()) {
			return replay.get();
		}
		Instant now = this.time.now();
		ScanCredential credential = this.credentials.find(request.token())
			.filter((c) -> c.schoolId().equals(schoolId) && c.staffId() != null)
			.orElse(null);
		if (credential == null) {
			return invalid(user, schoolId, "QR_STATIC", "INVALID", null);
		}
		this.credentials.noteRead(credential, user.userId(), now);
		UUID campus = this.jdbc.sql("select campus_id from school_staff where id = :id").param("id", credential.staffId()).query(UUID.class).optional().orElse(null);
		LocalDate date = this.time.today(this.time.zoneOf(schoolId, campus));
		Attempt attempt = Attempt.of(schoolId, "STAFF", credential.staffId(), null, date, null, "QR_STATIC", user.userId(), credential.id(),
				null, now, null, request.clientOpId());
		if (credential.revoked()) {
			refused(user, schoolId, "QR_STATIC", "REVOKED", credential.id());
			return logged(attempt, CheckinResultCode.REJECTED_REVOKED, null, null, staffPerson(credential.staffId(), false), null);
		}
		if (credential.expired(now)) {
			refused(user, schoolId, "QR_STATIC", "EXPIRED", credential.id());
			this.ledger.append(attempt, CheckinResultCode.REJECTED_EXPIRED, null, null);
			return answer(CheckinResultCode.REJECTED_INVALID, null, null, null, null, null);
		}
		this.credentials.used(credential, request.token(), now);
		StaffCheckinOutcome outcome = this.staff.recordFromCheckin(new StaffCheckin(attempt, StaffAttendanceStatus.PRESENT, null));
		return answer(outcome.result(), outcome.accepted() ? AttendanceStatus.PRESENT : null, null, outcome.checkinId(),
				staffPerson(credential.staffId(), true), null);
	}

	// --- answers ----------------------------------------------------------------------------

	/** Appends the refusal to the ledger and answers it. */
	private CheckinResult logged(Attempt attempt, CheckinResultCode result, AttendanceStatus status, Integer minutesLate,
			ScannedPerson person, AttendanceRecordView current) {
		this.ledger.append(attempt, result, null, null);
		return answer(result, status, minutesLate, attempt.id(), person, current);
	}

	/** An attempt that names nobody: audited without any token, never a ledger row. */
	private CheckinResult invalid(CurrentUser user, UUID schoolId, String method, String reason, UUID credentialId) {
		refused(user, schoolId, method, reason, credentialId);
		return answer(CheckinResultCode.REJECTED_INVALID, null, null, null, null, null);
	}

	/** The audit row of a refused scan: the scanner (the actor), the school, the method and the reason; ids, never a token. */
	private void refused(CurrentUser user, UUID schoolId, String method, String reason, UUID credentialId) {
		AuditEntry entry = AuditEntry.of("attendance.scan.refused", AuditOutcome.DENIED)
			.school(schoolId)
			.metadata("method", method)
			.metadata("reason", reason);
		if (credentialId != null) {
			entry.target("attendance_credential", credentialId);
		}
		this.audit.record(entry);
	}

	/** The answer of a retry of a client operation the ledger already holds (only for the scanner who made it). */
	private Optional<CheckinResult> replay(CurrentUser user, UUID schoolId, UUID clientOpId) {
		Optional<Stored> stored = this.ledger.byClientOp(schoolId, clientOpId);
		if (stored.isEmpty()) {
			return Optional.empty();
		}
		Stored first = stored.get();
		if (!first.scannerUserId().equals(user.userId())) {
			throw ApiException.conflict("attendance.client_op_conflict");
		}
		boolean student = "STUDENT".equals(first.subjectType());
		boolean enrolled = first.result() != CheckinResultCode.REJECTED_NOT_ENROLLED;
		CheckinResultCode result = (first.result() == CheckinResultCode.REJECTED_EXPIRED) ? CheckinResultCode.REJECTED_INVALID : first.result();
		return Optional.of(answer(result, first.markedStatus(), first.minutesLate(), first.id(),
				(result == CheckinResultCode.REJECTED_INVALID) ? null
						: student ? person(first.personId(), enrolled) : staffPerson(first.personId(), true),
				null));
	}

	CheckinResult answer(CheckinResultCode result, AttendanceStatus status, Integer minutesLate, UUID checkinId, ScannedPerson person,
			AttendanceRecordView current) {
		String code = problemCode(result);
		String detail = (code == null) ? null
				: this.problems.create(HttpStatus.CONFLICT, code, new Object[0], LocaleContextHolder.getLocale()).getDetail();
		return new CheckinResult(result, status, minutesLate, checkinId, person, current, code, detail);
	}

	/** The stable error code of a refusal; null for ACCEPTED. */
	static String problemCode(CheckinResultCode result) {
		return switch (result) {
			case ACCEPTED -> null;
			case REJECTED_EXPIRED -> "attendance.checkin_code_expired";
			case REJECTED_NOT_ENROLLED -> "attendance.student_not_in_class";
			case REJECTED_DUPLICATE -> "attendance.checkin_duplicate";
			case REJECTED_REVOKED -> "attendance.credential_revoked";
			case REJECTED_WINDOW -> "attendance.checkin_window_closed";
			case REJECTED_CONFLICT -> "attendance.checkin_conflict";
			case REJECTED_INVALID -> "attendance.credential_invalid";
		};
	}

	// --- who was scanned --------------------------------------------------------------------

	/** The student's first name and avatar (never anything else); the id only when the student is on the class roll. */
	private ScannedPerson person(UUID studentId, boolean withId) {
		return scanned(studentId, withId ? studentId : null);
	}

	private ScannedPerson staffPerson(UUID staffId, boolean withId) {
		UUID userId = this.jdbc.sql("select user_id from school_staff where id = :id").param("id", staffId).query(UUID.class).optional().orElse(null);
		return (userId == null) ? null : scanned(userId, withId ? staffId : null);
	}

	private ScannedPerson scanned(UUID userId, UUID personId) {
		String firstName = this.jdbc.sql("select coalesce(given_name, display_name, '') from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse("");
		PersonCard card = this.access.people().cards(List.of(userId)).get(userId);
		return new ScannedPerson(personId, firstName, (card != null) ? card.avatar() : null, (card != null) ? card.avatarConfig() : null);
	}

}
