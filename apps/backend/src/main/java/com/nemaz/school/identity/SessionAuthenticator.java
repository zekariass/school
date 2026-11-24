package com.nemaz.school.identity;

import java.util.UUID;

import org.springframework.security.core.AuthenticationException;

/**
 * Session checks for channels other than HTTP requests (the WebSocket gateway). The rules are
 * exactly those of the HTTP bearer-token path: valid signature, issuer, audience and expiry,
 * session family not revoked, account ACTIVE.
 */
public interface SessionAuthenticator {

	/**
	 * Validates an access token.
	 *
	 * @throws AuthenticationException when the token is invalid, expired, revoked or its
	 * account is inactive
	 */
	AuthenticatedSession authenticateAccessToken(String accessToken) throws AuthenticationException;

	/** True while the session family is not revoked and the user is ACTIVE (no token involved). */
	boolean isActive(UUID userId, UUID sessionId);

	/** The user and session family (the token's {@code sub} and {@code sid}). */
	record AuthenticatedSession(UUID userId, UUID sessionId) {
	}

}
