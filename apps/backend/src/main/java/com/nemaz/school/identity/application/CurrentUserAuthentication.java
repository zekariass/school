package com.nemaz.school.identity.application;

import java.util.Collection;

import com.nemaz.school.common.security.CurrentUser;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** An authenticated bearer token: principal {@link CurrentUser}, authorities = permissions held platform-wide. */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

	private final CurrentUser user;

	private final transient Jwt token;

	public CurrentUserAuthentication(CurrentUser user, Jwt token, Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.user = user;
		this.token = token;
		setAuthenticated(true);
	}

	@Override
	public CurrentUser getPrincipal() {
		return this.user;
	}

	/** The validated token (not the raw string). */
	@Override
	public Jwt getCredentials() {
		return this.token;
	}

	@Override
	public String getName() {
		return this.user.userId().toString();
	}

}
