package com.nemaz.school.authorization.api;

import java.util.UUID;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.stereotype.Component;

/**
 * The {@code @authz} bean for {@code @PreAuthorize}: scope-aware checks of the current user,
 * delegating to {@link AccessControl}. Anonymous or non-user principals are denied.
 * <pre>
 * &#64;PreAuthorize("@authz.school('school.manage', #schoolId)")
 * &#64;PreAuthorize("@authz.can('platform.settings.manage', T(com.nemaz.school.common.security.AccessScope).country(#code))")
 * </pre>
 * A null id is denied (the request is then answered 403, not 400; validation runs first for
 * request bodies).
 */
@Component("authz")
public class AuthzExpressions {

	private final AccessControl access;

	AuthzExpressions(AccessControl access) {
		this.access = access;
	}

	public boolean can(String permission, AccessScope scope) {
		CurrentUser user = CurrentUser.current().orElse(null);
		return user != null && scope != null && this.access.can(user, permission, scope);
	}

	/** True when any of {@code permissions} is held at a scope covering {@code scope}. */
	public boolean anyAt(AccessScope scope, String... permissions) {
		for (String permission : permissions) {
			if (can(permission, scope)) {
				return true;
			}
		}
		return false;
	}

	public boolean platform(String permission) {
		return can(permission, AccessScope.platform());
	}

	public boolean country(String permission, String countryCode) {
		return countryCode != null && can(permission, AccessScope.country(countryCode));
	}

	public boolean organization(String permission, UUID organizationId) {
		return organizationId != null && can(permission, AccessScope.organization(organizationId));
	}

	public boolean school(String permission, UUID schoolId) {
		return schoolId != null && can(permission, AccessScope.school(schoolId));
	}

	public boolean campus(String permission, UUID campusId) {
		return campusId != null && can(permission, AccessScope.campus(campusId));
	}

	/** Held at any scope at all: for list endpoints whose results are then filtered by scope. */
	public boolean anywhere(String permission) {
		CurrentUser user = CurrentUser.current().orElse(null);
		return user != null && this.access.canAnywhere(user, permission);
	}

}
