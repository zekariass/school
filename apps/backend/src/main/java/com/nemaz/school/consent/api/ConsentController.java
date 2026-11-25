package com.nemaz.school.consent.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.consent.application.ConsentRecordService;
import com.nemaz.school.consent.application.ConsentRecordService.Decision;
import com.nemaz.school.consent.application.ConsentRecordService.Method;
import com.nemaz.school.consent.application.ConsentRecordService.MyConsents;
import com.nemaz.school.consent.application.ConsentRecordService.MyDecision;
import com.nemaz.school.consent.application.ConsentRecordService.RecordedConsent;
import com.nemaz.school.consent.application.ConsentRecordService.StaffDecision;
import com.nemaz.school.consent.application.ConsentRecordService.StudentConsentSummary;
import com.nemaz.school.consent.application.ConsentRecordService.StudentConsents;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consent decisions: a guardian's for their children and an adult's own (TERMS, PRIVACY), decisions the school
 * received on paper, and the class roster's consent column. Records are append-only: a change of mind is a new record
 * (WITHDRAWN or DENIED), never an edit.
 */
@RestController
@Tag(name = "consent")
public class ConsentController {

	private final ConsentRecordService consents;

	ConsentController(ConsentRecordService consents) {
		this.consents = consents;
	}

	@GetMapping("/api/v1/me/consents")
	@Operation(operationId = "listMyConsents",
			summary = "For each child I may give consent for (ACTIVE link, not restricted, can_give_consent) and school: ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION with the current text, my latest decision and whether a new decision is needed; plus my own TERMS and PRIVACY")
	public MyConsents mine() {
		return this.consents.mine(CurrentUser.require());
	}

	@PostMapping("/api/v1/me/consents")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "recordMyConsent",
			summary = "Record a decision on the current policy version: for a child (studentId; schoolId when the child is in several schools) ACCOUNT_PARTICIPATION or MEDIA_PUBLICATION, or for myself (no studentId) TERMS or PRIVACY. 404 consent.child_not_found for children I may not decide for, 409 consent.version_outdated for any version but the current one")
	public RecordedConsent record(@Valid @RequestBody MyConsentRequest r) {
		return this.consents.decide(CurrentUser.require(),
				new MyDecision(r.studentId(), r.schoolId(), r.consentType(), r.policyVersionId(), r.decision()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/consents")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "recordStudentConsent",
			summary = "Record a decision the school received on paper or verbally (school.guardians.manage): from a guardian link that may give consent (409 consent.guardian_not_eligible otherwise) or from a named guardian without an account")
	public RecordedConsent recordForStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody StaffConsentRequest r) {
		return this.consents.recordForStudent(CurrentUser.require(), schoolId, studentId, new StaffDecision(
				r.consentType(), r.policyVersionId(), r.decision(), r.guardianLinkId(), r.guardianName(), r.method()));
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/consents")
	@Operation(operationId = "listStudentConsents",
			summary = "The child's effective statuses and every consent record, newest first (school.guardians.manage)")
	public StudentConsents forStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.consents.forStudent(CurrentUser.require(), schoolId, studentId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/consent-summary")
	@Operation(operationId = "getClassConsentSummary",
			summary = "The roster's consent column (teachers of the class, class managers, school.guardians.manage): per ACTIVE student the effective ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION status, without guardian identities. Treat MEDIA_PUBLICATION UNKNOWN as not allowed")
	public List<StudentConsentSummary> classSummary(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.consents.classSummary(CurrentUser.require(), schoolId, classId);
	}

	public record MyConsentRequest(UUID studentId, UUID schoolId,
			@NotNull(message = "validation.required") ConsentType consentType,
			@NotNull(message = "validation.required") UUID policyVersionId,
			@NotNull(message = "validation.required") Decision decision) {
	}

	@Schema(description = "Exactly one of guardianLinkId or guardianName. Evidence uploads (scanned forms) are not supported yet.")
	public record StaffConsentRequest(@NotNull(message = "validation.required") ConsentType consentType,
			@NotNull(message = "validation.required") UUID policyVersionId,
			@NotNull(message = "validation.required") Decision decision, UUID guardianLinkId,
			@Size(max = 200, message = "validation.tooLong") String guardianName,
			@NotNull(message = "validation.required") @Schema(description = "PAPER_RECORDED_BY_SCHOOL or VERBAL_RECORDED_BY_SCHOOL") Method method) {

		@Override
		public String toString() {
			return "StaffConsentRequest[redacted]";
		}

	}

}
