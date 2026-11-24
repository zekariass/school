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

	/** 30 per administrator per 15 minutes: user lookups must not become an enumeration tool. */
	void userLookup(java.util.UUID actorId) {
		this.limiter.consume("user-lookup-actor", actorId.toString(), 30, Duration.ofMinutes(15), CODE);
	}

	/** 10 per identifier and 50 per client address per 15 minutes. */
	void password(String identifier, String ip) {
		this.limiter.consume("password-identifier", identifier, 10, Duration.ofMinutes(15), CODE);
		if (ip != null) {
			this.limiter.consume("password-ip", ip, 50, Duration.ofMinutes(15), CODE);
		}
	}

}
