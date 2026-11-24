package com.nemaz.school.authorization;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.RoleGrant;

/** A user's administrative role assignments, for the identity module (principal, /me, bootstrap). */
public interface RoleAssignments {

	/**
	 * The user's ACTIVE grants with their permissions. Cached in Redis per user and evicted
	 * whenever one of the user's assignments changes; read on every authenticated request.
	 */
	List<RoleGrant> activeGrants(UUID userId);

	/** The user's ACTIVE assignments with localized role and scope names (for {@code GET /me}). */
	List<AssignmentView> activeAssignments(UUID userId, Locale locale);

	/** True when anyone holds {@code roleCode} ACTIVE. */
	boolean anyActive(String roleCode);

	/**
	 * Grants a role without an acting user (bootstrap): no permission check, but the role's
	 * scope level must match. Joins the caller's transaction and writes an audit row.
	 */
	void grantAsSystem(UUID userId, String roleCode, AccessScope scope);

	record AssignmentView(UUID id, String roleCode, String roleName, AccessScope scope, String scopeName,
			List<String> permissions, Instant grantedAt) {
	}

}
