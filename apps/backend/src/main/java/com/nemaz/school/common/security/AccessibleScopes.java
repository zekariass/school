package com.nemaz.school.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Where a user holds a permission, for filtering list queries in SQL (spec §100: lists are
 * filtered server-side, never trimmed by the client). {@code platform} means "everything";
 * otherwise a row is visible when it falls under one of the countries, organizations,
 * schools or campuses. {@code schoolIds} also contains the schools of campus grants when the
 * permission is readable from a campus (see {@link AccessControl}).
 */
public record AccessibleScopes(boolean platform, Set<String> countryCodes, Set<UUID> organizationIds,
		Set<UUID> schoolIds, Set<UUID> campusIds) {

	private static final AccessibleScopes NONE = new AccessibleScopes(false, Set.of(), Set.of(), Set.of(), Set.of());

	public AccessibleScopes {
		countryCodes = Set.copyOf(countryCodes);
		organizationIds = Set.copyOf(organizationIds);
		schoolIds = Set.copyOf(schoolIds);
		campusIds = Set.copyOf(campusIds);
	}

	public static AccessibleScopes none() {
		return NONE;
	}

	/** True when the permission is not held anywhere. */
	public boolean isEmpty() {
		return !this.platform && this.countryCodes.isEmpty() && this.organizationIds.isEmpty()
				&& this.schoolIds.isEmpty() && this.campusIds.isEmpty();
	}

}
