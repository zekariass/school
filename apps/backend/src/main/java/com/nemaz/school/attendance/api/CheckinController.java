package com.nemaz.school.attendance.api;

import java.util.UUID;

import com.nemaz.school.attendance.application.CheckinModels.CheckinMethods;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResult;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResultCode;
import com.nemaz.school.attendance.application.CheckinModels.OpenClassQrSessionRequest;
import com.nemaz.school.attendance.application.CheckinModels.OpenStaffQrSessionRequest;
import com.nemaz.school.attendance.application.CheckinModels.QrCheckinRequest;
import com.nemaz.school.attendance.application.CheckinModels.QrCodes;
import com.nemaz.school.attendance.application.CheckinModels.QrSessionDetail;
import com.nemaz.school.attendance.application.CheckinModels.QrSessionView;
import com.nemaz.school.attendance.application.CheckinModels.StaffScanRequest;
import com.nemaz.school.attendance.application.CheckinModels.StaticScanRequest;
import com.nemaz.school.attendance.application.CheckinService;
import com.nemaz.school.attendance.application.QrSessionService;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The QR check-in (M7e): rotating codes (a teacher or manager shows a code, the person scans it with their own app) and static
 * cards (a teacher or manager scans the card). The answer of every check-in is a {@code CheckinResult}: 200 when ACCEPTED, 409
 * with the same body for every refusal. <b>No endpoint here accepts, validates or stores a location</b> (D38); unknown JSON
 * members are ignored. See {@link CheckinService} and {@link QrSessionService}.
 */
@RestController
@Tag(name = "attendance-checkin")
public class CheckinController {

	private static final String SCHOOL = "/api/v1/schools/{schoolId}";

	private final CheckinService checkins;

	private final QrSessionService sessions;

	CheckinController(CheckinService checkins, QrSessionService sessions) {
		this.checkins = checkins;
		this.sessions = sessions;
	}

	@GetMapping(SCHOOL + "/attendance/methods")
	@Operation(operationId = "getAttendanceCheckinMethods",
			summary = "How attendance is taken at the school: the QR methods that are on (QR_ROTATING, QR_STATIC; manual marking is always available), the rotation of the code and the staff late threshold. Any member who can see the school; 403 attendance.feature_disabled when attendance is off")
	public CheckinMethods methods(@PathVariable UUID schoolId) {
		return this.checkins.methods(CurrentUser.require(), schoolId);
	}

	// --- rotating codes: running a session --------------------------------------------------

	@PostMapping(SCHOOL + "/classes/{classId}/attendance/qr-sessions")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "openClassAttendanceQrSession",
			summary = "Opens a rotating code for today's roll (school-local date) of the class and a slot: the code changes every attendance.qr.rotation_seconds and lives durationMinutes (default 15, at most 120); lateAfterMinutes makes later check-ins LATE. Opening one closes the class's other open codes for that roll. Needs the school ACTIVE (403 attendance.school_not_active), the method QR_ROTATING on (403 attendance.method_disabled), a roll that has not locked (409 attendance.session_locked), 400 for a slot the school does not use, a bad subject or duration. The people who may take the roll (the class's teachers, class managers, school.attendance.manage); audited")
	public QrSessionView openClass(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody OpenClassQrSessionRequest request) {
		return this.sessions.openForClass(CurrentUser.require(), schoolId, classId, request);
	}

	@PostMapping(SCHOOL + "/staff-attendance/qr-sessions")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "openStaffAttendanceQrSession",
			summary = "Opens a rotating code for today's staff of a campus (campusId; omit for the whole school): durationMinutes default 60 (at most 480), lateAfterMinutes default the school's attendance.staff.late_after_minutes (0: nobody late). Staff scan it with their own app. school.staff_attendance.manage, staff attendance on, the school ACTIVE, the method QR_ROTATING on")
	public QrSessionView openStaff(@PathVariable UUID schoolId, @Valid @RequestBody OpenStaffQrSessionRequest request) {
		return this.sessions.openForStaff(CurrentUser.require(), schoolId, request);
	}

	@GetMapping(SCHOOL + "/attendance/qr-sessions/{sessionId}")
	@Operation(operationId = "getAttendanceQrSession",
			summary = "A rotating code session with its live list (who checked in, oldest first; removed entries are marked). The people who run it: those who may take the roll, or the staff attendance managers for a staff code; 404 for anybody else")
	public QrSessionDetail session(@PathVariable UUID schoolId, @PathVariable UUID sessionId) {
		return this.sessions.detail(CurrentUser.require(), schoolId, sessionId);
	}

	@GetMapping(SCHOOL + "/attendance/qr-sessions/{sessionId}/codes")
	@Operation(operationId = "getAttendanceQrSessionCodes",
			summary = "The codes of the current window and of the windows ahead (about ten minutes, never past the end of the session), so the teacher's display keeps rotating without a connection; each carries the payload to show as a QR (the session id, a dot and the code). A code is accepted in its own window and the next. Never cached (Cache-Control: no-store). 409 attendance.qr_session_closed once the session was closed or ran out. The people who run the session")
	public ResponseEntity<QrCodes> codes(@PathVariable UUID schoolId, @PathVariable UUID sessionId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
			.body(this.sessions.codes(CurrentUser.require(), schoolId, sessionId));
	}

	@PostMapping(SCHOOL + "/attendance/qr-sessions/{sessionId}/close")
	@Operation(operationId = "closeAttendanceQrSession",
			summary = "Closes the session (idempotent): its codes stop working at once. The people who run it; audited")
	public QrSessionView close(@PathVariable UUID schoolId, @PathVariable UUID sessionId) {
		return this.sessions.close(CurrentUser.require(), schoolId, sessionId);
	}

	@DeleteMapping(SCHOOL + "/attendance/qr-sessions/{sessionId}/check-ins/{checkinId}")
	@Operation(operationId = "removeAttendanceQrCheckin",
			summary = "Takes one accepted check-in off the roll (or the staff day) while nobody has changed its mark since (409 attendance.checkin_changed otherwise); the removal is appended and audited and the person cannot check in again to this session (mark them by hand). 404 attendance.checkin_not_found for an entry that is not an accepted one of this session or was already removed. The people who run the session")
	public QrSessionDetail removeCheckin(@PathVariable UUID schoolId, @PathVariable UUID sessionId, @PathVariable UUID checkinId) {
		return this.sessions.removeEntry(CurrentUser.require(), schoolId, sessionId, checkinId);
	}

	// --- rotating codes: the person checks in -----------------------------------------------

	@PostMapping(SCHOOL + "/attendance/qr-sessions/{sessionId}/check-ins")
	@Operation(operationId = "checkInToAttendanceQrSession",
			summary = "A student's (class code) or staff member's (staff code) own check-in with the code shown on the teacher's or manager's screen (the part after the dot of the payload). The caller is the authenticated person, never anonymous: an ACTIVE student of the school for a class code, an ACTIVE staff member for a staff code (403 attendance.checkin_not_allowed for anybody else, a guardian included). 200 with result ACCEPTED (PRESENT, or LATE after the session's threshold); 409 with the same body when it was refused and logged: REJECTED_EXPIRED (wrong, replayed or older than the previous window), REJECTED_NOT_ENROLLED (not in the class on that date), REJECTED_WINDOW (session closed, not open yet, another day, or the roll locked), REJECTED_DUPLICATE (one accepted check-in per person per session), REJECTED_CONFLICT (the roll already holds a different mark: nothing is overwritten). 404 for a session of another school or an unknown one; 429 attendance.checkin_rate_limited (per person and per session). The school must be ACTIVE and the method on")
	public ResponseEntity<CheckinResult> checkIn(@PathVariable UUID schoolId, @PathVariable UUID sessionId,
			@Valid @RequestBody QrCheckinRequest request) {
		return answer(this.checkins.checkIn(CurrentUser.require(), schoolId, sessionId, request));
	}

	// --- static cards: the teacher or manager scans -----------------------------------------

	@PostMapping(SCHOOL + "/classes/{classId}/attendance/scans")
	@Operation(operationId = "scanAttendanceCredential",
			summary = "A teacher scans a student's card (the bare token of its QR) for a class roll: PRESENT or LATE only (status), for the school-local date (default today) and slot, through the roll's own rules. 200 ACCEPTED; 409 with the same body when refused: REJECTED_REVOKED (a card of this school that was revoked: logged with the scanner), REJECTED_NOT_ENROLLED, REJECTED_WINDOW (date not started or roll locked: a scan has no override), REJECTED_DUPLICATE, REJECTED_CONFLICT (a different mark is stored; current shows it, nothing was overwritten), REJECTED_INVALID (unknown, malformed, expired, another school's or a staff card: one answer for all, nothing about the card is said). The answer names the scanned student by first name and avatar only. clientOpId makes a retry answer the first outcome. Offline scans use the sync operation ATTENDANCE_CHECKIN. The people who may take the roll; 429 attendance.scan_rate_limited")
	public ResponseEntity<CheckinResult> scan(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody StaticScanRequest request) {
		return answer(this.checkins.scan(CurrentUser.require(), schoolId, classId, request));
	}

	@PostMapping(SCHOOL + "/staff-attendance/scans")
	@Operation(operationId = "scanStaffAttendanceCredential",
			summary = "A manager scans a staff member's card for today (staff attendance rules: one record per day, never overwritten, PRESENT only). Same answers as the student scan; first name and avatar only. Online only. school.staff_attendance.manage, staff attendance on, the school ACTIVE, the method QR_STATIC on")
	public ResponseEntity<CheckinResult> scanStaff(@PathVariable UUID schoolId, @Valid @RequestBody StaffScanRequest request) {
		return answer(this.checkins.scanStaff(CurrentUser.require(), schoolId, request));
	}

	private static ResponseEntity<CheckinResult> answer(CheckinResult result) {
		return ResponseEntity.status((result.result() == CheckinResultCode.ACCEPTED) ? HttpStatus.OK : HttpStatus.CONFLICT)
			.cacheControl(CacheControl.noStore())
			.body(result);
	}

}
