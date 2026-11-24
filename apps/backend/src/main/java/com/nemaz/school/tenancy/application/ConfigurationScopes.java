package com.nemaz.school.tenancy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.domain.Organization;
import com.nemaz.school.tenancy.domain.School;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.stereotype.Component;

/**
 * Turns a scope into its precedence chain, most specific first: a school looks at its
 * organization (if any), then its country, then the platform. Also checks that the scope
 * exists, so writes cannot reference a school, organization or country that is not there.
 */
@Component
class ConfigurationScopes {

	private final SchoolRepository schools;

	private final OrganizationRepository organizations;

	private final ReferenceData reference;

	ConfigurationScopes(SchoolRepository schools, OrganizationRepository organizations, ReferenceData reference) {
		this.schools = schools;
		this.organizations = organizations;
		this.reference = reference;
	}

	List<SettingScope> chain(SettingScope scope) {
		List<SettingScope> chain = new ArrayList<>(4);
		switch (scope.type()) {
			case SCHOOL -> {
				School school = this.schools.findById(scope.schoolId())
					.orElseThrow(() -> ApiException.notFound("school.not_found"));
				chain.add(scope);
				if (school.getOrganizationId() != null) {
					chain.add(SettingScope.organization(school.getOrganizationId()));
				}
				chain.add(SettingScope.country(school.getCountryCode()));
				chain.add(SettingScope.platform());
			}
			case ORGANIZATION -> {
				Organization organization = this.organizations.findById(scope.organizationId())
					.orElseThrow(() -> ApiException.notFound("organization.not_found"));
				chain.add(scope);
				chain.add(SettingScope.country(organization.getCountryCode()));
				chain.add(SettingScope.platform());
			}
			case COUNTRY -> {
				if (this.reference.findCountry(scope.countryCode()).isEmpty()) {
					throw ApiException.notFound("country.not_found");
				}
				chain.add(scope);
				chain.add(SettingScope.platform());
			}
			case PLATFORM -> chain.add(scope);
		}
		return chain;
	}

	/** The school's chain, resolved from the id alone. */
	List<SettingScope> chainOfSchool(UUID schoolId) {
		return chain(SettingScope.school(schoolId));
	}

}
