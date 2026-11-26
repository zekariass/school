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

	/**
	 * Ends the user's ACTIVE assignment of {@code roleCode} at exactly {@code scope} (designating and removing a safeguarding
	 * lead is a school decision, not a platform-roles one): no permission check, the caller made its own; audited here.
	 * Returns false when there was none. Joins the caller's transaction.
	 */
	boolean revokeAsSystem(UUID userId, String roleCode, AccessScope scope);

	/**
	 * Grants a role on behalf of an administrator who manages the people of the scope (staff provisioning) rather
	 * than roles in general: no {@code platform.roles.manage} is needed, but there is no escalation either, since the
	 * actor must hold every permission of the role at a scope covering it (otherwise 403
	 * {@code role.assignment_not_allowed}). The caller has made its own permission check and audits its own action;
	 * the grant itself is audited here. Joins the caller's transaction.
	 */
	void grantWithinScope(com.nemaz.school.common.security.CurrentUser actor, UUID userId, String roleCode,
			AccessScope scope);

	/**
	 * {@code schoolSlug} and {@code schoolName} identify the school of a SCHOOL or CAMPUS scope
	 * (null at the other levels), so clients can build school URLs.
	 */
	record AssignmentView(UUID id, String roleCode, String roleName, AccessScope scope, String scopeName,
			String schoolSlug, String schoolName, List<String> permissions, Instant grantedAt) {
	}

}
