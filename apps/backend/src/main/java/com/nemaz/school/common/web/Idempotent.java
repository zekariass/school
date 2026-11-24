package com.nemaz.school.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a POST endpoint into {@code Idempotency-Key} handling (system module): an
 * authenticated request carrying the header is executed once per (user, key); a retry with
 * the same request replays the stored response with {@code Idempotent-Replayed: true}, a
 * different request under the same key is 422 {@code idempotency.key_reused}, and a retry
 * while the first is still running is 409 {@code idempotency.in_progress}. Only successful
 * (2xx) responses are stored, for 24 hours; requests without the header run normally.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

	String HEADER = "Idempotency-Key";

	String REPLAYED_HEADER = "Idempotent-Replayed";

}
