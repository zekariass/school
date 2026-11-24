package com.nemaz.school.authorization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.application.RoleAssignmentService;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Granting and revoking roles: scope coverage, no escalation, immediate effect, audit. */
@IntegrationTest
class RoleAssignmentApiTest {

	private static final String URL = "/api/v1/platform/role-assignments";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	@Autowired
	RoleAssignmentService service;

	@Autowired
	RoleAssignments roleAssignments;

	@Autowired
	TransactionTemplate tx;

	private ResultActions grant(Persona as, UUID userId, String role, Map<String, Object> scope) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("userId", userId);
		body.put("roleCode", role);
		body.put("scope", scope);
		return this.mvc.perform(post(URL).with(as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(body)));
	}

	private static Map<String, Object> school(UUID id) {
		return Map.of("type", "SCHOOL", "schoolId", id);
	}

	@Test
	void grantedRolesTakeEffectAtOnceAndRevocationRemovesThem() throws Exception {
		Fixture f = this.personas.fixture();
		Persona platform = this.personas.platformAdmin();
		Persona newcomer = this.personas.newUser("newcomer");
		this.mvc.perform(get("/api/v1/platform/schools/" + f.schoolB()).with(newcomer.bearer()))
			.andExpect(status().isForbidden()); // also caches the newcomer's (empty) grants

		JsonNode created = this.json.readTree(grant(platform, newcomer.userId(), "SCHOOL_ADMIN", school(f.schoolB()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.grantedBy").value(platform.userId().toString()))
			.andExpect(jsonPath("$.scope.type").value("SCHOOL"))
			.andReturn()
			.getResponse()
			.getContentAsString());
		this.mvc.perform(get("/api/v1/platform/schools/" + f.schoolB()).with(newcomer.bearer()))
			.andExpect(status().isOk());

		this.mvc.perform(delete(URL + "/" + created.get("id").asString()).with(platform.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVOKED"))
			.andExpect(jsonPath("$.revokedAt").isString());
		this.mvc.perform(get("/api/v1/platform/schools/" + f.schoolB()).with(newcomer.bearer()))
			.andExpect(status().isForbidden());
		// revoking twice is harmless
		this.mvc.perform(delete(URL + "/" + created.get("id").asString()).with(platform.bearer()))
			.andExpect(status().isOk());

		assertThat(this.jdbc.queryForList(
				"select action from audit_logs where target_type = 'role_assignment' and target_id = ?::uuid order by id",
				String.class, created.get("id").asString()))
			.containsExactly("role_assignment.grant", "role_assignment.revoke");
	}

	@Test
	void nobodyGrantsAboveTheirScopeOrMoreThanTheyHold() throws Exception {
		Fixture f = this.personas.fixture();
		UUID target = this.personas.newUser("target").userId();
		Persona countryEt = this.personas.get(Name.COUNTRY_ADMIN_ET);
		Persona orgAdmin = this.personas.get(Name.ORG_ADMIN);

		grant(countryEt, target, "PLATFORM_ADMIN", Map.of("type", "PLATFORM")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("role.assignment_not_allowed"));
		grant(countryEt, target, "COUNTRY_ADMIN", Map.of("type", "COUNTRY", "countryCode", "KE"))
			.andExpect(status().isForbidden());
		grant(countryEt, target, "SCHOOL_ADMIN", school(f.schoolKe())).andExpect(status().isForbidden());
		grant(orgAdmin, target, "COUNTRY_ADMIN", Map.of("type", "COUNTRY", "countryCode", "ET"))
			.andExpect(status().isForbidden());
		grant(orgAdmin, target, "SCHOOL_ADMIN", school(f.schoolKe())).andExpect(status().isForbidden());
		grant(this.personas.get(Name.SCHOOL_ADMIN_A), target, "CAMPUS_ADMIN", Map.of("type", "CAMPUS", "campusId", f.campusA()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("forbidden"));
		// a role the grantor does not fully hold, even inside their scope (PLATFORM_ADMIN-only permissions)
		this.jdbc.update("""
				insert into roles (code, name_i18n, scope_level, is_system) values ('TEST_SCHOOL_AUDITOR', '{"en":"x"}', 'SCHOOL', false)
				on conflict do nothing""");
		this.jdbc.update("insert into role_permissions values ('TEST_SCHOOL_AUDITOR', 'audit.read') on conflict do nothing");
		grant(countryEt, target, "TEST_SCHOOL_AUDITOR", school(f.schoolA())).andExpect(status().isForbidden());

		grant(orgAdmin, target, "SCHOOL_ADMIN", school(f.schoolA())).andExpect(status().isCreated());
		grant(orgAdmin, target, "CAMPUS_ADMIN", Map.of("type", "CAMPUS", "campusId", f.campusA()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.scope.schoolId").value(f.schoolA().toString()));
		grant(countryEt, target, "ORGANIZATION_ADMIN", Map.of("type", "ORGANIZATION", "organizationId", f.organization()))
			.andExpect(status().isCreated());
		grant(countryEt, target, "COUNTRY_ADMIN", Map.of("type", "COUNTRY", "countryCode", "et"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.scope.countryCode").value("ET"));
	}

	@Test
	void grantValidation() throws Exception {
		Fixture f = this.personas.fixture();
		Persona platform = this.personas.platformAdmin();
		UUID target = this.personas.newUser("validation-target").userId();
		grant(platform, target, "SCHOOL_ADMIN", Map.of("type", "COUNTRY", "countryCode", "ET"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("role.scope_mismatch"));
		grant(platform, target, "NO_SUCH_ROLE", school(f.schoolA())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("role.unknown"));
		grant(platform, target, "SCHOOL_ADMIN", Map.of("type", "SCHOOL", "countryCode", "ET"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("scope.invalid"));
		grant(platform, target, "SCHOOL_ADMIN", school(UUID.randomUUID())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("role.scope_not_found"));
		grant(platform, target, "COUNTRY_ADMIN", Map.of("type", "COUNTRY", "countryCode", "ZZ"))
			.andExpect(status().isNotFound());
		grant(platform, UUID.randomUUID(), "SCHOOL_ADMIN", school(f.schoolA())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("user.not_found"));
		grant(platform, target, "SCHOOL_ADMIN", school(f.schoolA())).andExpect(status().isCreated());
		grant(platform, target, "SCHOOL_ADMIN", school(f.schoolA())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("role.assignment_exists"));
		this.mvc.perform(post(URL).with(platform.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors", hasSize(3)));
		this.mvc.perform(delete(URL + "/" + UUID.randomUUID()).with(platform.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("role.assignment_not_found"));
	}

	@Test
	void listsOnlyWhatTheCallerMayManage() throws Exception {
		Fixture f = this.personas.fixture();
		UUID schoolAdminA = this.personas.get(Name.SCHOOL_ADMIN_A).userId();
		this.mvc.perform(get(URL + "?userId=" + schoolAdminA).with(this.personas.platformAdmin().bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].roleCode").value("SCHOOL_ADMIN"));
		this.mvc.perform(get(URL + "?userId=" + schoolAdminA).with(this.personas.get(Name.ORG_ADMIN).bearer()))
			.andExpect(jsonPath("$.items", hasSize(1)));
		this.mvc.perform(get(URL + "?userId=" + schoolAdminA).with(this.personas.get(Name.COUNTRY_ADMIN_KE).bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)));
		this.mvc.perform(get(URL + "?scopeType=SCHOOL&schoolId=" + f.schoolA())
			.with(this.personas.get(Name.COUNTRY_ADMIN_ET).bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.userId == '" + schoolAdminA + "')]", hasSize(1)));
		this.mvc.perform(get(URL + "?scopeType=SCHOOL&schoolId=" + f.schoolA())
			.with(this.personas.get(Name.COUNTRY_ADMIN_KE).bearer()))
			.andExpect(status().isForbidden());
		this.mvc.perform(get(URL).with(this.personas.platformAdmin().bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("role.filter_required"));
		this.mvc.perform(get(URL + "?userId=" + schoolAdminA).with(this.personas.get(Name.SCHOOL_ADMIN_A).bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	void theLastPlatformAdministratorCannotBeRevoked() {
		Persona platform = this.personas.platformAdmin();
		CurrentUser actor = new CurrentUser(platform.userId(), UUID.randomUUID(), null,
				this.roleAssignments.activeGrants(platform.userId()));
		this.tx.executeWithoutResult((status) -> {
			status.setRollbackOnly();
			UUID keep = this.jdbc.queryForObject(
					"select id from role_assignments where user_id = ? and role_code = 'PLATFORM_ADMIN' and status = 'ACTIVE'",
					UUID.class, platform.userId());
			this.jdbc.update("update role_assignments set status = 'REVOKED', revoked_at = now() "
					+ "where role_code = 'PLATFORM_ADMIN' and status = 'ACTIVE' and id <> ?", keep);
			assertThatThrownBy(() -> this.service.revoke(actor, keep)).isInstanceOfSatisfying(ApiException.class,
					(ex) -> assertThat(ex.code()).isEqualTo("role.last_platform_admin"));
		});
		// a second platform administrator can be removed
		Persona second = this.personas.withRole("second-platform-admin", RoleCodes.PLATFORM_ADMIN, AccessScope.platform());
		UUID id = this.jdbc.queryForObject("select id from role_assignments where user_id = ?", UUID.class,
				second.userId());
		assertThat(this.service.revoke(actor, id).getStatus().name()).isEqualTo("REVOKED");
	}

}
