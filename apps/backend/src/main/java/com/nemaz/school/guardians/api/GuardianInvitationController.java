package com.nemaz.school.guardians.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.guardians.application.InvitationService;
import com.nemaz.school.guardians.application.InvitationService.InvitationPreview;
import com.nemaz.school.guardians.application.InvitationService.InvitationView;
import com.nemaz.school.guardians.application.InvitationService.IssuedParentCode;
import com.nemaz.school.guardians.application.InvitationService.NewInvitation;
import com.nemaz.school.identity.InvitationRedeemer.InvitationKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inviting guardians: SMS or email invitations per student, printed parent codes per class, revoking, and the public
 * preview of an invitation. Who may invite: {@code school.guardians.manage}, or teachers (not assistants) of the
 * student's classes while {@code guardians.teachers_can_invite} is on.
 */
@RestController
@Tag(name = "guardian-invitations")
public class GuardianInvitationController {

	private final InvitationService invitations;

	GuardianInvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/guardian-invitations")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "inviteGuardian",
			summary = "Invite an adult by SMS or email to link to the student (single use, 14 days, sent in the school's language). The phone or email is not proof of guardianship: accepting creates a PENDING link. At most 10 live invitations per student (409 invitation.limit_reached)")
	public InvitationView invite(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody InviteGuardianRequest r) {
		return this.invitations.invite(CurrentUser.require(), schoolId, studentId,
				new NewInvitation(r.phone(), r.email(), r.country(), r.relationshipType(), r.guardianName()));
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/guardian-invitations")
	@Operation(operationId = "listGuardianInvitations",
			summary = "The student's guardian invitations, newest first (destinations masked; never tokens or codes)")
	public List<InvitationView> list(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.invitations.forStudent(CurrentUser.require(), schoolId, studentId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/guardian-invitations/{invitationId}/revoke")
	@Operation(operationId = "revokeGuardianInvitation",
			summary = "Revoke an invitation or a printed parent code; links already made stay as they are")
	public InvitationView revoke(@PathVariable UUID schoolId, @PathVariable UUID invitationId) {
		return this.invitations.revoke(CurrentUser.require(), schoolId, invitationId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/parent-codes")
	@Operation(operationId = "issueParentCodes",
			summary = "Print parent codes (home connection) for students of the class (default: all ACTIVE students): one code per student for up to guardians.parent_code.max_uses adults within guardians.parent_code.ttl_days; the student's previous live code is revoked. Links made with a code are always PENDING. The codes are in this response only (Cache-Control: no-store); do not send an Idempotency-Key")
	public ResponseEntity<List<IssuedParentCode>> issueCodes(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody(required = false) IssueParentCodesRequest r) {
		List<IssuedParentCode> codes = this.invitations.issueCodes(CurrentUser.require(), schoolId, classId,
				(r != null) ? r.studentIds() : null);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(codes);
	}

	@PostMapping("/api/v1/invitations/preview")
	@Operation(operationId = "previewInvitation",
			summary = "What a live invitation is about, before signing in: school, the child's first name, relationship, channel. Unknown, expired, revoked and used-up invitations are all 404 invitation.not_found; guessing is rate limited (429)")
	public InvitationPreview preview(@Valid @RequestBody InvitationKeyRequest r) {
		return this.invitations.preview(key(r.token(), r.code()));
	}

	/** Exactly one of token or code. */
	static InvitationKey key(String token, String code) {
		boolean hasToken = token != null && !token.isBlank();
		boolean hasCode = code != null && !code.isBlank();
		if (hasToken == hasCode) {
			throw ApiException.badRequest("invitation.key_required");
		}
		return new InvitationKey(hasToken ? token.trim() : null, hasCode ? code.trim() : null);
	}

	@Schema(description = "Exactly one of phone or email. National phone formats are read in `country` (default: the school's country).")
	public record InviteGuardianRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "validation.invalid") String country,
			@NotNull(message = "validation.required") RelationshipType relationshipType,
			@Size(max = 100, message = "validation.tooLong") @Schema(description = "A hint for verifiers, not proof") String guardianName) {

		@Override
		public String toString() {
			return "InviteGuardianRequest[redacted]";
		}

	}

	public record IssueParentCodesRequest(
			@Size(max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds) {
	}

	@Schema(description = "Exactly one of token (from the invitation link) or code (printed parent code, any case, hyphen optional).")
	public record InvitationKeyRequest(@Size(max = 128, message = "validation.tooLong") String token,
			@Size(max = 32, message = "validation.tooLong") String code) {

		@Override
		public String toString() {
			return "InvitationKeyRequest[redacted]";
		}

	}

}
