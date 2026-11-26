package com.nemaz.school.safeguarding.application;

import java.util.List;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.safeguarding.SafeguardingLeads;
import com.nemaz.school.tenancy.ScopeType;
import com.nemaz.school.tenancy.SettingChangeGuard;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Decision 3 of the rule book, enforced: a school cannot switch on student messaging ({@code messaging.student.*}) or student
 * contributions without a safeguarding lead; the settings change is refused with 409 {@code safeguarding.lead_required}
 * (en and am) until one is designated. "Switch on" means the effective value goes from off to on (or, for the group-creation
 * setting, to STUDENTS_WITH_APPROVAL). Switching it on above the school (organization, country, platform) is refused with
 * {@code safeguarding.lead_required_scope}: it would apply to schools that may have no lead, so it is done school by school.
 * The platform setting {@code safeguarding.require_lead_for_student_messaging} (platform and country scope only) can relax
 * the rule deliberately.
 */
@Component
class SafeguardingSettingsGuard implements SettingChangeGuard {

	static final List<String> GUARDED_KEYS = List.of("messaging.student.enabled", "messaging.student.teacher_direct.enabled",
			"messaging.student.student_direct.enabled", "messaging.student.group_create", "messaging.student.media_enabled",
			"feed.student_contributions.enabled");

	private final SafeguardingLeads leads;

	private final SettingsService settings;

	SafeguardingSettingsGuard(SafeguardingLeads leads, SettingsService settings) {
		this.leads = leads;
		this.settings = settings;
	}

	@Override
	public void check(Change change) {
		if (!GUARDED_KEYS.contains(change.key()) || !turnedOn(change)) {
			return;
		}
		SettingScope scope = change.scope();
		if (scope.type() == ScopeType.SCHOOL) {
			if (!requirementOn(scope) || this.leads.hasLead(scope.schoolId())) {
				return;
			}
			throw ApiException.conflict("safeguarding.lead_required");
		}
		if (scope.type() != ScopeType.PLATFORM || requirementOn(scope)) {
			throw ApiException.conflict("safeguarding.lead_required_scope");
		}
	}

	private boolean requirementOn(SettingScope scope) {
		return this.settings.getBoolean("safeguarding.require_lead_for_student_messaging", scope);
	}

	/** The effective value goes from "not on" to "on". */
	private static boolean turnedOn(Change change) {
		return on(change.key(), change.effectiveAfter()) && !on(change.key(), change.effectiveBefore());
	}

	private static boolean on(String key, JsonNode value) {
		if (value == null || value.isNull()) {
			return false;
		}
		if ("messaging.student.group_create".equals(key)) {
			return "STUDENTS_WITH_APPROVAL".equals(value.asString());
		}
		return value.isBoolean() && value.asBoolean();
	}

}
