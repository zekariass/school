package com.nemaz.school.common.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in user of the current request (the authentication principal), built from a
 * validated access token by the identity module: the user id ({@code sub}), the session
 * family id ({@code sid}), the user's preferred locale (null when unset) and the user's
 * ACTIVE administrative role grants. Use it for actor columns ({@code updated_by},
 * {@code granted_by}, ...) and pass it to {@link AccessControl}.
 */
public record CurrentUser(UUID userId, UUID sessionId, Locale locale, List<RoleGrant> grants) {

	public CurrentUser {
		grants = List.copyOf(grants);
	}

	/** The current request's user, empty when anonymous or authenticated some other way. */
	public static Optional<CurrentUser> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
			return Optional.of(user);
		}
		return Optional.empty();
	}

	/** The current user; 401 when there is none (only reachable when an endpoint is misconfigured). */
	public static CurrentUser require() {
		return current().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in user"));
	}

	/** The acting user's id for actor columns, or null outside an authenticated request. */
	public static UUID currentId() {
		return current().map(CurrentUser::userId).orElse(null);
	}

	public List<String> roleCodes() {
		return this.grants.stream().map(RoleGrant::roleCode).distinct().sorted().toList();
	}

}
