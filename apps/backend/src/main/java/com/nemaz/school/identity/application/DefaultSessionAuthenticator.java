package com.nemaz.school.identity.application;

import java.util.UUID;

import com.nemaz.school.common.security.CodedAuthenticationException;
import com.nemaz.school.identity.SessionAuthenticator;
import com.nemaz.school.identity.infrastructure.AccessTokenKeys;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
class DefaultSessionAuthenticator implements SessionAuthenticator {

	private final JwtDecoder decoder;

	private final PrincipalConverter principals;

	DefaultSessionAuthenticator(JwtDecoder decoder, PrincipalConverter principals) {
		this.decoder = decoder;
		this.principals = principals;
	}

	@Override
	public AuthenticatedSession authenticateAccessToken(String accessToken) throws AuthenticationException {
		Jwt jwt;
		try {
			jwt = this.decoder.decode(accessToken);
		}
		catch (JwtException ex) {
			throw new BadCredentialsException("auth.invalid_token", ex);
		}
		UUID userId;
		UUID sessionId;
		try {
			userId = UUID.fromString(jwt.getSubject());
			sessionId = UUID.fromString(jwt.getClaimAsString(AccessTokenKeys.SESSION_CLAIM));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new BadCredentialsException("auth.invalid_token");
		}
		this.principals.requireActive(userId, sessionId);
		return new AuthenticatedSession(userId, sessionId);
	}

	@Override
	public boolean isActive(UUID userId, UUID sessionId) {
		try {
			this.principals.requireActive(userId, sessionId);
			return true;
		}
		catch (CodedAuthenticationException ex) {
			return false;
		}
	}

}
