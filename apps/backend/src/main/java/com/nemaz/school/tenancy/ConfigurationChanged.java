package com.nemaz.school.tenancy;

import java.util.UUID;

/**
 * A setting or feature override changed at {@code scopeType}. Exactly the key of that
 * scope is set. {@code kind} is SETTING or FEATURE.
 */
public record ConfigurationChanged(ScopeType scopeType, String countryCode, UUID organizationId, UUID schoolId,
		String kind, String key) {

	public static final String SETTING = "SETTING";

	public static final String FEATURE = "FEATURE";

	public static ConfigurationChanged of(SettingScope scope, String kind, String key) {
		return new ConfigurationChanged(scope.type(), scope.countryCode(), scope.organizationId(), scope.schoolId(),
				kind, key);
	}

}
