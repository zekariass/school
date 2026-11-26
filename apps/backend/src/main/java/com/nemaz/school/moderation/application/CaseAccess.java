package com.nemaz.school.moderation.application;

import java.util.UUID;

import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.moderation.application.CaseRows.CaseRow;
import com.nemaz.school.tenancy.FeatureService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may work a case (docs/Safeguarding_Rules.md S2.5, S2.7), always answered server-side:
 * <ul>
 * <li>a case in a SCHOOL queue is worked by holders of {@code school.moderation.manage} at the school; a case in the PLATFORM
 * queue by holders of {@code platform.moderation.manage}. A school moderator never sees the platform queue;</li>
 * <li>nobody works a case about themselves, about their own content, about their child (as guardian) or one they reported:
 * they get 404, exactly as for an unknown id (the case is then reassigned to the next level by routing);</li>
 * <li>reporter identity is for safeguarding leads and platform moderators only.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
class CaseAccess {

	static final String FEATURE = "moderation";

	private final AccessControl access;

	private final RoleAssignments roles;

	private final JdbcClient jdbc;

	private final PeopleLookup people;

	private final FeatureService features;

	CaseAccess(AccessControl access, RoleAssignments roles, JdbcClient jdbc, PeopleLookup people, FeatureService features) {
		this.access = access;
		this.roles = roles;
		this.jdbc = jdbc;
		this.people = people;
		this.features = features;
	}

	static ApiException notFound() {
		return ApiException.notFound("moderation.case_not_found");
	}

	static AccessScope scopeOf(UUID schoolId) {
		return (schoolId != null) ? AccessScope.school(schoolId) : AccessScope.platform();
	}

	/** 403 {@code moderation.feature_disabled} when the school switched moderation off. */
	void requireFeature(UUID schoolId) {
		if (schoolId != null && !this.features.isEnabled(FEATURE, schoolId)) {
			throw ApiException.forbidden("moderation.feature_disabled");
		}
	}

	boolean canWorkQueue(CurrentUser user, CaseRow row) {
		return "PLATFORM".equals(row.queue())
				? this.access.can(user, Permissions.PLATFORM_MODERATION_MANAGE, AccessScope.platform())
				: row.schoolId() != null && this.access.can(user, Permissions.SCHOOL_MODERATION_MANAGE, AccessScope.school(row.schoolId()));
	}

	/** Subject, guardian of the subject, or one of the reporters. */
	boolean conflicted(UUID userId, CaseRow row) {
		if (userId.equals(row.subjectUserId())) {
			return true;
		}
		if (row.subjectUserId() != null && this.people.isGuardianOf(userId, row.subjectUserId())) {
			return true;
		}
		return this.jdbc.sql("select exists (select 1 from content_reports where case_id = :c and reporter_id = :u)")
			.param("c", row.id())
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	CaseRow find(UUID caseId) {
		return this.jdbc.sql("select " + CaseRows.COLUMNS + " from moderation_cases c where c.id = :id")
			.param("id", caseId)
			.query(CaseRows::caseRow)
			.optional()
			.orElse(null);
	}

	/** The case when the caller may work it (queue permission, no conflict), else 404. */
	CaseRow workable(CurrentUser user, UUID caseId) {
		CaseRow row = find(caseId);
		if (row == null || !canWorkQueue(user, row) || conflicted(user.userId(), row)) {
			throw notFound();
		}
		requireFeature(row.schoolId());
		return row;
	}

	/** A safeguarding lead of the case's school (or a platform lead): may set CRITICAL, lift cases, restore escalated ones. */
	boolean isLead(CurrentUser user, CaseRow row) {
		return this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_MANAGE, scopeOf(row.schoolId()));
	}

	/** Who sees the identity of reporters: safeguarding leads and platform moderators (never administrators as such). */
	boolean sees(CurrentUser user, CaseRow row) {
		if (this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_READ, scopeOf(row.schoolId()))) {
			return true;
		}
		return user.grants().stream().anyMatch((g) -> RoleCodes.PLATFORM_MODERATOR.equals(g.roleCode())
				&& g.allows(Permissions.PLATFORM_MODERATION_MANAGE));
	}

	/** Only safeguarding leads know that an escalation created an incident (moderators must not learn that one exists). */
	boolean canReadIncidents(CurrentUser user, CaseRow row) {
		return this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_READ, scopeOf(row.schoolId()));
	}

	/** Legal hold of a case: a lead of its school, or a holder of platform.moderation.manage. */
	boolean canHold(CurrentUser user, CaseRow row) {
		return isLead(user, row) || this.access.can(user, Permissions.PLATFORM_MODERATION_MANAGE, AccessScope.platform());
	}

	/** The grants of another user, to decide whether they may be assigned (their own queue access). */
	CurrentUser asUser(UUID userId) {
		return new CurrentUser(userId, null, null, this.roles.activeGrants(userId));
	}

}
