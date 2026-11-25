package com.nemaz.school.guardians;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which adult may do what for which child, for every module that serves families (feed, attendance, points,
 * messaging, consent). Deny by default: only an ACTIVE link without {@code custody_restricted} counts, and a custody
 * restriction overrides every flag; PENDING, REJECTED and REVOKED links count for nothing. Reads the database on every
 * call (no cache), so a revoked or restricted link stops working on the next request.
 * <p>
 * The child's school enrollment is not part of these answers: callers that serve school data also check that the child
 * is an ACTIVE student of that school (as the class rule does).
 */
public interface GuardianAccess {

	/** True when the user holds an ACTIVE, not custody-restricted link to the student. */
	boolean isActiveGuardian(UUID userId, UUID studentId);

	/** True when such a link exists and its flag for {@code permission} is on. */
	boolean can(UUID userId, UUID studentId, GuardianPermission permission);

	/** The student's ACTIVE, not custody-restricted guardians with their permissions (primary first). */
	List<GuardianGrant> guardiansOf(UUID studentId);

	/** The user's ACTIVE, not custody-restricted links (children), with their permissions. */
	List<GuardianGrant> childrenOf(UUID userId);

	/** One link if it is ACTIVE and not custody-restricted; empty otherwise (or unknown). */
	Optional<GuardianGrant> activeLink(UUID linkId);

	/** An ACTIVE, not custody-restricted link and what it allows. */
	record GuardianGrant(UUID linkId, UUID guardianId, UUID studentId, RelationshipType relationshipType,
			boolean primary, Set<GuardianPermission> permissions) {

		public GuardianGrant {
			permissions = Set.copyOf(permissions);
		}

		public boolean allows(GuardianPermission permission) {
			return this.permissions.contains(permission);
		}

	}

}
