package com.nemaz.school.tenancy;

import java.util.UUID;

/**
 * A level of the configuration hierarchy: the whole platform, one country, one
 * organization or one school. Build one with the factories; only the key of its own level
 * is set. When a school scope is resolved, the service looks up the school's organization
 * and country itself, so callers only ever need the school id.
 */
public record SettingScope(ScopeType type, String countryCode, UUID organizationId, UUID schoolId) {

	public SettingScope {
		boolean valid = switch (type) {
			case PLATFORM -> countryCode == null && organizationId == null && schoolId == null;
			case COUNTRY -> countryCode != null && organizationId == null && schoolId == null;
			case ORGANIZATION -> countryCode == null && organizationId != null && schoolId == null;
			case SCHOOL -> countryCode == null && organizationId == null && schoolId != null;
		};
		if (!valid) {
			throw new IllegalArgumentException("Scope " + type + " has the wrong keys set");
		}
	}

	public static SettingScope platform() {
		return new SettingScope(ScopeType.PLATFORM, null, null, null);
	}

	public static SettingScope country(String countryCode) {
		return new SettingScope(ScopeType.COUNTRY, countryCode, null, null);
	}

	public static SettingScope organization(UUID organizationId) {
		return new SettingScope(ScopeType.ORGANIZATION, null, organizationId, null);
	}

	public static SettingScope school(UUID schoolId) {
		return new SettingScope(ScopeType.SCHOOL, null, null, schoolId);
	}

}
