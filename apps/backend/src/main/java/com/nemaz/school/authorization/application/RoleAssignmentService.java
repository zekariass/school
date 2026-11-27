package com.nemaz.school.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.authorization.RoleAssignmentGranted;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.authorization.domain.AssignmentStatus;
import com.nemaz.school.authorization.domain.Role;
import com.nemaz.school.authorization.domain.RoleAssignment;
import com.nemaz.school.authorization.infrastructure.RoleAssignmentRepository;
import com.nemaz.school.authorization.infrastructure.RolePermissions;
import com.nemaz.school.authorization.infrastructure.RoleRepository;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.i18n.SupportedLocales;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusRef;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.exception.ConstraintViolationException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants, revokes and lists administrative role assignments.
 * <ul>
 * <li><b>No escalation:</b> granting or revoking a role at a scope needs
 * {@code platform.roles.manage} and every permission of the role at a scope covering it
 * ({@link AuthorizationService#canGrant}); otherwise 403 {@code role.assignment_not_allowed}.</li>
 * <li>A role is assigned at exactly its {@code scope_level}.</li>
 * <li>The last ACTIVE platform administrator cannot be revoked.</li>
 * <li>Every change is audited and evicts the user's cached grants after commit, so the next
 * request sees it.</li>
 * </ul>
 */
@Service
@Transactional
public class RoleAssignmentService implements RoleAssignments {

	private final RoleRepository roles;

	private final RoleAssignmentRepository assignments;

	private final RolePermissions rolePermissions;

	private final AuthorizationService authorization;

	private final GrantCache grantCache;

	private final TenantDirectory directory;

	private final ReferenceData reference;

	private final AuditLog audit;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	private final SupportedLocales languages;

	RoleAssignmentService(RoleRepository roles, RoleAssignmentRepository assignments, RolePermissions rolePermissions,
			AuthorizationService authorization, GrantCache grantCache, TenantDirectory directory,
			ReferenceData reference, AuditLog audit, Clock clock, ApplicationEventPublisher events,
			SupportedLocales languages) {
		this.roles = roles;
		this.assignments = assignments;
		this.rolePermissions = rolePermissions;
		this.authorization = authorization;
		this.grantCache = grantCache;
		this.directory = directory;
		this.reference = reference;
		this.audit = audit;
		this.clock = clock;
		this.events = events;
		this.languages = languages;
	}

	// --- RoleAssignments (for the identity module) -------------------------------------

	@Override
	@Transactional(readOnly = true)
	public List<RoleGrant> activeGrants(UUID userId) {
		return this.grantCache.get(userId).grants();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AssignmentView> activeAssignments(UUID userId, Locale locale) {
		List<AssignmentView> views = new ArrayList<>();
		for (RoleAssignment a : this.assignments.findByUserIdAndStatusOrderByGrantedAtAsc(userId,
				AssignmentStatus.ACTIVE)) {
			Role role = this.roles.findById(a.getRoleCode()).orElseThrow();
			TenantDirectory.SchoolRef school = schoolOf(a.scope());
			views.add(new AssignmentView(a.getId(), role.getCode(), role.name(locale), a.scope(),
					scopeName(a.scope(), locale), (school != null) ? school.slug() : null,
					(school != null) ? school.name() : null,
					this.rolePermissions.of(role.getCode()).stream().sorted().toList(), a.getGrantedAt()));
		}
		return views;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean anyActive(String roleCode) {
		return this.assignments.existsByRoleCodeAndStatus(roleCode, AssignmentStatus.ACTIVE);
	}

	@Override
	public void grantAsSystem(UUID userId, String roleCode, AccessScope scope) {
		Role role = role(roleCode);
		AccessScope normalized = normalize(scope, role);
		save(userId, role, normalized, null);
	}

	@Override
	public boolean revokeAsSystem(UUID userId, String roleCode, AccessScope scope) {
		RoleAssignment assignment = this.assignments.findByUserIdAndStatusOrderByGrantedAtAsc(userId, AssignmentStatus.ACTIVE)
			.stream()
			.filter((a) -> a.getRoleCode().equals(roleCode) && a.scope().equals(scope))
			.findFirst()
			.orElse(null);
		if (assignment == null) {
			return false;
		}
		assignment.revoke(this.clock.instant());
		this.assignments.saveAndFlush(assignment);
		this.audit.record(AuditEntry.of("role_assignment.revoke", AuditOutcome.SUCCESS)
			.target("role_assignment", assignment.getId())
			.school(assignment.scope().schoolId())
			.metadata("userId", userId.toString())
			.metadata("roleCode", roleCode)
			.metadata("scope", describe(assignment.scope())));
		this.grantCache.evictAfterCommit(userId);
		return true;
	}

	@Override
	public void grantWithinScope(CurrentUser actor, UUID userId, String roleCode, AccessScope scope) {
		Role role = role(roleCode);
		if (scope.level() != role.getScopeLevel()) {
			throw ApiException.badRequest("role.scope_mismatch", role.getScopeLevel());
		}
		Set<String> permissions = this.rolePermissions.of(role.getCode());
		if (!permissions.stream().allMatch((p) -> this.authorization.can(actor, p, scope))) {
			throw ApiException.forbidden("role.assignment_not_allowed");
		}
		save(userId, role, normalize(scope, role), actor.userId());
	}

	// --- API operations ---------------------------------------------------------------

	public RoleAssignment grant(CurrentUser actor, UUID userId, String roleCode, AccessScope scope) {
		Role role = role(roleCode);
		if (scope.level() != role.getScopeLevel()) {
			throw ApiException.badRequest("role.scope_mismatch", role.getScopeLevel());
		}
		if (!this.authorization.canGrant(actor, this.rolePermissions.of(role.getCode()), scope)) {
			throw ApiException.forbidden("role.assignment_not_allowed");
		}
		AccessScope normalized = normalize(scope, role);
		return save(userId, role, normalized, actor.userId());
	}

	public RoleAssignment revoke(CurrentUser actor, UUID assignmentId) {
		RoleAssignment assignment = this.assignments.findById(assignmentId)
			.orElseThrow(() -> ApiException.notFound("role.assignment_not_found"));
		if (!this.authorization.canGrant(actor, this.rolePermissions.of(assignment.getRoleCode()),
				assignment.scope())) {
			throw ApiException.forbidden("role.assignment_not_allowed");
		}
		if (!assignment.isActive()) {
			return assignment;
		}
		if (RoleCodes.PLATFORM_ADMIN.equals(assignment.getRoleCode())
				&& this.assignments.countByRoleCodeAndStatus(RoleCodes.PLATFORM_ADMIN, AssignmentStatus.ACTIVE) <= 1) {
			throw ApiException.conflict("role.last_platform_admin");
		}
		assignment.revoke(this.clock.instant());
		this.assignments.saveAndFlush(assignment);
		this.audit.record(AuditEntry.of("role_assignment.revoke", AuditOutcome.SUCCESS)
			.target("role_assignment", assignment.getId())
			.school(assignment.scope().schoolId())
			.metadata("userId", assignment.getUserId().toString())
			.metadata("roleCode", assignment.getRoleCode())
			.metadata("scope", describe(assignment.scope())));
		this.grantCache.evictAfterCommit(assignment.getUserId());
		return assignment;
	}

	/**
	 * Assignments visible to {@code actor} (holder of {@code platform.roles.manage} at a scope
	 * covering each one). With a {@code scope}, the assignments at exactly that scope (keyset
	 * pages); otherwise those of {@code userId} (one page: a user holds few roles).
	 */
	@Transactional(readOnly = true)
	public CursorPage<RoleAssignment> list(CurrentUser actor, UUID userId, AccessScope scope, AssignmentStatus status,
			String cursor, Integer limit) {
		if (!this.authorization.canAnywhere(actor, Permissions.PLATFORM_ROLES_MANAGE)) {
			throw new AccessDeniedException("Missing " + Permissions.PLATFORM_ROLES_MANAGE);
		}
		AssignmentStatus wanted = (status != null) ? status : AssignmentStatus.ACTIVE;
		if (scope != null) {
			if (!this.authorization.can(actor, Permissions.PLATFORM_ROLES_MANAGE, scope)) {
				throw ApiException.forbidden("role.assignment_not_allowed");
			}
			Specification<RoleAssignment> filter = (root, query, cb) -> {
				List<Predicate> p = new ArrayList<>();
				p.add(cb.equal(root.get("status"), wanted));
				p.add(cb.equal(root.get("scopeType"), scope.level()));
				switch (scope.level()) {
					case PLATFORM -> {
					}
					case COUNTRY -> p.add(cb.equal(root.get("countryCode"), scope.countryCode()));
					case ORGANIZATION -> p.add(cb.equal(root.get("organizationId"), scope.organizationId()));
					case SCHOOL -> p.add(cb.equal(root.get("schoolId"), scope.schoolId()));
					case CAMPUS -> p.add(cb.equal(root.get("campusId"), scope.campusId()));
				}
				if (userId != null) {
					p.add(cb.equal(root.get("userId"), userId));
				}
				return cb.and(p.toArray(Predicate[]::new));
			};
			return KeysetPages.page(this.assignments, filter, cursor, limit, false);
		}
		if (userId == null) {
			throw ApiException.badRequest("role.filter_required");
		}
		Specification<RoleAssignment> byUser = (root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("status"), wanted));
		List<RoleAssignment> visible = this.assignments.findAll(byUser)
			.stream()
			.filter((a) -> this.authorization.can(actor, Permissions.PLATFORM_ROLES_MANAGE, a.scope()))
			.sorted(java.util.Comparator.comparing(RoleAssignment::getGrantedAt))
			.toList();
		return new CursorPage<>(visible, null);
	}

	// --- helpers ----------------------------------------------------------------------

	private RoleAssignment save(UUID userId, Role role, AccessScope scope, UUID grantedBy) {
		RoleAssignment saved;
		try {
			saved = this.assignments.saveAndFlush(
					new RoleAssignment(userId, role.getCode(), scope, grantedBy, this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			String constraint = constraintName(ex);
			if (constraint != null && constraint.contains("ux_role_assignment_active")) {
				throw ApiException.conflict("role.assignment_exists");
			}
			if (constraint != null && constraint.contains("user_id")) {
				throw ApiException.notFound("user.not_found");
			}
			throw ex;
		}
		this.audit.record(AuditEntry.of("role_assignment.grant", AuditOutcome.SUCCESS)
			.target("role_assignment", saved.getId())
			.school(scope.schoolId())
			.metadata("userId", userId.toString())
			.metadata("roleCode", role.getCode())
			.metadata("scope", describe(scope)));
		this.grantCache.evictAfterCommit(userId);
		Map<String, String> scopeNames = new LinkedHashMap<>();
		for (String language : this.languages.codes()) {
			String name = scopeName(scope, Locale.forLanguageTag(language));
			if (name != null) {
				scopeNames.put(language, name);
			}
		}
		this.events.publishEvent(new RoleAssignmentGranted(saved.getId(), userId, role.getCode(), role.names(),
				scope.level(), scope.schoolId(), scopeNames, grantedBy));
		return saved;
	}

	private Role role(String roleCode) {
		return this.roles.findById(roleCode).orElseThrow(() -> ApiException.badRequest("role.unknown"));
	}

	/** Checks the scope exists and adds a campus's school (stored with campus assignments). */
	private AccessScope normalize(AccessScope scope, Role role) {
		if (scope.level() != role.getScopeLevel()) {
			throw ApiException.badRequest("role.scope_mismatch", role.getScopeLevel());
		}
		return switch (scope.level()) {
			case PLATFORM -> scope;
			case COUNTRY -> {
				if (this.reference.findCountry(scope.countryCode()).isEmpty()) {
					throw ApiException.notFound("role.scope_not_found");
				}
				yield scope;
			}
			case ORGANIZATION -> {
				this.directory.organization(scope.organizationId())
					.orElseThrow(() -> ApiException.notFound("role.scope_not_found"));
				yield scope;
			}
			case SCHOOL -> {
				this.directory.school(scope.schoolId()).orElseThrow(() -> ApiException.notFound("role.scope_not_found"));
				yield scope;
			}
			case CAMPUS -> {
				CampusRef campus = this.directory.campus(scope.campusId())
					.orElseThrow(() -> ApiException.notFound("role.scope_not_found"));
				if (scope.schoolId() != null && !scope.schoolId().equals(campus.schoolId())) {
					throw ApiException.notFound("role.scope_not_found");
				}
				yield AccessScope.campus(campus.schoolId(), campus.id());
			}
		};
	}

	/** The school of a SCHOOL or CAMPUS scope, or null at other levels. */
	private TenantDirectory.SchoolRef schoolOf(AccessScope scope) {
		return switch (scope.level()) {
			case SCHOOL -> this.directory.school(scope.schoolId()).orElse(null);
			case CAMPUS -> this.directory.campus(scope.campusId())
				.flatMap((c) -> this.directory.school(c.schoolId()))
				.orElse(null);
			default -> null;
		};
	}

	String scopeName(AccessScope scope, Locale locale) {
		String language = (locale != null) ? locale.getLanguage() : "en";
		return switch (scope.level()) {
			case PLATFORM -> null;
			case COUNTRY -> this.reference.countryName(scope.countryCode(), language).orElse(scope.countryCode());
			case ORGANIZATION -> this.directory.organization(scope.organizationId())
				.map(TenantDirectory.OrganizationRef::name)
				.orElse(null);
			case SCHOOL -> this.directory.school(scope.schoolId()).map(TenantDirectory.SchoolRef::name).orElse(null);
			case CAMPUS -> this.directory.campus(scope.campusId()).map(CampusRef::name).orElse(null);
		};
	}

	private static String describe(AccessScope scope) {
		return switch (scope.level()) {
			case PLATFORM -> "PLATFORM";
			case COUNTRY -> "COUNTRY:" + scope.countryCode();
			case ORGANIZATION -> "ORGANIZATION:" + scope.organizationId();
			case SCHOOL -> "SCHOOL:" + scope.schoolId();
			case CAMPUS -> "CAMPUS:" + scope.campusId();
		};
	}

	private static String constraintName(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve) {
				return cve.getConstraintName();
			}
		}
		return null;
	}

}
