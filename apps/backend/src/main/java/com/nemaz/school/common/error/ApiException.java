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

	public ApiException(HttpStatus status, String code, Object... args) {
		super(code);
		this.status = status;
		this.code = code;
		this.args = args;
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

}
