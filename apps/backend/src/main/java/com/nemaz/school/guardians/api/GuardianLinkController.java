package com.nemaz.school.guardians.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.guardians.application.GuardianLinkService;
import com.nemaz.school.guardians.application.GuardianLinkService.GuardianLinkView;
import com.nemaz.school.guardians.application.GuardianLinkService.LinkChange;
import com.nemaz.school.guardians.application.GuardianLinkService.LinkStatus;
import com.nemaz.school.guardians.application.GuardianLinkService.NewGuardian;
import com.nemaz.school.guardians.application.GuardianLinkService.PermissionChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifying and managing guardian links of a school's students. Verifiers: {@code school.guardians.manage}, or the
 * LEAD teacher of one of the student's classes while {@code guardians.teachers_can_verify} is on. Managing (flags,
 * custody restriction, primary, revoke, links created by the school): {@code school.guardians.manage}.
 */
@RestController
@Tag(name = "guardian-links")
public class GuardianLinkController {

	private final GuardianLinkService links;

	GuardianLinkController(GuardianLinkService links) {
		this.links = links;
	}

	@GetMapping("/api/v1/schools/{schoolId}/guardian-links")
	@Operation(operationId = "listGuardianLinks",
			summary = "The verification queue: links of the school's ACTIVE students in one status (default PENDING), oldest first. Administrators see all, verifying lead teachers the students of their classes")
	public CursorPage<GuardianLinkView> queue(@PathVariable UUID schoolId,
			@RequestParam(required = false) LinkStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.links.queue(CurrentUser.require(), schoolId, status, cursor, limit);
	}

	@PostMapping("/api/v1/schools/{schoolId}/guardian-links/{linkId}/approve")
	@Operation(operationId = "approveGuardianLink",
			summary = "Verify a PENDING link (SCHOOL_VERIFIED): it becomes ACTIVE and the guardian is notified. 409 guardian_link.not_pending otherwise")
	public GuardianLinkView approve(@PathVariable UUID schoolId, @PathVariable UUID linkId,
			@Valid @RequestBody(required = false) DecisionRequest r) {
		return this.links.approve(CurrentUser.require(), schoolId, linkId, (r != null) ? r.note() : null);
	}

	@PostMapping("/api/v1/schools/{schoolId}/guardian-links/{linkId}/reject")
	@Operation(operationId = "rejectGuardianLink", summary = "Reject a PENDING link; the adult is notified")
	public GuardianLinkView reject(@PathVariable UUID schoolId, @PathVariable UUID linkId,
			@Valid @RequestBody(required = false) DecisionRequest r) {
		return this.links.reject(CurrentUser.require(), schoolId, linkId, (r != null) ? r.note() : null);
	}

	@PatchMapping("/api/v1/schools/{schoolId}/guardian-links/{linkId}")
	@Operation(operationId = "updateGuardianLink",
			summary = "Change a PENDING or ACTIVE link: permission flags, relationship, primary guardian (one per child), custody restriction with an admin-only note (a restricted link grants nothing)")
	public GuardianLinkView update(@PathVariable UUID schoolId, @PathVariable UUID linkId,
			@Valid @RequestBody UpdateLinkRequest r) {
		return this.links.update(CurrentUser.require(), schoolId, linkId, new LinkChange(r.relationshipType(), r.primary(),
				r.permissions(), r.custodyRestricted(), r.restrictionNote()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/guardian-links/{linkId}/revoke")
	@Operation(operationId = "revokeGuardianLink",
			summary = "End a link: access stops with the guardian's next request")
	public GuardianLinkView revoke(@PathVariable UUID schoolId, @PathVariable UUID linkId) {
		return this.links.revoke(CurrentUser.require(), schoolId, linkId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/guardians")
	@Operation(operationId = "listStudentGuardians",
			summary = "The student's guardian links. Administrators (school.guardians.manage) see every status with masked contact details and notes; teachers of the student see PENDING and ACTIVE links with names, relationship and status only")
	public List<GuardianLinkView> forStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.links.forStudent(CurrentUser.require(), schoolId, studentId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/guardians")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createStudentGuardian",
			summary = "Link an adult the school knows (the administrator vouches): found by phone or email or created, the link is ACTIVE (SCHOOL_VERIFIED)")
	public GuardianLinkView create(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody CreateGuardianRequest r) {
		return this.links.create(CurrentUser.require(), schoolId, studentId,
				new NewGuardian(r.phone(), r.email(), r.country(), r.name(), r.relationshipType()));
	}

	public record DecisionRequest(
			@Size(max = 500, message = "validation.tooLong") @Schema(description = "Internal to the school") String note) {

		@Override
		public String toString() {
			return "DecisionRequest[redacted]";
		}

	}

	public record UpdateLinkRequest(RelationshipType relationshipType, Boolean primary, PermissionChange permissions,
			Boolean custodyRestricted,
			@Size(max = 1000, message = "validation.tooLong") @Schema(description = "Visible to school.guardians.manage only; never audited") String restrictionNote) {

		@Override
		public String toString() {
			return "UpdateLinkRequest[redacted]";
		}

	}

	@Schema(description = "Exactly one of phone or email. National phone formats are read in `country` (default: the school's country).")
	public record CreateGuardianRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "validation.invalid") String country,
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotNull(message = "validation.required") RelationshipType relationshipType) {

		@Override
		public String toString() {
			return "CreateGuardianRequest[redacted]";
		}

	}

}
