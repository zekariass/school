package com.nemaz.school.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in and token settings ({@code school.auth.*}). Deployed environments must supply
 * {@code SCHOOL_AUTH_OTP_PEPPER} and {@code SCHOOL_AUTH_JWT_KEYS}; the application refuses to
 * start without them (local and test profiles supply development values).
 */
@ConfigurationProperties("school.auth")
public record IdentityProperties(@DefaultValue Jwt jwt, @DefaultValue Otp otp, @DefaultValue Sessions sessions,
		@DefaultValue Password password) {

	/**
	 * Access tokens. {@code keys} is a JWK Set JSON document ({@code {"keys":[...]}}) of EC
	 * P-256 keys with their private parts: the first key signs (its {@code kid} goes into the
	 * header), all keys verify, so a new key can be added in front of the old one for rotation.
	 * {@code allowEphemeralKey} generates a throwaway key at startup when none is configured
	 * (local and test only: tokens die with the process).
	 */
	public record Jwt(@DefaultValue("school-api") String issuer, @DefaultValue("school-apps") String audience,
			String keys, @DefaultValue("false") boolean allowEphemeralKey,
			@DefaultValue("PT15M") Duration accessTokenTtl, @DefaultValue("PT60S") Duration clockSkew) {
	}

	/**
	 * One-time codes: {@code pepper} is the HMAC key of the stored code hashes (at least 32
	 * characters, secret, never logged). A code lives {@code ttl} and allows
	 * {@code maxAttempts} guesses.
	 */
	public record Otp(String pepper, @DefaultValue("PT5M") Duration ttl, @DefaultValue("5") int maxAttempts) {
	}

	/**
	 * Refresh-token sessions: absolute lifetime from sign-in, and how long an unused session survives.
	 * Sessions started with a student login code are deliberately shorter (a shared classroom tablet):
	 * {@code loginCodeAbsoluteTtl} and {@code loginCodeIdleTtl}.
	 */
	public record Sessions(@DefaultValue("P30D") Duration absoluteTtl, @DefaultValue("P14D") Duration idleTtl,
			@DefaultValue("P7D") Duration loginCodeAbsoluteTtl, @DefaultValue("P2D") Duration loginCodeIdleTtl) {
	}

	/** Password lockout: after {@code maxFailures} consecutive failures, refuse for {@code lockDuration}. */
	public record Password(@DefaultValue("5") int maxFailures, @DefaultValue("PT15M") Duration lockDuration) {
	}

}
