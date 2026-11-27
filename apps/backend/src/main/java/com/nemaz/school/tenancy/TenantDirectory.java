package com.nemaz.school.tenancy;

import java.util.List;
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

	/** Every campus of the school (any status) with its short code, by name. */
	List<CampusEntry> campusesOf(UUID schoolId);

	record OrganizationRef(UUID id, String countryCode, String name) {
	}

	/**
	 * {@code organizationId} is null for an independent school; {@code status} is the school's status name (ACTIVE,
	 * ...); {@code timezone} is the school's own IANA zone ({@code schools.timezone}).
	 */
	record SchoolRef(UUID id, String countryCode, UUID organizationId, String name, String slug, String status,
			String defaultLanguageCode, String timezone) {

		public boolean isActive() {
			return "ACTIVE".equals(this.status);
		}

	}

	/** A campus as a lookup row: {@code code} may be null, {@code status} is ACTIVE or ARCHIVED. */
	record CampusEntry(UUID id, String name, String code, String status) {
	}

	/** {@code status} is ACTIVE or ARCHIVED. */
	record CampusRef(UUID id, UUID schoolId, String name, String status) {
	}

}
