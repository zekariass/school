package com.nemaz.school.authorization.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusRef;
import com.nemaz.school.tenancy.TenantDirectory.OrganizationRef;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;

import org.springframework.stereotype.Service;

/**
 * Scope-aware policy over the user's role grants (deny by default). A grant covers a target
 * when the target lies at or below the grant's scope:
 * <ul>
 * <li>PLATFORM covers everything;</li>
 * <li>COUNTRY covers the country, its organizations and its schools (by
 * {@code schools.country_code}) and their campuses;</li>
 * <li>ORGANIZATION covers the organization, its schools and their campuses;</li>
 * <li>SCHOOL covers the school and its campuses;</li>
 * <li>CAMPUS covers the campus. Read permissions in {@link #READABLE_FROM_CAMPUS} also let a
 * campus grant see its school (a campus admin can see the school its campus belongs to).</li>
 * </ul>
 * Parents are resolved through tenancy's {@link TenantDirectory}; an unknown target is covered
 * only by platform grants (so non-platform users get 403, never a 404 that confirms an id).
 */
@Service
public class AuthorizationService implements AccessControl {

	static final Set<String> READABLE_FROM_CAMPUS = Set.of(Permissions.SCHOOL_READ);

	private final TenantDirectory directory;

	AuthorizationService(TenantDirectory directory) {
		this.directory = directory;
	}

	@Override
	public boolean can(CurrentUser user, String permission, AccessScope target) {
		if (user == null || permission == null || target == null) {
			return false;
		}
		List<RoleGrant> grants = user.grants().stream().filter((g) -> g.allows(permission)).toList();
		if (grants.isEmpty()) {
			return false;
		}
		if (grants.stream().anyMatch((g) -> g.scope().level() == ScopeLevel.PLATFORM)) {
			return true;
		}
		Lineage lineage = lineage(target);
		if (lineage == null) {
			return false;
		}
		for (RoleGrant grant : grants) {
			if (covers(grant.scope(), lineage)) {
				return true;
			}
			if (READABLE_FROM_CAMPUS.contains(permission) && target.level() == ScopeLevel.SCHOOL
					&& grant.scope().level() == ScopeLevel.CAMPUS && lineage.schoolId().equals(grant.scope().schoolId())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean canAnywhere(CurrentUser user, String permission) {
		return user != null && user.grants().stream().anyMatch((g) -> g.allows(permission));
	}

	@Override
	public AccessibleScopes accessible(CurrentUser user, String permission) {
		if (user == null) {
			return AccessibleScopes.none();
		}
		boolean platform = false;
		Set<String> countries = new HashSet<>();
		Set<UUID> organizations = new HashSet<>();
		Set<UUID> schools = new HashSet<>();
		Set<UUID> campuses = new HashSet<>();
		for (RoleGrant grant : user.grants()) {
			if (!grant.allows(permission)) {
				continue;
			}
			AccessScope scope = grant.scope();
			switch (scope.level()) {
				case PLATFORM -> platform = true;
				case COUNTRY -> countries.add(scope.countryCode());
				case ORGANIZATION -> organizations.add(scope.organizationId());
				case SCHOOL -> schools.add(scope.schoolId());
				case CAMPUS -> {
					campuses.add(scope.campusId());
					if (READABLE_FROM_CAMPUS.contains(permission) && scope.schoolId() != null) {
						schools.add(scope.schoolId());
					}
				}
			}
		}
		return new AccessibleScopes(platform, countries, organizations, schools, campuses);
	}

	/**
	 * True when {@code user} may hand out {@code rolePermissions} at {@code scope}: they must
	 * hold {@code platform.roles.manage} and every one of the role's permissions at a scope
	 * covering it. Nobody can grant more than they hold, or above their own scope.
	 */
	public boolean canGrant(CurrentUser user, Set<String> rolePermissions, AccessScope scope) {
		if (!can(user, Permissions.PLATFORM_ROLES_MANAGE, scope)) {
			return false;
		}
		return rolePermissions.stream().allMatch((p) -> can(user, p, scope));
	}

	private static boolean covers(AccessScope grant, Lineage target) {
		return switch (grant.level()) {
			case PLATFORM -> true;
			case COUNTRY -> Objects.equals(grant.countryCode(), target.countryCode());
			case ORGANIZATION -> target.organizationId() != null && grant.organizationId().equals(target.organizationId());
			case SCHOOL -> target.schoolId() != null && grant.schoolId().equals(target.schoolId());
			case CAMPUS -> target.campusId() != null && grant.campusId().equals(target.campusId());
		};
	}

	/** The target and its ancestors, or null when the target is the platform or does not exist. */
	private Lineage lineage(AccessScope target) {
		return switch (target.level()) {
			case PLATFORM -> null;
			case COUNTRY -> new Lineage(target.countryCode(), null, null, null);
			case ORGANIZATION -> this.directory.organization(target.organizationId())
				.map((OrganizationRef o) -> new Lineage(o.countryCode(), o.id(), null, null))
				.orElse(null);
			case SCHOOL -> this.directory.school(target.schoolId()).map(AuthorizationService::of).orElse(null);
			case CAMPUS -> {
				CampusRef campus = this.directory.campus(target.campusId()).orElse(null);
				if (campus == null || (target.schoolId() != null && !target.schoolId().equals(campus.schoolId()))) {
					yield null;
				}
				Lineage school = this.directory.school(campus.schoolId()).map(AuthorizationService::of).orElse(null);
				yield (school != null) ? new Lineage(school.countryCode(), school.organizationId(), school.schoolId(),
						campus.id()) : null;
			}
		};
	}

	private static Lineage of(SchoolRef s) {
		return new Lineage(s.countryCode(), s.organizationId(), s.id(), null);
	}

	private record Lineage(String countryCode, UUID organizationId, UUID schoolId, UUID campusId) {
	}

}
