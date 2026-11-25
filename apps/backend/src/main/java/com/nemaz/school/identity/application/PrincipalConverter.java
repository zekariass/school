package com.nemaz.school.identity.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.common.security.CodedAuthenticationException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.identity.domain.UserStatus;
import com.nemaz.school.identity.infrastructure.AccessTokenKeys;
import com.nemaz.school.identity.infrastructure.SessionDenyList;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Turns a validated access token into the request's {@link CurrentUser}:
 * <ol>
 * <li>a session family on the deny-list (logout, reuse detection) is 401
 * {@code auth.session_revoked};</li>
 * <li>an account that is not ACTIVE (suspended, deactivated, deleted) is 401
 * {@code auth.account_inactive};</li>
 * <li>the user's ACTIVE role grants are loaded (cached per user); permissions held at platform
 * scope also become authorities, so {@code hasAuthority(...)} means "platform-wide".</li>
 * </ol>
 */
@Component
public class PrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final SessionDenyList denyList;

	private final UserSnapshotCache users;

	private final RoleAssignments roleAssignments;

	PrincipalConverter(SessionDenyList denyList, UserSnapshotCache users, RoleAssignments roleAssignments) {
		this.denyList = denyList;
		this.users = users;
		this.roleAssignments = roleAssignments;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID userId = uuid(jwt.getSubject());
		UUID sessionId = uuid(jwt.getClaimAsString(AccessTokenKeys.SESSION_CLAIM));
		UserSnapshotCache.UserSnapshot user = requireActive(userId, sessionId);
		List<RoleGrant> grants = this.roleAssignments.activeGrants(userId);
		List<GrantedAuthority> authorities = grants.stream()
			.filter((g) -> g.scope().level() == ScopeLevel.PLATFORM)
			.flatMap((g) -> g.permissions().stream())
			.distinct()
			.sorted()
			.<GrantedAuthority>map(SimpleGrantedAuthority::new)
			.toList();
		Locale locale = (user.preferredLanguageCode() != null) ? Locale.forLanguageTag(user.preferredLanguageCode())
				: null;
		return new CurrentUserAuthentication(new CurrentUser(userId, sessionId, locale, grants), jwt, authorities);
	}

	/**
	 * The session and account rules shared by every way of authenticating (HTTP bearer tokens,
	 * WebSocket CONNECT): the family must not be deny-listed and the account must be ACTIVE.
	 */
	public UserSnapshotCache.UserSnapshot requireActive(UUID userId, UUID sessionId) {
		if (this.denyList.isDenied(sessionId)) {
			throw new CodedAuthenticationException("auth.session_revoked");
		}
		UserSnapshotCache.UserSnapshot user = this.users.get(userId);
		if (user == null || user.status() != UserStatus.ACTIVE) {
			throw new CodedAuthenticationException("auth.account_inactive");
		}
		return user;
	}

	private static UUID uuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidBearerTokenException("Malformed token subject or session");
		}
	}

}
