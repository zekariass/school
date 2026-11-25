package com.nemaz.school.identity.api;

import java.util.Optional;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.identity.InvitationRedeemer.InvitationKey;
import com.nemaz.school.identity.api.AuthController.OtpRequestAccepted;
import com.nemaz.school.identity.api.AuthController.TokenResponse;
import com.nemaz.school.identity.application.Destination;
import com.nemaz.school.identity.application.Destination.Channel;
import com.nemaz.school.identity.application.Emails;
import com.nemaz.school.identity.application.IdentityProperties;
import com.nemaz.school.identity.application.InvitationSignInService;
import com.nemaz.school.identity.application.PhoneNumbers;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepting a guardian invitation (SMS/email link token or printed parent code) as a new or existing adult: prove a
 * phone or email with a one-time code, then accept and get signed in. Public and rate-limited; the answers never
 * reveal whether an account exists. The preview is {@code POST /invitations/preview} (guardians module).
 */
@RestController
@RequestMapping("/api/v1/invitations")
@Tag(name = "invitations")
public class InvitationAcceptController {

	private static final String COUNTRY = "^[A-Za-z]{2}$";

	private final InvitationSignInService invitations;

	private final IdentityProperties props;

	InvitationAcceptController(InvitationSignInService invitations, IdentityProperties props) {
		this.invitations = invitations;
		this.props = props;
	}

	@PostMapping("/otp")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(operationId = "requestInvitationOtp",
			summary = "Send a one-time code to the phone or email of the adult accepting a live invitation (even when no account exists; 3 per destination and 20 per address per hour). 404 invitation.not_found for unknown, expired, revoked or used-up invitations")
	public OtpRequestAccepted requestOtp(@Valid @RequestBody InvitationOtpRequest r) {
		Destination destination = destination(r.phone(), r.email(), r.country())
			.orElseThrow(() -> ApiException.badRequest("auth.invalid_destination"));
		this.invitations.requestCode(key(r.token(), r.code()), destination);
		return new OtpRequestAccepted(6, this.props.otp().ttl().toSeconds());
	}

	@PostMapping("/accept")
	@Operation(operationId = "acceptInvitation",
			summary = "Accept a live invitation with the one-time code: finds or creates the adult's account, links them to the child (PENDING until the school verifies, unless the school auto-verifies its own SMS/email invites accepted from the invited destination) and signs them in. Wrong or expired codes are 400 auth.invalid_code")
	public ResponseEntity<TokenResponse> accept(@Valid @RequestBody InvitationAcceptRequest r) {
		Destination destination = destination(r.phone(), r.email(), r.country())
			.orElseThrow(() -> ApiException.badRequest("auth.invalid_code"));
		TokenPair pair = this.invitations.accept(key(r.token(), r.code()), destination, r.otpCode(), r.displayName(),
				r.preferredLanguage());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.header("Pragma", "no-cache")
			.body(new TokenResponse(pair.accessToken(), pair.accessTokenExpiresIn(), pair.refreshToken(),
					pair.refreshTokenExpiresIn(), "Bearer"));
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

	private static Optional<Destination> destination(String phone, String email, String country) {
		boolean hasPhone = phone != null && !phone.isBlank();
		boolean hasEmail = email != null && !email.isBlank();
		if (hasPhone == hasEmail) {
			return Optional.empty();
		}
		if (hasPhone) {
			return PhoneNumbers.toE164(phone, country).map((p) -> new Destination(Channel.SMS, p));
		}
		return Emails.normalize(email).map((e) -> new Destination(Channel.EMAIL, e));
	}

	@Schema(description = "Set exactly one of token (from the invitation link) or code (printed parent code), and exactly one of phone or email.")
	public record InvitationOtpRequest(@Size(max = 128, message = "validation.tooLong") String token,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = COUNTRY, message = "validation.invalid") String country) {

		@Override
		public String toString() {
			return "InvitationOtpRequest[redacted]";
		}

	}

	@Schema(description = "Set exactly one of token or code, and the same phone or email the code was sent to.")
	public record InvitationAcceptRequest(@Size(max = 128, message = "validation.tooLong") String token,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = COUNTRY, message = "validation.invalid") String country,
			@NotBlank(message = "validation.required") @Size(max = 16, message = "validation.tooLong") String otpCode,
			@Size(max = 100, message = "validation.tooLong") @Schema(description = "Name for a new account (ignored for an existing one)") String displayName,
			@Size(max = 8, message = "validation.tooLong") @Schema(description = "Language for a new account, e.g. am or en") String preferredLanguage) {

		@Override
		public String toString() {
			return "InvitationAcceptRequest[redacted]";
		}

	}

}
