package com.nemaz.school.identity.application;

import java.time.Duration;

import com.nemaz.school.system.RateLimiter;

import org.springframework.stereotype.Component;

/**
 * The sign-in rate limits (fixed windows in Redis); exceeding one is 429
 * {@code auth.rate_limited} with {@code Retry-After}. Limits per destination or identifier
 * apply whether or not an account exists, so they do not reveal accounts either.
 */
@Component
public class AuthRateLimits {

	static final String CODE = "auth.rate_limited";

	private final RateLimiter limiter;

	AuthRateLimits(RateLimiter limiter) {
		this.limiter = limiter;
	}

	/** 5 per destination per hour; 30 per client address per hour. */
	void otpRequest(String destination, String ip) {
		this.limiter.consume("otp-request-destination", destination, 5, Duration.ofHours(1), CODE);
		if (ip != null) {
			this.limiter.consume("otp-request-ip", ip, 30, Duration.ofHours(1), CODE);
		}
	}

	/** 10 per destination per 15 minutes. */
	void otpVerify(String destination) {
		this.limiter.consume("otp-verify-destination", destination, 10, Duration.ofMinutes(15), CODE);
	}

	/**
	 * Invitation codes go to destinations that may have no account (the only place that happens): 3 per destination
	 * and 20 per client address per hour.
	 */
	void inviteOtpRequest(String destination, String ip) {
		this.limiter.consume("invite-otp-destination", destination, 3, Duration.ofHours(1), CODE);
		if (ip != null) {
			this.limiter.consume("invite-otp-ip", ip, 20, Duration.ofHours(1), CODE);
		}
	}

	/** 10 invitation-code checks per destination per 15 minutes. */
	void inviteOtpVerify(String destination) {
		this.limiter.consume("invite-otp-verify-destination", destination, 10, Duration.ofMinutes(15), CODE);
	}

	/** 30 per administrator per 15 minutes: user lookups must not become an enumeration tool. */
	void userLookup(java.util.UUID actorId) {
		this.limiter.consume("user-lookup-actor", actorId.toString(), 30, Duration.ofMinutes(15), CODE);
	}

	/**
	 * Student login codes. Failures are what count (a classroom signs in from one address): refused once 20
	 * failures from one client address or 10 for one code prefix fell in 15 minutes, and 300 attempts of any
	 * kind per address. The prefix key makes guessing the rest of a code that is almost known as slow as
	 * guessing a whole one.
	 */
	void loginCodeAttempt(String ip, String prefix) {
		if (ip != null) {
			this.limiter.consume("login-code-ip-total", ip, 300, Duration.ofMinutes(15), CODE);
			this.limiter.check("login-code-ip-failed", ip, 20, Duration.ofMinutes(15), CODE);
		}
		this.limiter.check("login-code-prefix-failed", prefix, 10, Duration.ofMinutes(15), CODE);
	}

	/** Counts one failed login-code attempt against the address and the code prefix. */
	void loginCodeFailed(String ip, String prefix) {
		if (ip != null) {
			countFailure("login-code-ip-failed", ip, 20);
		}
		countFailure("login-code-prefix-failed", prefix, 10);
	}

	private void countFailure(String bucket, String key, int limit) {
		try {
			this.limiter.consume(bucket, key, limit, Duration.ofMinutes(15), CODE);
		}
		catch (com.nemaz.school.common.error.ApiException ex) {
			// Over the limit: the next attempt is refused by loginCodeAttempt; this one stays a plain 400.
		}
	}

	/** 10 per identifier and 50 per client address per 15 minutes. */
	void password(String identifier, String ip) {
		this.limiter.consume("password-identifier", identifier, 10, Duration.ofMinutes(15), CODE);
		if (ip != null) {
			this.limiter.consume("password-ip", ip, 50, Duration.ofMinutes(15), CODE);
		}
	}

}
