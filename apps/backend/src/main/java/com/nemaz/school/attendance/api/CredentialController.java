package com.nemaz.school.attendance.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.attendance.application.CheckinModels.AttendanceCredentialView;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheet;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheetRequest;
import com.nemaz.school.attendance.application.CheckinModels.IssueCredentialRequest;
import com.nemaz.school.attendance.application.CheckinModels.IssuedAttendanceCredential;
import com.nemaz.school.attendance.application.CheckinModels.ReissueCredentialRequest;
import com.nemaz.school.attendance.application.CheckinModels.RevokeCredentialRequest;
import com.nemaz.school.attendance.application.CredentialService;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The QR credentials (cards) of attendance (M7e). The QR payload of a new credential is in the answer of the issue only, with
 * {@code Cache-Control: no-store}; these calls are deliberately not {@code @Idempotent} (the idempotency store would keep the
 * answer, token included, for 24 hours): never send an {@code Idempotency-Key}. See {@link CredentialService}.
 */
@RestController
@Tag(name = "attendance-credentials")
public class CredentialController {

	private static final String SCHOOL = "/api/v1/schools/{schoolId}";

	private final CredentialService credentials;

	CredentialController(CredentialService credentials) {
		this.credentials = credentials;
	}

	// --- students ---------------------------------------------------------------------------

	@PostMapping(SCHOOL + "/students/{studentId}/attendance-credentials")
	@Operation(operationId = "issueStudentAttendanceCredential",
			summary = "Issues the student's attendance credential (the QR of a card; 128 random bits, never the login code, never personal data). qrPayload is the bare token: it is in this answer ONLY (Cache-Control: no-store; only its keyed hash is stored) and can never be read again. 409 attendance.credential_exists while the student has a live credential (reissue instead), 409 attendance.credential_person_not_active for a student who is not ACTIVE, 400 attendance.credential_invalid_ttl. school.attendance.credentials.manage, or the LEAD teacher / class manager of a class the student is in; 403 for everybody else (before anything is said about the student), 404 for people who cannot see the school. Audited without the token")
	public ResponseEntity<IssuedAttendanceCredential> issueStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody(required = false) IssueCredentialRequest request) {
		IssuedAttendanceCredential issued = this.credentials.issueForStudent(CurrentUser.require(), schoolId, studentId,
				(request != null) ? request.ttlDays() : null);
		return noStore(HttpStatus.CREATED, issued);
	}

	@PostMapping(SCHOOL + "/students/{studentId}/attendance-credentials/reissue")
	@Operation(operationId = "reissueStudentAttendanceCredential",
			summary = "Replaces the student's live credential: it is revoked with the reason (LOST, REPLACED by default, SUSPECTED_COPY, OTHER) and a new one is issued in the same transaction (qrPayload once, no-store). Same access as issuing")
	public ResponseEntity<IssuedAttendanceCredential> reissueStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody(required = false) ReissueCredentialRequest request) {
		IssuedAttendanceCredential issued = this.credentials.reissueForStudent(CurrentUser.require(), schoolId, studentId,
				(request != null) ? request.reason() : null, (request != null) ? request.ttlDays() : null);
		return noStore(HttpStatus.CREATED, issued);
	}

	@PostMapping(SCHOOL + "/students/{studentId}/attendance-credentials/revoke")
	@Operation(operationId = "revokeStudentAttendanceCredential",
			summary = "Revokes the student's live credential with a reason (LOST, REPLACED, LEFT_SCHOOL, SUSPECTED_COPY, OTHER): its next scan is refused (REJECTED_REVOKED) and logged with the scanner. 404 attendance.credential_not_found when there is none. Same access as issuing")
	public AttendanceCredentialView revokeStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody RevokeCredentialRequest request) {
		return this.credentials.revokeForStudent(CurrentUser.require(), schoolId, studentId, request.reason());
	}

	@GetMapping(SCHOOL + "/students/{studentId}/attendance-credentials")
	@Operation(operationId = "listStudentAttendanceCredentials",
			summary = "The student's credentials, newest first (at most 20): when issued, by whom, expiry, revocation, last use and the SUSPECTED_COPY flag. Never the QR. Same access as issuing; guardians and students never see them")
	public List<AttendanceCredentialView> listStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.credentials.ofStudent(CurrentUser.require(), schoolId, studentId);
	}

	// --- staff ------------------------------------------------------------------------------

	@PostMapping(SCHOOL + "/staff/{staffId}/attendance-credentials")
	@Operation(operationId = "issueStaffAttendanceCredential",
			summary = "Issues an attendance credential for a staff member (school_staff id): same rules and answer as for a student (qrPayload once, no-store). 409 staff_attendance.staff_not_active for staff who are not ACTIVE. school.attendance.credentials.manage only")
	public ResponseEntity<IssuedAttendanceCredential> issueStaff(@PathVariable UUID schoolId, @PathVariable UUID staffId,
			@Valid @RequestBody(required = false) IssueCredentialRequest request) {
		IssuedAttendanceCredential issued = this.credentials.issueForStaff(CurrentUser.require(), schoolId, staffId,
				(request != null) ? request.ttlDays() : null);
		return noStore(HttpStatus.CREATED, issued);
	}

	@PostMapping(SCHOOL + "/staff/{staffId}/attendance-credentials/reissue")
	@Operation(operationId = "reissueStaffAttendanceCredential",
			summary = "Replaces a staff member's live credential (see the student call). school.attendance.credentials.manage only")
	public ResponseEntity<IssuedAttendanceCredential> reissueStaff(@PathVariable UUID schoolId, @PathVariable UUID staffId,
			@Valid @RequestBody(required = false) ReissueCredentialRequest request) {
		IssuedAttendanceCredential issued = this.credentials.reissueForStaff(CurrentUser.require(), schoolId, staffId,
				(request != null) ? request.reason() : null, (request != null) ? request.ttlDays() : null);
		return noStore(HttpStatus.CREATED, issued);
	}

	@PostMapping(SCHOOL + "/staff/{staffId}/attendance-credentials/revoke")
	@Operation(operationId = "revokeStaffAttendanceCredential",
			summary = "Revokes a staff member's live credential with a reason. school.attendance.credentials.manage only")
	public AttendanceCredentialView revokeStaff(@PathVariable UUID schoolId, @PathVariable UUID staffId,
			@Valid @RequestBody RevokeCredentialRequest request) {
		return this.credentials.revokeForStaff(CurrentUser.require(), schoolId, staffId, request.reason());
	}

	@GetMapping(SCHOOL + "/staff/{staffId}/attendance-credentials")
	@Operation(operationId = "listStaffAttendanceCredentials",
			summary = "A staff member's credentials, newest first (at most 20), never the QR. school.attendance.credentials.manage only")
	public List<AttendanceCredentialView> listStaff(@PathVariable UUID schoolId, @PathVariable UUID staffId) {
		return this.credentials.ofStaff(CurrentUser.require(), schoolId, staffId);
	}

	// --- the class sheet and the school's list ----------------------------------------------

	@PostMapping(SCHOOL + "/classes/{classId}/attendance-credentials")
	@Operation(operationId = "issueClassAttendanceCredentials",
			summary = "Credentials for the cards of a class (printing is the web's job): every ACTIVE student of the class (studentIds narrows it) without a live credential gets one; with replaceExisting the others get a new one and the old is revoked as REPLACED; without it they are listed as skipped (ALREADY_ISSUED). Every qrPayload is in this answer ONLY (Cache-Control: no-store). 409 attendance.student_not_in_class for a listed student who is not ACTIVE in the class. The class's LEAD teacher, class managers and school.attendance.credentials.manage; 403 for the class's other teachers, 404 for people who cannot see the class")
	public ResponseEntity<CredentialSheet> sheet(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody(required = false) CredentialSheetRequest request) {
		CredentialSheet sheet = this.credentials.sheet(CurrentUser.require(), schoolId, classId,
				(request != null) ? request : new CredentialSheetRequest(null, null, null));
		return noStore(HttpStatus.OK, sheet);
	}

	@GetMapping(SCHOOL + "/attendance/credentials")
	@Operation(operationId = "listAttendanceCredentials",
			summary = "The school's credentials, newest first, keyset paged: kind (STUDENT, STAFF), state (LIVE, EXPIRED, REVOKED) and suspectedCopy=true (live credentials that two scanners read within a minute: the SUSPECTED_COPY alert; revoke them with the reason SUSPECTED_COPY). Never the QR. school.attendance.credentials.manage only")
	public CursorPage<AttendanceCredentialView> list(@PathVariable UUID schoolId, @RequestParam(required = false) String kind,
			@RequestParam(required = false) String state, @RequestParam(required = false) Boolean suspectedCopy,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.credentials.list(CurrentUser.require(), schoolId, kind, state, suspectedCopy, cursor, limit);
	}

	private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
	}

}
