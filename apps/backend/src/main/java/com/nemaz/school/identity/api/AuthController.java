package com.nemaz.school.identity.api;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.identity.application.Destination;
import com.nemaz.school.identity.application.Destination.Channel;
import com.nemaz.school.identity.application.Emails;
import com.nemaz.school.identity.application.IdentityProperties;
import com.nemaz.school.identity.application.LoginCodeSignInService;
import com.nemaz.school.identity.application.OtpSignInService;
import com.nemaz.school.identity.application.PasswordSignInService;
import com.nemaz.school.identity.application.PhoneNumbers;
import com.nemaz.school.identity.application.TokenService;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * Sign-in, token refresh and sign-out. Everything here except {@code logout-all} is public
 * and rate-limited; responses never reveal whether an account exists.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class AuthController {

	private static final String COUNTRY = "^[A-Za-z]{2}$";

	private final OtpSignInService otp;

	private final PasswordSignInService passwords;

	private final LoginCodeSignInService loginCodes;

	private final TokenService tokens;

	private final IdentityProperties props;

	AuthController(OtpSignInService otp, PasswordSignInService passwords, LoginCodeSignInService loginCodes,
			TokenService tokens, IdentityProperties props) {
		this.otp = otp;
		this.passwords = passwords;
		this.loginCodes = loginCodes;
		this.tokens = tokens;
		this.props = props;
	}

	@PostMapping("/otp/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(operationId = "requestOtp",
			summary = "Send a one-time sign-in code to a phone (SMS) or email; the answer is the same whether or not an account exists")
	public OtpRequestAccepted requestOtp(@Valid @RequestBody OtpRequest r) {
		Destination destination = destination(r.phone(), r.email(), r.country())
			.orElseThrow(() -> ApiException.badRequest("auth.invalid_destination"));
		this.otp.request(destination);
		return new OtpRequestAccepted(6, this.props.otp().ttl().toSeconds());
	}

	@PostMapping("/otp/verify")
	@Operation(operationId = "verifyOtp", summary = "Exchange a one-time code for tokens")
	public ResponseEntity<TokenResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest r) {
		Destination destination = destination(r.phone(), r.email(), r.country())
			.orElseThrow(() -> ApiException.badRequest("auth.invalid_code"));
		return tokenResponse(this.otp.verify(destination, r.code()));
	}

	@PostMapping("/password")
	@Operation(operationId = "signInWithPassword", summary = "Sign in with email, phone or username and a password")
	public ResponseEntity<TokenResponse> password(@Valid @RequestBody PasswordSignInRequest r) {
		return tokenResponse(this.passwords.signIn(r.identifier(), r.country(), r.password()));
	}

	@PostMapping("/login-code")
	@Operation(operationId = "signInWithLoginCode",
			summary = "Sign in a student with the QR token or text code their teacher printed (case and hyphen are ignored); every failure is the same 400 auth.invalid_code")
	public ResponseEntity<TokenResponse> loginCode(@Valid @RequestBody LoginCodeRequest r) {
		return tokenResponse(this.loginCodes.signIn(r.code()));
	}

	@PostMapping("/refresh")
	@Operation(operationId = "refreshTokens",
			summary = "Exchange a refresh token for a new pair; the old refresh token stops working")
	public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest r) {
		return tokenResponse(this.tokens.refresh(r.refreshToken()));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "logout", summary = "Sign out this device: revokes the refresh token's session")
	public void logout(@Valid @RequestBody RefreshRequest r) {
		this.tokens.logout(r.refreshToken());
	}

	@PostMapping("/logout-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "logoutAll", summary = "Sign out everywhere: revokes every session of the signed-in user")
	public void logoutAll() {
		CurrentUser user = CurrentUser.require();
		this.tokens.logoutAll(user.userId(), user.sessionId());
	}

	/** Exactly one of phone or email, well-formed. */
	private static java.util.Optional<Destination> destination(String phone, String email, String country) {
		boolean hasPhone = phone != null && !phone.isBlank();
		boolean hasEmail = email != null && !email.isBlank();
		if (hasPhone == hasEmail) {
			return java.util.Optional.empty();
		}
		if (hasPhone) {
			return PhoneNumbers.toE164(phone, country).map((p) -> new Destination(Channel.SMS, p));
		}
		return Emails.normalize(email).map((e) -> new Destination(Channel.EMAIL, e));
	}

	private static ResponseEntity<TokenResponse> tokenResponse(TokenPair pair) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.header("Pragma", "no-cache")
			.body(new TokenResponse(pair.accessToken(), pair.accessTokenExpiresIn(), pair.refreshToken(),
					pair.refreshTokenExpiresIn(), "Bearer"));
	}

	@Schema(description = "Set exactly one of phone or email. National phone formats are read in `country` (default ET).")
	public record OtpRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = COUNTRY, message = "validation.invalid") String country) {
	}

	public record OtpVerifyRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = COUNTRY, message = "validation.invalid") String country,
			@NotBlank(message = "validation.required") @Size(max = 16, message = "validation.tooLong") String code) {

		@Override
		public String toString() {
			return "OtpVerifyRequest[redacted]";
		}

	}

	public record PasswordSignInRequest(
			@NotBlank(message = "validation.required") @Size(max = 254,
					message = "validation.tooLong") @Schema(description = "Email, phone or username") String identifier,
			@NotBlank(message = "validation.required") @Size(max = 128, message = "validation.tooLong") String password,
			@Pattern(regexp = COUNTRY, message = "validation.invalid") String country) {

		@Override
		public String toString() {
			return "PasswordSignInRequest[redacted]";
		}

	}

	public record LoginCodeRequest(@NotBlank(message = "validation.required") @Size(max = 64,
			message = "validation.tooLong") @Schema(description = "A QR token or a text code such as K7M2P-9XQ4T") String code) {

		@Override
		public String toString() {
			return "LoginCodeRequest[redacted]";
		}

	}

	public record RefreshRequest(
			@NotBlank(message = "validation.required") @Size(max = 128, message = "validation.tooLong") String refreshToken) {

		@Override
		public String toString() {
			return "RefreshRequest[redacted]";
		}

	}

	public record OtpRequestAccepted(@NotNull int codeLength,
			@NotNull @Schema(description = "Seconds a code stays valid") long expiresIn) {
	}

	public record TokenResponse(@NotNull String accessToken,
			@NotNull @Schema(description = "Seconds until the access token expires") long accessTokenExpiresIn,
			@NotNull @Schema(description = "Shown once; store it securely") String refreshToken,
			@NotNull @Schema(description = "Seconds until the refresh token expires if unused") long refreshTokenExpiresIn,
			@NotNull @Schema(allowableValues = "Bearer") String tokenType) {

		@Override
		public String toString() {
			return "TokenResponse[redacted]";
		}

	}

}
