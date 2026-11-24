package com.nemaz.school.common.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Scope-aware authorization (Implementation Plan M1 step 6), implemented by the
 * authorization module. It lives in the shared kernel so that every module can ask "may the
 * current user do X here?" without depending on the authorization module, while the
 * authorization module depends on the modules whose hierarchy it resolves (tenancy today;
 * classes and guardianship later). Deny by default: no grant, no access.
 */
public interface AccessControl {

	/** True when one of the user's grants allows {@code permission} at a scope covering {@code target}. */
	boolean can(CurrentUser user, String permission, AccessScope target);

	/** True when the user holds {@code permission} anywhere (for list endpoints that filter by scope). */
	boolean canAnywhere(CurrentUser user, String permission);

	/** Where the user holds {@code permission}, for SQL list filters. */
	AccessibleScopes accessible(CurrentUser user, String permission);

	/** {@link #can} or a 403. */
	default void require(CurrentUser user, String permission, AccessScope target) {
		if (!can(user, permission, target)) {
			throw new AccessDeniedException("Missing " + permission);
		}
	}

}
