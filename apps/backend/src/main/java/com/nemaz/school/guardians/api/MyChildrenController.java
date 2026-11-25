package com.nemaz.school.guardians.api;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.guardians.application.GuardianLinkService.GuardianLinkView;
import com.nemaz.school.guardians.application.InvitationService;
import com.nemaz.school.guardians.application.MyChildrenService;
import com.nemaz.school.guardians.application.MyChildrenService.MyChildren;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in adult's children and redeeming an invitation while signed in. */
@RestController
@Tag(name = "guardians")
public class MyChildrenController {

	private final MyChildrenService children;

	private final InvitationService invitations;

	MyChildrenController(MyChildrenService children, InvitationService invitations) {
		this.children = children;
		this.invitations = invitations;
	}

	@GetMapping("/api/v1/me/children")
	@Operation(operationId = "listMyChildren",
			summary = "My children through ACTIVE links that are not custody-restricted (record, schools, classes and teachers when the link allows academics); PENDING requests listed apart with the child's first name only")
	public MyChildren list() {
		return this.children.list(CurrentUser.require());
	}

	@PostMapping("/api/v1/me/guardian-links")
	@Operation(operationId = "redeemGuardianInvitation",
			summary = "Redeem an invitation link token or printed parent code as the signed-in adult (STANDARD account with a verified phone or email, which is the proof). Creates a PENDING link (or returns the existing one); unknown, expired or used-up invitations are 404 invitation.not_found")
	public GuardianLinkView redeem(@Valid @RequestBody RedeemRequest r) {
		return this.invitations.redeemSignedIn(CurrentUser.require(), GuardianInvitationController.key(r.token(), r.code()),
				r.relationshipType());
	}

	@Schema(description = "Exactly one of token or code. relationshipType is used when the invitation names none (printed codes).")
	public record RedeemRequest(@Size(max = 128, message = "validation.tooLong") String token,
			@Size(max = 32, message = "validation.tooLong") String code, RelationshipType relationshipType) {

		@Override
		public String toString() {
			return "RedeemRequest[redacted]";
		}

	}

}
