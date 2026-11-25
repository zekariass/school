package com.nemaz.school.common.security;

import java.util.UUID;

import com.nemaz.school.common.error.ApiException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The 404-or-403 rule of school-scoped endpoints (the pattern of {@code GET /schools/by-slug}): a caller
 * who cannot see the school at all (no {@code school.read} on it and no ACTIVE staff or student membership)
 * gets 404 {@code school.not_found}, exactly as for an id that does not exist, so ids cannot be probed
 * across tenants; a caller who belongs to the school but lacks the permission gets 403.
 */
@Component
public class SchoolGate {

	private final AccessControl access;

	private final SchoolMemberships memberships;

	SchoolGate(AccessControl access, SchoolMemberships memberships) {
		this.access = access;
		this.memberships = memberships;
	}

	/** True when the user holds the permission at the school. */
	public boolean can(CurrentUser user, UUID schoolId, String permission) {
		return this.access.can(user, permission, AccessScope.school(schoolId));
	}

	/** Visible: {@code school.read} on it or an ACTIVE membership. */
	public boolean canSee(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_READ, AccessScope.school(schoolId))
				|| this.memberships.isActiveMember(user.userId(), schoolId);
	}

	/** 404 unless the school is visible to the user. */
	public void requireVisible(CurrentUser user, UUID schoolId) {
		if (!canSee(user, schoolId) || !exists(user, schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
	}

	/** Only platform-wide grants cover ids of schools that do not exist, so only they need the lookup. */
	private boolean exists(CurrentUser user, UUID schoolId) {
		return user.grants().stream().noneMatch((g) -> g.scope().level() == ScopeLevel.PLATFORM)
				|| this.memberships.schoolExists(schoolId);
	}

	/** The permission at the school; 404 when the school is not visible, 403 when it is but the permission is missing. */
	public void require(CurrentUser user, UUID schoolId, String permission) {
		if (can(user, schoolId, permission)) {
			if (!exists(user, schoolId)) {
				throw ApiException.notFound("school.not_found");
			}
			return;
		}
		requireVisible(user, schoolId);
		throw new AccessDeniedException("Missing " + permission);
	}

}
