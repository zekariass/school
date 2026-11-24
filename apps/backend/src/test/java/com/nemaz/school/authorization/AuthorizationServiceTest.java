package com.nemaz.school.authorization;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Scope coverage of AccessControl over the persona fixture. */
@IntegrationTest
class AuthorizationServiceTest {

	@Autowired
	AccessControl access;

	@Autowired
	RoleAssignments roleAssignments;

	@Autowired
	Personas personas;

	private CurrentUser user(Name name) {
		UUID id = this.personas.get(name).userId();
		return new CurrentUser(id, UUID.randomUUID(), null, this.roleAssignments.activeGrants(id));
	}

	@Test
	void coverageFollowsTheTenancyHierarchy() {
		Fixture f = this.personas.fixture();
		AccessScope platform = AccessScope.platform();
		AccessScope et = AccessScope.country("ET");
		AccessScope org = AccessScope.organization(f.organization());
		AccessScope a = AccessScope.school(f.schoolA());
		AccessScope b = AccessScope.school(f.schoolB());
		AccessScope ke = AccessScope.school(f.schoolKe());
		AccessScope campus = AccessScope.campus(f.campusA());
		String manage = Permissions.SCHOOL_MANAGE;

		CurrentUser platformAdmin = user(Name.PLATFORM_ADMIN);
		assertThat(this.access.can(platformAdmin, manage, platform)).isTrue();
		assertThat(this.access.can(platformAdmin, manage, AccessScope.school(UUID.randomUUID()))).isTrue();

		CurrentUser countryEt = user(Name.COUNTRY_ADMIN_ET);
		assertThat(this.access.can(countryEt, manage, et)).isTrue();
		assertThat(this.access.can(countryEt, manage, org)).isTrue();
		assertThat(this.access.can(countryEt, manage, a)).isTrue();
		assertThat(this.access.can(countryEt, manage, campus)).isTrue();
		assertThat(this.access.can(countryEt, manage, ke)).isFalse();
		assertThat(this.access.can(countryEt, manage, platform)).isFalse();
		assertThat(this.access.can(countryEt, manage, AccessScope.country("KE"))).isFalse();
		assertThat(this.access.can(countryEt, Permissions.PLATFORM_FEATURES_MANAGE, et)).isFalse();

		CurrentUser orgAdmin = user(Name.ORG_ADMIN);
		assertThat(this.access.can(orgAdmin, manage, a)).isTrue();
		assertThat(this.access.can(orgAdmin, manage, b)).isTrue();
		assertThat(this.access.can(orgAdmin, manage, campus)).isTrue();
		assertThat(this.access.can(orgAdmin, manage, ke)).isFalse();
		assertThat(this.access.can(orgAdmin, manage, et)).isFalse();

		CurrentUser schoolA = user(Name.SCHOOL_ADMIN_A);
		assertThat(this.access.can(schoolA, manage, a)).isTrue();
		assertThat(this.access.can(schoolA, manage, campus)).isTrue();
		assertThat(this.access.can(schoolA, manage, b)).isFalse();
		assertThat(this.access.can(schoolA, manage, org)).isFalse();
		assertThat(this.access.can(schoolA, manage, AccessScope.school(UUID.randomUUID()))).isFalse();

		CurrentUser campusA = user(Name.CAMPUS_ADMIN_A);
		assertThat(this.access.can(campusA, Permissions.SCHOOL_READ, campus)).isTrue();
		assertThat(this.access.can(campusA, Permissions.SCHOOL_READ, a)).isTrue(); // sees its school
		assertThat(this.access.can(campusA, Permissions.SCHOOL_READ, b)).isFalse();
		assertThat(this.access.can(campusA, manage, campus)).isFalse();
		assertThat(this.access.can(campusA, manage, a)).isFalse();

		CurrentUser plain = user(Name.PLAIN_USER);
		assertThat(this.access.can(plain, Permissions.SCHOOL_READ, a)).isFalse();
		assertThat(this.access.canAnywhere(plain, Permissions.SCHOOL_READ)).isFalse();
		assertThat(this.access.can(null, Permissions.SCHOOL_READ, a)).isFalse();
	}

	@Test
	void accessibleScopesListWhereAPermissionIsHeld() {
		Fixture f = this.personas.fixture();
		AccessibleScopes country = this.access.accessible(user(Name.COUNTRY_ADMIN_KE), Permissions.SCHOOL_READ);
		assertThat(country.platform()).isFalse();
		assertThat(country.countryCodes()).containsExactly("KE");
		AccessibleScopes campus = this.access.accessible(user(Name.CAMPUS_ADMIN_A), Permissions.SCHOOL_READ);
		assertThat(campus.campusIds()).containsExactly(f.campusA());
		assertThat(campus.schoolIds()).containsExactly(f.schoolA());
		assertThat(this.access.accessible(user(Name.PLATFORM_ADMIN), Permissions.AUDIT_READ).platform()).isTrue();
		assertThat(this.access.accessible(user(Name.SCHOOL_ADMIN_A), Permissions.AUDIT_READ).isEmpty()).isTrue();
	}

}
