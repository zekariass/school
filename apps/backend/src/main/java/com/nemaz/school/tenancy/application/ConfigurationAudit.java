package com.nemaz.school.tenancy.application;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.tenancy.SettingScope;

/** Audit entries for setting and feature-override changes, tagged with their scope. */
final class ConfigurationAudit {

	private ConfigurationAudit() {
	}

	static AuditEntry entry(String action, SettingScope scope) {
		AuditEntry entry = AuditEntry.of(action, AuditOutcome.SUCCESS).metadata("scopeType", scope.type().name());
		return switch (scope.type()) {
			case PLATFORM -> entry;
			case COUNTRY -> entry.metadata("countryCode", scope.countryCode());
			case ORGANIZATION -> entry.target("organization", scope.organizationId());
			case SCHOOL -> entry.target("school", scope.schoolId()).school(scope.schoolId());
		};
	}

}
