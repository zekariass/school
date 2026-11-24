package com.nemaz.school.common.security;

import java.util.Locale;
import java.util.UUID;

/**
 * A node of the tenancy hierarchy that a permission is held at or checked against:
 * the platform, one country, one organization, one school or one campus. Exactly the key of
 * its level is set, except that a campus also carries its school when known (role
 * assignments store both). Build one with the factories.
 *
 * <p>
 * Coverage (who covers whom) is resolved by the authorization module: PLATFORM covers
 * everything, a COUNTRY its organizations and schools, an ORGANIZATION its schools, a SCHOOL
 * its campuses, a CAMPUS itself.
 */
public record AccessScope(ScopeLevel level, String countryCode, UUID organizationId, UUID schoolId, UUID campusId) {

	private static final AccessScope PLATFORM = new AccessScope(ScopeLevel.PLATFORM, null, null, null, null);

	public AccessScope {
		if (level == null) {
			throw new IllegalArgumentException("Scope level is required");
		}
		if (countryCode != null) {
			countryCode = countryCode.toUpperCase(Locale.ROOT);
		}
		boolean valid = switch (level) {
			case PLATFORM -> countryCode == null && organizationId == null && schoolId == null && campusId == null;
			case COUNTRY -> countryCode != null && organizationId == null && schoolId == null && campusId == null;
			case ORGANIZATION -> countryCode == null && organizationId != null && schoolId == null && campusId == null;
			case SCHOOL -> countryCode == null && organizationId == null && schoolId != null && campusId == null;
			case CAMPUS -> countryCode == null && organizationId == null && campusId != null;
		};
		if (!valid) {
			throw new IllegalArgumentException("Scope " + level + " has the wrong keys set");
		}
	}

	public static AccessScope platform() {
		return PLATFORM;
	}

	public static AccessScope country(String countryCode) {
		return new AccessScope(ScopeLevel.COUNTRY, countryCode, null, null, null);
	}

	public static AccessScope organization(UUID organizationId) {
		return new AccessScope(ScopeLevel.ORGANIZATION, null, organizationId, null, null);
	}

	public static AccessScope school(UUID schoolId) {
		return new AccessScope(ScopeLevel.SCHOOL, null, null, schoolId, null);
	}

	/** A campus; its school is resolved when needed. */
	public static AccessScope campus(UUID campusId) {
		return new AccessScope(ScopeLevel.CAMPUS, null, null, null, campusId);
	}

	public static AccessScope campus(UUID schoolId, UUID campusId) {
		return new AccessScope(ScopeLevel.CAMPUS, null, null, schoolId, campusId);
	}

}
