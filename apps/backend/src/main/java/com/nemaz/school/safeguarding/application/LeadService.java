package com.nemaz.school.safeguarding.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.safeguarding.application.SafeguardingModels.CapabilityView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LeadView;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Designating the school's safeguarding leads (docs/Safeguarding_Rules.md S4.7, decision 3): the school administrator
 * appoints an ACTIVE staff member (the principal may be one) through the role SAFEGUARDING_LEAD; the capability view tells the
 * screens why student messaging cannot be switched on yet. The last lead cannot be removed while the school has student
 * features on (designate the next one first).
 */
@Service
public class LeadService {

	private final LeadDirectory leads;

	private final RoleAssignments roles;

	private final SchoolGate gate;

	private final SettingsService settings;

	private final AuditLog audit;

	LeadService(LeadDirectory leads, RoleAssignments roles, SchoolGate gate, SettingsService settings, AuditLog audit) {
		this.leads = leads;
		this.roles = roles;
		this.gate = gate;
		this.settings = settings;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<LeadView> list(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		return this.leads.views(schoolId);
	}

	@Transactional
	public LeadView designate(CurrentUser user, UUID schoolId, UUID userId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		if (!this.leads.isActiveStaff(userId, schoolId)) {
			throw ApiException.badRequest("safeguarding.lead_must_be_staff");
		}
		if (!this.leads.leadsOf(schoolId).contains(userId)) {
			this.roles.grantAsSystem(userId, RoleCodes.SAFEGUARDING_LEAD, AccessScope.school(schoolId));
			this.audit.record(AuditEntry.of("safeguarding.lead.designate", AuditOutcome.SUCCESS)
				.target("user", userId)
				.school(schoolId));
		}
		return this.leads.views(schoolId).stream().filter((l) -> l.userId().equals(userId)).findFirst().orElseThrow();
	}

	@Transactional
	public void remove(CurrentUser user, UUID schoolId, UUID userId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		if (!this.leads.leadsOf(schoolId).contains(userId)) {
			throw ApiException.notFound("safeguarding.lead_not_found");
		}
		if (this.leads.leadsOf(schoolId).size() == 1 && studentFeaturesOn(schoolId)) {
			throw ApiException.conflict("safeguarding.last_lead_required");
		}
		this.roles.revokeAsSystem(userId, RoleCodes.SAFEGUARDING_LEAD, AccessScope.school(schoolId));
		this.audit.record(AuditEntry.of("safeguarding.lead.remove", AuditOutcome.SUCCESS)
			.target("user", userId)
			.school(schoolId));
	}

	@Transactional(readOnly = true)
	public CapabilityView capability(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_SETTINGS_MANAGE);
		int count = this.leads.leadsOf(schoolId).size();
		boolean messaging = messagingOn(schoolId);
		boolean contributions = contributionsOn(schoolId);
		return new CapabilityView(count > 0, count, requirementOn(schoolId), SafeguardingSettingsGuard.GUARDED_KEYS, messaging,
				contributions, count == 0 && (messaging || contributions));
	}

	boolean requirementOn(UUID schoolId) {
		return this.settings.getBoolean("safeguarding.require_lead_for_student_messaging", SettingScope.school(schoolId));
	}

	private boolean messagingOn(UUID schoolId) {
		return this.settings.getBoolean("messaging.student.enabled", SettingScope.school(schoolId));
	}

	private boolean contributionsOn(UUID schoolId) {
		return this.settings.getBoolean("feed.student_contributions.enabled", SettingScope.school(schoolId));
	}

	private boolean studentFeaturesOn(UUID schoolId) {
		return requirementOn(schoolId) && (messagingOn(schoolId) || contributionsOn(schoolId));
	}

}
