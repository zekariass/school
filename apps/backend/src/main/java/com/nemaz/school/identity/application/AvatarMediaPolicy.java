package com.nemaz.school.identity.application;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;

import org.springframework.stereotype.Component;

/**
 * Who may upload and see avatars (R-30: derived from the owning object, the user).
 * <ul>
 * <li><b>Create:</b> only the user, for themself.</li>
 * <li><b>Read:</b> the user; anyone holding a platform-scope role; anyone holding a role in a
 * school where the user also holds one (staff of the same school); and anyone who may read
 * such a school (a country or organization administrator over it); and, through school memberships
 * (M3a), members of the subject's school: staff see every member, students and teachers see the
 * students they share a class with. Guardians arrive with M5.</li>
 * </ul>
 * A user without any role is visible only to themself and platform roles for now.
 */
@Component
class AvatarMediaPolicy implements MediaPurposePolicy {

	private final RoleAssignments assignments;

	private final AccessControl access;

	private final com.nemaz.school.common.security.SchoolMemberships memberships;

	AvatarMediaPolicy(RoleAssignments assignments, AccessControl access,
			com.nemaz.school.common.security.SchoolMemberships memberships) {
		this.assignments = assignments;
		this.access = access;
		this.memberships = memberships;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.AVATAR;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return context.type() == ContextType.USER && context.id().equals(user.userId());
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		UUID subject = media.context().id();
		if (subject.equals(user.userId())) {
			return true;
		}
		if (user.grants().stream().anyMatch((g) -> g.scope().level() == ScopeLevel.PLATFORM)) {
			return true;
		}
		if (this.memberships.mayViewAvatar(user.userId(), subject)) {
			return true;
		}
		Set<UUID> subjectSchools = schoolsOf(this.assignments.activeGrants(subject).stream().toList());
		if (subjectSchools.isEmpty()) {
			return false;
		}
		Set<UUID> readerSchools = schoolsOf(user.grants());
		for (UUID school : subjectSchools) {
			if (readerSchools.contains(school) || this.access.can(user, Permissions.SCHOOL_READ, AccessScope.school(school))) {
				return true;
			}
		}
		return false;
	}

	/** Schools named by school and campus grants. */
	private static Set<UUID> schoolsOf(Iterable<RoleGrant> grants) {
		Set<UUID> schools = new HashSet<>();
		for (RoleGrant grant : grants) {
			AccessScope scope = grant.scope();
			if ((scope.level() == ScopeLevel.SCHOOL || scope.level() == ScopeLevel.CAMPUS) && scope.schoolId() != null) {
				schools.add(scope.schoolId());
			}
		}
		return schools;
	}

}
