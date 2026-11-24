package com.nemaz.school.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the tenancy hierarchy for other modules: which country and organization a
 * school belongs to, which school a campus belongs to, and display names. The authorization
 * module resolves scope coverage with it (a country covers its schools, an organization its
 * schools, a school its campuses).
 */
public interface TenantDirectory {

	Optional<OrganizationRef> organization(UUID organizationId);

	Optional<SchoolRef> school(UUID schoolId);

	Optional<CampusRef> campus(UUID campusId);

	record OrganizationRef(UUID id, String countryCode, String name) {
	}

	/** {@code organizationId} is null for an independent school. */
	record SchoolRef(UUID id, String countryCode, UUID organizationId, String name) {
	}

	record CampusRef(UUID id, UUID schoolId, String name) {
	}

}
