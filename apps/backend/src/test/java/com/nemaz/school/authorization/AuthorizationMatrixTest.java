package com.nemaz.school.authorization;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.nemaz.school.support.Personas.Name.CAMPUS_ADMIN_A;
import static com.nemaz.school.support.Personas.Name.COUNTRY_ADMIN_ET;
import static com.nemaz.school.support.Personas.Name.COUNTRY_ADMIN_KE;
import static com.nemaz.school.support.Personas.Name.ORG_ADMIN;
import static com.nemaz.school.support.Personas.Name.PLAIN_USER;
import static com.nemaz.school.support.Personas.Name.PLATFORM_ADMIN;
import static com.nemaz.school.support.Personas.Name.SCHOOL_ADMIN_A;
import static com.nemaz.school.support.Personas.Name.SCHOOL_ADMIN_B;
import static com.nemaz.school.support.Personas.Name.SUSPENDED_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every platform/admin endpoint against every persona (Implementation Plan, definition of
 * done 3): the listed personas get the given success status, every other signed-in persona
 * gets 403, the suspended user and anonymous callers get 401.
 *
 * <p>
 * <b>Adding an endpoint:</b> add a {@link Case} to {@link #cases()} with the personas allowed
 * and their status. {@code url} and {@code body} are functions of the fixture and run once per
 * call, so they can create what the call needs (unique slugs, a fresh role assignment, a
 * setting to delete). <b>Adding a persona:</b> add it to {@link Personas.Name}; it is then
 * expected to get 403 everywhere until a case allows it.
 */
@IntegrationTest
class AuthorizationMatrixTest {

	private static final String PLATFORM = "/api/v1/platform";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	@Autowired
	CacheManager caches;

	@AfterEach
	void removeConfigurationWrittenByTheMatrix() {
		this.jdbc.update("delete from settings");
		this.jdbc.update("delete from feature_overrides");
		this.caches.getCache("school-configuration").clear();
	}

	/** An endpoint call and who may make it. */
	record Case(String name, HttpMethod method, Function<Fixture, String> url, Function<Fixture, Object> body,
			Map<Name, Integer> allowed) {
	}

	private static Map<Name, Integer> allow(int status, Name... names) {
		Map<Name, Integer> allowed = new EnumMap<>(Name.class);
		for (Name name : names) {
			allowed.put(name, status);
		}
		return allowed;
	}

	private static final Name[] EVERY_ACTIVE_PERSONA = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN,
			SCHOOL_ADMIN_A, SCHOOL_ADMIN_B, CAMPUS_ADMIN_A, PLAIN_USER };

	private static final Name[] READERS_OF_SCHOOL_A = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A,
			CAMPUS_ADMIN_A };

	private static final Name[] MANAGERS_OF_SCHOOL_A = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A };

	List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		// --- account and reference data: any signed-in user
		cases.add(new Case("GET /me", HttpMethod.GET, (f) -> "/api/v1/me", null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET /reference/countries", HttpMethod.GET, (f) -> "/api/v1/reference/countries", null,
				allow(200, EVERY_ACTIVE_PERSONA)));

		// --- organizations: platform.organizations.manage (platform, country)
		cases.add(new Case("GET organizations", HttpMethod.GET, (f) -> PLATFORM + "/organizations", null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE)));
		cases.add(new Case("POST organization in ET", HttpMethod.POST, (f) -> PLATFORM + "/organizations",
				(f) -> Map.of("countryCode", "ET", "name", "Matrix org", "slug", Personas.newSlug("matrix-org")),
				allow(201, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("GET organization", HttpMethod.GET, (f) -> PLATFORM + "/organizations/" + f.organization(),
				null, allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("PUT organization", HttpMethod.PUT, (f) -> PLATFORM + "/organizations/" + f.organization(),
				(f) -> Map.of("name", "Persona Org", "status", "ACTIVE"), allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));

		// --- schools
		cases.add(new Case("GET schools (filtered)", HttpMethod.GET, (f) -> PLATFORM + "/schools", null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN, SCHOOL_ADMIN_A, SCHOOL_ADMIN_B,
						CAMPUS_ADMIN_A)));
		cases.add(new Case("POST school in the organization", HttpMethod.POST, (f) -> PLATFORM + "/schools",
				(f) -> Map.of("countryCode", "ET", "organizationId", f.organization(), "name", "Matrix school", "slug",
						Personas.newSlug("matrix-school")),
				allow(201, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN)));
		cases.add(new Case("POST independent school in ET", HttpMethod.POST, (f) -> PLATFORM + "/schools",
				(f) -> Map.of("countryCode", "ET", "name", "Matrix school", "slug", Personas.newSlug("matrix-school")),
				allow(201, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("GET school A", HttpMethod.GET, (f) -> PLATFORM + "/schools/" + f.schoolA(), null,
				allow(200, READERS_OF_SCHOOL_A)));
		cases.add(new Case("GET school KE", HttpMethod.GET, (f) -> PLATFORM + "/schools/" + f.schoolKe(), null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_KE)));
		cases.add(new Case("GET unknown school", HttpMethod.GET, (f) -> PLATFORM + "/schools/" + UUID.randomUUID(), null,
				allow(404, PLATFORM_ADMIN)));
		cases.add(new Case("PUT school A status", HttpMethod.PUT, (f) -> PLATFORM + "/schools/" + f.schoolA() + "/status",
				(f) -> Map.of("status", "ACTIVE"), allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN)));
		cases.add(new Case("GET school A campuses", HttpMethod.GET,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/campuses", null, allow(200, READERS_OF_SCHOOL_A)));
		cases.add(new Case("POST campus in school A", HttpMethod.POST,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/campuses", (f) -> Map.of("name", "Matrix campus"),
				allow(201, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("PUT campus A", HttpMethod.PUT,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/campuses/" + f.campusA(),
				(f) -> Map.of("name", "Campus A", "code", "A", "status", "ACTIVE"), allow(200, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("PUT school A subscription", HttpMethod.PUT,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/subscription", (f) -> Map.of("planCode", "SCHOOL_BASIC"),
				allow(200, PLATFORM_ADMIN)));
		cases.add(new Case("GET school A configuration", HttpMethod.GET,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/configuration", null, allow(200, READERS_OF_SCHOOL_A)));

		// --- feature overrides: platform administrators only
		cases.add(new Case("GET platform feature overrides", HttpMethod.GET,
				(f) -> PLATFORM + "/feature-overrides?scopeType=PLATFORM", null, allow(200, PLATFORM_ADMIN)));
		cases.add(new Case("PUT ET feature override", HttpMethod.PUT, (f) -> PLATFORM + "/feature-overrides",
				(f) -> Map.of("featureKey", "feed", "scope", Map.of("type", "COUNTRY", "countryCode", "ET"), "enabled", true),
				allow(200, PLATFORM_ADMIN)));
		cases.add(new Case("DELETE platform feature override", HttpMethod.DELETE, (f) -> {
			this.jdbc.update("insert into feature_overrides (feature_key, scope_type, enabled) values ('feed', 'PLATFORM', true) "
					+ "on conflict do nothing");
			return PLATFORM + "/feature-overrides?featureKey=feed&scopeType=PLATFORM";
		}, null, allow(204, PLATFORM_ADMIN)));

		// --- settings: platform.settings.manage covering the scope, or school.settings.manage for a school
		cases.add(new Case("GET platform settings", HttpMethod.GET, (f) -> PLATFORM + "/settings?scopeType=PLATFORM",
				null, allow(200, PLATFORM_ADMIN)));
		cases.add(new Case("GET organization settings", HttpMethod.GET,
				(f) -> PLATFORM + "/settings?scopeType=ORGANIZATION&organizationId=" + f.organization(), null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("PUT ET setting", HttpMethod.PUT, (f) -> PLATFORM + "/settings",
				(f) -> Map.of("key", "engagement.badges.enabled", "scope", Map.of("type", "COUNTRY", "countryCode", "ET"),
						"value", true),
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("PUT school A setting", HttpMethod.PUT, (f) -> PLATFORM + "/settings",
				(f) -> Map.of("key", "engagement.badges.enabled", "scope", Map.of("type", "SCHOOL", "schoolId", f.schoolA()),
						"value", true),
				allow(200, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("DELETE school A setting", HttpMethod.DELETE, (f) -> {
			this.jdbc.update("insert into settings (key, scope_type, school_id, value) "
					+ "values ('engagement.badges.enabled', 'SCHOOL', ?, 'true') on conflict do nothing", f.schoolA());
			return PLATFORM + "/settings?key=engagement.badges.enabled&scopeType=SCHOOL&schoolId=" + f.schoolA();
		}, null, allow(204, MANAGERS_OF_SCHOOL_A)));

		// --- role assignments: platform.roles.manage covering the scope, never above it
		cases.add(new Case("GET role assignments of a user", HttpMethod.GET,
				(f) -> PLATFORM + "/role-assignments?userId=" + this.personas.get(SCHOOL_ADMIN_A).userId(), null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN)));
		cases.add(new Case("POST SCHOOL_ADMIN of school A", HttpMethod.POST, (f) -> PLATFORM + "/role-assignments",
				(f) -> Map.of("userId", this.personas.newUser("matrix-grantee").userId(), "roleCode", "SCHOOL_ADMIN", "scope",
						Map.of("type", "SCHOOL", "schoolId", f.schoolA())),
				allow(201, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN)));
		cases.add(new Case("POST PLATFORM_ADMIN", HttpMethod.POST, (f) -> PLATFORM + "/role-assignments",
				(f) -> Map.of("userId", this.personas.newUser("matrix-grantee").userId(), "roleCode", "PLATFORM_ADMIN",
						"scope", Map.of("type", "PLATFORM")),
				allow(201, PLATFORM_ADMIN)));
		cases.add(new Case("DELETE a school A role assignment", HttpMethod.DELETE, (f) -> {
			UUID user = this.personas.newUser("matrix-revokee").userId();
			this.personas.grant(user, "CAMPUS_ADMIN", AccessScope.campus(f.schoolA(), f.campusA()));
			UUID id = this.jdbc.queryForObject("select id from role_assignments where user_id = ?", UUID.class, user);
			return PLATFORM + "/role-assignments/" + id;
		}, null, allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN)));

		// --- audit log: platform only
		cases.add(new Case("GET audit logs", HttpMethod.GET, (f) -> PLATFORM + "/audit-logs?limit=5", null,
				allow(200, PLATFORM_ADMIN)));
		return cases;
	}

	@TestFactory
	Stream<DynamicTest> everyEndpointAllowsExactlyTheExpectedPersonas() {
		Fixture fixture = this.personas.fixture();
		return cases().stream().map((c) -> DynamicTest.dynamicTest(c.name(), () -> {
			List<String> mismatches = new ArrayList<>();
			Map<Name, Integer> actual = new HashMap<>();
			for (Name name : Name.values()) {
				int expected = (name == SUSPENDED_USER) ? 401 : c.allowed().getOrDefault(name, 403);
				int status = call(c, fixture, this.personas.get(name));
				actual.put(name, status);
				if (status != expected) {
					mismatches.add(name + ": expected " + expected + " but was " + status);
				}
			}
			int anonymous = call(c, fixture, null);
			if (anonymous != 401) {
				mismatches.add("anonymous: expected 401 but was " + anonymous);
			}
			assertThat(mismatches).as(c.name() + " " + actual).isEmpty();
		}));
	}

	private int call(Case c, Fixture fixture, Persona persona) throws Exception {
		MockHttpServletRequestBuilder builder = request(c.method(), c.url().apply(fixture));
		if (c.body() != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON)
				.content(this.json.writeValueAsString(c.body().apply(fixture)));
		}
		if (persona != null) {
			builder = builder.with(persona.bearer());
		}
		return this.mvc.perform(builder).andReturn().getResponse().getStatus();
	}

}
