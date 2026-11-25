package com.nemaz.school.authorization;

import java.util.List;
import java.util.Map;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.security.Permissions;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The code-owned seed (R__40_roles_permissions.sql) agrees with common.security.Permissions. */
@IntegrationTest
class RolesAndPermissionsSeedTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void seededPermissionsAreExactlyThePermissionConstants() {
		assertThat(this.jdbc.queryForList("select code from permissions", String.class))
			.containsExactlyInAnyOrderElementsOf(Permissions.ALL);
	}

	@Test
	void systemRolesHaveTheirScopeLevels() {
		Map<String, String> levels = new java.util.HashMap<>();
		this.jdbc.query("select code, scope_level from roles where is_system",
				(rs) -> { levels.put(rs.getString(1), rs.getString(2)); });
		assertThat(levels).containsAllEntriesOf(Map.of("PLATFORM_ADMIN", "PLATFORM", "COUNTRY_ADMIN", "COUNTRY",
				"ORGANIZATION_ADMIN", "ORGANIZATION", "SCHOOL_ADMIN", "SCHOOL", "CAMPUS_ADMIN", "CAMPUS", "SCHOOL_STAFF",
				"SCHOOL", "PLATFORM_MODERATOR", "PLATFORM", "SUPPORT_AGENT", "PLATFORM", "SAFEGUARDING_LEAD", "SCHOOL"));
		assertThat(levels).hasSize(9);
	}

	@Test
	void platformAdministratorsHoldEveryPermissionAndNoOtherRoleHoldsPlatformOnlyOnes() {
		assertThat(permissionsOf("PLATFORM_ADMIN")).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
		for (String role : List.of("COUNTRY_ADMIN", "ORGANIZATION_ADMIN", "SCHOOL_ADMIN", "CAMPUS_ADMIN")) {
			assertThat(permissionsOf(role)).as(role)
				.doesNotContain(Permissions.PLATFORM_FEATURES_MANAGE, Permissions.PLATFORM_SUBSCRIPTIONS_MANAGE,
						Permissions.AUDIT_READ);
		}
		assertThat(permissionsOf("SCHOOL_ADMIN")).containsExactlyInAnyOrder(Permissions.SCHOOL_READ,
				Permissions.SCHOOL_MANAGE, Permissions.SCHOOL_SETTINGS_MANAGE, Permissions.SCHOOL_ACADEMIC_MANAGE,
				Permissions.SCHOOL_STAFF_MANAGE, Permissions.SCHOOL_STUDENTS_MANAGE, Permissions.CLASS_MANAGE,
				Permissions.SCHOOL_IMPORT_MANAGE);
		// A campus administrator reads the school and manages the classes of its own campus (M3a).
		assertThat(permissionsOf("CAMPUS_ADMIN")).containsExactlyInAnyOrder(Permissions.SCHOOL_READ,
				Permissions.CLASS_MANAGE);
		assertThat(permissionsOf("SCHOOL_STAFF")).containsExactly(Permissions.SCHOOL_READ);
	}

	@Test
	void everyAdministratorWhoCanGrantSchoolAdminHoldsItsPermissions() {
		// No escalation: granting SCHOOL_ADMIN needs every permission of the role at that scope.
		for (String role : List.of("COUNTRY_ADMIN", "ORGANIZATION_ADMIN")) {
			assertThat(permissionsOf(role)).as(role)
				.containsAll(permissionsOf("SCHOOL_ADMIN"));
		}
	}

	private List<String> permissionsOf(String role) {
		return this.jdbc.queryForList("select permission_code from role_permissions where role_code = ?", String.class,
				role);
	}

}
