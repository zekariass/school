package com.nemaz.school.system;

import java.time.Duration;

/**
 * Fixed-window rate limits shared by every backend instance (Redis {@code INCR} +
 * {@code EXPIRE}). Keys are hashed before they reach Redis, so phone numbers and emails are
 * not stored there in clear.
 */
public interface RateLimiter {

	/**
	 * Counts one hit of {@code key} in {@code bucket}. Once more than {@code limit} hits fall
	 * in the current window, throws a 429 {@code ApiException} with {@code problemCode} and a
	 * {@code Retry-After} of the window's remaining time. When Redis is unavailable the hit
	 * is allowed (and logged): sign-in must keep working, and the database-side limits
	 * (OTP attempts, password lockout) still apply.
	 */
	/**
	 * Like {@link #consume} but without counting: throws the same 429 when {@code limit} hits already fell in
	 * the current window. For limits that count failures only (counted separately with {@link #consume}).
	 */
	void check(String bucket, String key, int limit, Duration window, String problemCode);

	void consume(String bucket, String key, int limit, Duration window, String problemCode);

}
