package com.nemaz.school.tenancy.application;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.infrastructure.CampusRepository;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultTenantDirectory implements TenantDirectory {

	private final OrganizationRepository organizations;

	private final SchoolRepository schools;

	private final CampusRepository campuses;

	DefaultTenantDirectory(OrganizationRepository organizations, SchoolRepository schools, CampusRepository campuses) {
		this.organizations = organizations;
		this.schools = schools;
		this.campuses = campuses;
	}

	@Override
	public Optional<OrganizationRef> organization(UUID organizationId) {
		if (organizationId == null) {
			return Optional.empty();
		}
		return this.organizations.findById(organizationId)
			.map((o) -> new OrganizationRef(o.getId(), o.getCountryCode(), o.getName()));
	}

	@Override
	public Optional<SchoolRef> school(UUID schoolId) {
		if (schoolId == null) {
			return Optional.empty();
		}
		return this.schools.findById(schoolId)
			.map((s) -> new SchoolRef(s.getId(), s.getCountryCode(), s.getOrganizationId(), s.getName(), s.getSlug()));
	}

	@Override
	public Optional<CampusRef> campus(UUID campusId) {
		if (campusId == null) {
			return Optional.empty();
		}
		return this.campuses.findById(campusId).map((c) -> new CampusRef(c.getId(), c.getSchoolId(), c.getName()));
	}

}
