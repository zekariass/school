package com.nemaz.school.common.error;

import org.springframework.http.HttpStatus;

/**
 * A failure with a stable, machine-readable {@code code} (e.g. "class.access_denied").
 * Rendered as RFC 9457 problem details by {@link GlobalExceptionHandler}; the human
 * message is looked up as {@code error.<code>} in messages*.properties in the
 * caller's language, with {@code args} as MessageFormat arguments.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final transient Object[] args;

	private long retryAfterSeconds;

	public ApiException(HttpStatus status, String code, Object... args) {
		super(code);
		this.status = status;
		this.code = code;
		this.args = args;
	}

	public static ApiException unauthorized(String code, Object... args) {
		return new ApiException(HttpStatus.UNAUTHORIZED, code, args);
	}

	public static ApiException unprocessable(String code, Object... args) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, args);
	}

	/** 429 with a {@code Retry-After} header (seconds, at least 1). */
	public static ApiException tooManyRequests(String code, long retryAfterSeconds) {
		ApiException ex = new ApiException(HttpStatus.TOO_MANY_REQUESTS, code);
		ex.retryAfterSeconds = Math.max(1, retryAfterSeconds);
		return ex;
	}

	public static ApiException badRequest(String code, Object... args) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, args);
	}

	public static ApiException notFound(String code, Object... args) {
		return new ApiException(HttpStatus.NOT_FOUND, code, args);
	}

	public static ApiException forbidden(String code, Object... args) {
		return new ApiException(HttpStatus.FORBIDDEN, code, args);
	}

	public static ApiException conflict(String code, Object... args) {
		return new ApiException(HttpStatus.CONFLICT, code, args);
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public Object[] args() {
		return this.args.clone();
	}

	/** Seconds for the {@code Retry-After} header, or 0 when the response has none. */
	public long retryAfterSeconds() {
		return this.retryAfterSeconds;
	}

}
