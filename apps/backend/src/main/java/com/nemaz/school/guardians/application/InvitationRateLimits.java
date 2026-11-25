package com.nemaz.school.guardians.application;

import java.time.Duration;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.system.RateLimiter;

import org.springframework.stereotype.Component;

/**
 * Guessing limits for invitation keys (preview, one-time code request, accept, signed-in redeem), like student login
 * codes: unknown keys count, known ones do not. Refused (429 {@code auth.rate_limited}) once 20 unknown keys came from
 * one client address or 10 for one parent-code prefix in 15 minutes, and after 300 attempts of any kind per address.
 */
@Component
class InvitationRateLimits {

	static final String CODE = "auth.rate_limited";

	private static final Duration WINDOW = Duration.ofMinutes(15);

	private final RateLimiter limiter;

	InvitationRateLimits(RateLimiter limiter) {
		this.limiter = limiter;
	}

	void attempt(String ip, String codePrefix) {
		if (ip != null) {
			this.limiter.consume("invite-key-ip-total", ip, 300, WINDOW, CODE);
			this.limiter.check("invite-key-ip-failed", ip, 20, WINDOW, CODE);
		}
		if (codePrefix != null) {
			this.limiter.check("invite-key-prefix-failed", codePrefix, 10, WINDOW, CODE);
		}
	}

	void failed(String ip, String codePrefix) {
		if (ip != null) {
			countFailure("invite-key-ip-failed", ip, 20);
		}
		if (codePrefix != null) {
			countFailure("invite-key-prefix-failed", codePrefix, 10);
		}
	}

	private void countFailure(String bucket, String key, int limit) {
		try {
			this.limiter.consume(bucket, key, limit, WINDOW, CODE);
		}
		catch (ApiException ex) {
			// Over the limit: the next attempt is refused by attempt(); this one stays a plain 404.
		}
	}

}
