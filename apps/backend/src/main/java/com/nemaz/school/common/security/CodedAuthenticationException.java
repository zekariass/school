package com.nemaz.school.common.security;

import org.springframework.security.core.AuthenticationException;

/**
 * A rejected bearer token with a stable problem code (e.g. {@code auth.session_revoked},
 * {@code auth.account_inactive}); the 401 problem body carries {@link #code()}. Only raised
 * for tokens that were validly signed, so it never tells a stranger whether an account exists.
 */
public class CodedAuthenticationException extends AuthenticationException {

	private final String code;

	public CodedAuthenticationException(String code) {
		super(code);
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
