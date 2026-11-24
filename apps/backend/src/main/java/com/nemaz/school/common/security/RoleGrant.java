package com.nemaz.school.common.security;

import java.util.Set;

/** One ACTIVE role assignment of the current user: the role, where it applies and what it allows. */
public record RoleGrant(String roleCode, AccessScope scope, Set<String> permissions) {

	public RoleGrant {
		permissions = Set.copyOf(permissions);
	}

	public boolean allows(String permission) {
		return this.permissions.contains(permission);
	}

}
