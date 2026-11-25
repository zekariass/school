package com.nemaz.school.authorization;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.ScopeLevel;

/**
 * A role was given to a user. Names are carried per language ({@code en}, {@code am}) so
 * listeners can write to the user in their own language; {@code scopeNames} is empty at
 * platform level. {@code grantedBy} is null for system grants (bootstrap). Delivered at least
 * once.
 */
public record RoleAssignmentGranted(UUID assignmentId, UUID userId, String roleCode, Map<String, String> roleNames,
		ScopeLevel scopeLevel, UUID schoolId, Map<String, String> scopeNames, UUID grantedBy) {

	public RoleAssignmentGranted {
		roleNames = Map.copyOf(roleNames);
		scopeNames = Map.copyOf(scopeNames);
	}

}
