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
import com.nemaz.school.common.id.Ids;
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
import static com.nemaz.school.support.Personas.Name.GUARDIAN_A1;
import static com.nemaz.school.support.Personas.Name.GUARDIAN_OF_A2;
import static com.nemaz.school.support.Personas.Name.GUARDIAN_PENDING_A1;
import static com.nemaz.school.support.Personas.Name.GUARDIAN_RESTRICTED_A1;
import static com.nemaz.school.support.Personas.Name.GUARDIAN_REVOKED_A1;
import static com.nemaz.school.support.Personas.Name.UNRELATED_ADULT;
import static com.nemaz.school.support.Personas.Name.ORG_ADMIN;
import static com.nemaz.school.support.Personas.Name.PLAIN_USER;
import static com.nemaz.school.support.Personas.Name.STUDENT_A1;
import static com.nemaz.school.support.Personas.Name.STUDENT_A2;
import static com.nemaz.school.support.Personas.Name.STUDENT_B1;
import static com.nemaz.school.support.Personas.Name.STUDENT_FORMER_A;
import static com.nemaz.school.support.Personas.Name.TEACHER_LEAD_A;
import static com.nemaz.school.support.Personas.Name.TEACHER_OTHER_A;
import static com.nemaz.school.support.Personas.Name.TEACHER_SUBJECT_A;
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
			Map<Name, Integer> allowed, int denied, int anonymous) {

		/** Personas not listed are answered {@code denied}; anonymous callers (and the suspended user) 401. */
		Case(String name, HttpMethod method, Function<Fixture, String> url, Function<Fixture, Object> body,
				Map<Name, Integer> allowed, int denied) {
			this(name, method, url, body, allowed, denied, 401);
		}

		/** Personas not listed are answered 403. */
		Case(String name, HttpMethod method, Function<Fixture, String> url, Function<Fixture, Object> body,
				Map<Name, Integer> allowed) {
			this(name, method, url, body, allowed, 403);
		}

	}

	private static Map<Name, Integer> allow(int status, Name... names) {
		Map<Name, Integer> allowed = new EnumMap<>(Name.class);
		for (Name name : names) {
			allowed.put(name, status);
		}
		return allowed;
	}

	private static final Name[] EVERY_ACTIVE_PERSONA = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN,
			SCHOOL_ADMIN_A, SCHOOL_ADMIN_B, CAMPUS_ADMIN_A, PLAIN_USER, TEACHER_LEAD_A, TEACHER_SUBJECT_A,
			TEACHER_OTHER_A, STUDENT_A1, STUDENT_A2, STUDENT_FORMER_A, STUDENT_B1, GUARDIAN_A1, GUARDIAN_PENDING_A1,
			GUARDIAN_RESTRICTED_A1, GUARDIAN_REVOKED_A1, GUARDIAN_OF_A2, UNRELATED_ADULT };

	private static final Name[] READERS_OF_SCHOOL_A = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A,
			CAMPUS_ADMIN_A };

	private static final Name[] MANAGERS_OF_SCHOOL_A = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A };

	List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		cases.addAll(schoolCases());
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

		// --- user provisioning: platform.roles.manage at any scope
		Name[] roleManagers = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN };
		cases.add(new Case("POST user", HttpMethod.POST, (f) -> PLATFORM + "/users",
				(f) -> Map.of("phone", Personas.newPhone(), "givenName", "Matrix", "familyName", "User"),
				allow(201, roleManagers)));
		// the national format keeps the '+' of E.164 out of the URL template
		cases.add(new Case("GET user lookup", HttpMethod.GET,
				(f) -> PLATFORM + "/users/lookup?phone=0" + this.jdbc
					.queryForObject("select phone_e164 from users where id = ?", String.class,
							this.personas.get(PLAIN_USER).userId())
					.substring(4),
				null, allow(200, roleManagers)));

		cases.add(new Case("GET user by id", HttpMethod.GET,
				(f) -> PLATFORM + "/users/" + this.personas.get(PLAIN_USER).userId(), null, allow(200, roleManagers)));

		// --- school by slug: school.read on the school; everyone else gets 404, as for an unknown slug
		cases.add(new Case("GET school A by slug", HttpMethod.GET,
				(f) -> "/api/v1/schools/by-slug/" + this.jdbc.queryForObject("select slug from schools where id = ?",
						String.class, f.schoolA()),
				null, allow(200, READERS_OF_SCHOOL_A), 404));
		cases.add(new Case("GET unknown school by slug", HttpMethod.GET, (f) -> "/api/v1/schools/by-slug/nope-nope",
				null, allow(404, EVERY_ACTIVE_PERSONA), 404));

		// --- catalogs for the console editors
		cases.add(new Case("GET configuration definitions", HttpMethod.GET, (f) -> PLATFORM + "/definitions", null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN, SCHOOL_ADMIN_A,
						SCHOOL_ADMIN_B)));
		cases.add(new Case("GET school plans", HttpMethod.GET, (f) -> PLATFORM + "/plans", null,
				allow(200, PLATFORM_ADMIN)));

		// --- media (R-30: access follows the owning object; there is no role check on the media endpoints)
		cases.add(new Case("GET media policies", HttpMethod.GET, (f) -> "/api/v1/media/policies", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST AVATAR upload", HttpMethod.POST, (f) -> "/api/v1/media/uploads",
				(f) -> Map.of("purpose", "AVATAR", "filename", "me.jpg", "mimeType", "image/jpeg", "sizeBytes", 1000),
				allow(201, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST BRANDING upload for school A", HttpMethod.POST, (f) -> "/api/v1/media/uploads",
				(f) -> Map.of("purpose", "BRANDING", "filename", "logo.png", "mimeType", "image/png", "sizeBytes", 1000,
						"context", Map.of("type", "SCHOOL", "id", f.schoolA())),
				allow(201, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("POST BRANDING upload for the organization", HttpMethod.POST, (f) -> "/api/v1/media/uploads",
				(f) -> Map.of("purpose", "BRANDING", "filename", "logo.png", "mimeType", "image/png", "sizeBytes", 1000,
						"context", Map.of("type", "ORGANIZATION", "id", f.organization())),
				allow(201, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("POST upload for a purpose without a policy", HttpMethod.POST, (f) -> "/api/v1/media/uploads",
				(f) -> Map.of("purpose", "MESSAGE", "filename", "x.jpg", "mimeType", "image/jpeg", "sizeBytes", 1000,
						"context", Map.of("type", "SCHOOL", "id", f.schoolA())),
				allow(403)));
		cases.add(new Case("GET READY branding media", HttpMethod.GET,
				(f) -> "/api/v1/media/" + brandingMedia(f, "READY"), null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET branding media url", HttpMethod.GET,
				(f) -> "/api/v1/media/" + brandingMedia(f, "READY") + "/url?variant=THUMB", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET branding media original url", HttpMethod.GET,
				(f) -> "/api/v1/media/" + brandingMedia(f, "READY") + "/url?variant=ORIGINAL", null,
				allow(400, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET pending branding media (uploader and managers only)", HttpMethod.GET,
				(f) -> "/api/v1/media/" + brandingMedia(f, "PENDING_UPLOAD"), null,
				allow(200, MANAGERS_OF_SCHOOL_A), 404));
		cases.add(new Case("POST complete for somebody else's media", HttpMethod.POST,
				(f) -> "/api/v1/media/" + brandingMedia(f, "READY") + "/complete", null,
				allow(200, SCHOOL_ADMIN_A), 404));
		cases.add(new Case("DELETE branding media", HttpMethod.DELETE, (f) -> "/api/v1/media/" + brandingMedia(f, "READY"),
				null, allow(204, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("PUT my avatar (unknown media)", HttpMethod.PUT, (f) -> "/api/v1/me/avatar",
				(f) -> Map.of("mediaId", UUID.randomUUID()), allow(404, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("DELETE my avatar", HttpMethod.DELETE, (f) -> "/api/v1/me/avatar", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("PUT school A logo", HttpMethod.PUT, (f) -> PLATFORM + "/schools/" + f.schoolA() + "/logo",
				(f) -> Map.of("mediaId", UUID.randomUUID()), allow(404, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("DELETE school A logo", HttpMethod.DELETE,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/logo", null, allow(200, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("PUT school A cover", HttpMethod.PUT, (f) -> PLATFORM + "/schools/" + f.schoolA() + "/cover",
				(f) -> Map.of("mediaId", UUID.randomUUID()), allow(404, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("DELETE school A cover", HttpMethod.DELETE,
				(f) -> PLATFORM + "/schools/" + f.schoolA() + "/cover", null, allow(200, MANAGERS_OF_SCHOOL_A)));
		cases.add(new Case("PUT organization logo", HttpMethod.PUT,
				(f) -> PLATFORM + "/organizations/" + f.organization() + "/logo",
				(f) -> Map.of("mediaId", UUID.randomUUID()), allow(404, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));
		cases.add(new Case("DELETE organization logo", HttpMethod.DELETE,
				(f) -> PLATFORM + "/organizations/" + f.organization() + "/logo", null,
				allow(200, PLATFORM_ADMIN, COUNTRY_ADMIN_ET)));

		// --- notifications, preferences, push devices and real-time tickets: any signed-in user, own data only
		cases.add(new Case("GET notifications", HttpMethod.GET, (f) -> "/api/v1/notifications?limit=5", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET unread notification count", HttpMethod.GET, (f) -> "/api/v1/notifications/unread-count",
				null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST mark a (somebody else's) notification read", HttpMethod.POST,
				(f) -> "/api/v1/notifications/" + UUID.randomUUID() + "/read", null, allow(404, EVERY_ACTIVE_PERSONA), 404));
		cases.add(new Case("POST mark all notifications read", HttpMethod.POST, (f) -> "/api/v1/notifications/read-all",
				null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST archive a (somebody else's) notification", HttpMethod.POST,
				(f) -> "/api/v1/notifications/" + UUID.randomUUID() + "/archive", null, allow(404, EVERY_ACTIVE_PERSONA),
				404));
		cases.add(new Case("POST test notification", HttpMethod.POST, (f) -> "/api/v1/notifications/test", null,
				allow(200, PLATFORM_ADMIN)));
		cases.add(new Case("GET notification preferences", HttpMethod.GET, (f) -> "/api/v1/me/notification-preferences",
				null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("PUT notification preferences", HttpMethod.PUT, (f) -> "/api/v1/me/notification-preferences",
				(f) -> Map.of("changes", List.of(Map.of("typeCode", "NEW_POST", "channel", "PUSH", "enabled", true))),
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET notification settings", HttpMethod.GET, (f) -> "/api/v1/me/notification-settings", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("PUT notification settings", HttpMethod.PUT, (f) -> "/api/v1/me/notification-settings",
				(f) -> Map.of("quietHoursStart", "22:00", "quietHoursEnd", "06:00"), allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST push device", HttpMethod.POST, (f) -> "/api/v1/me/push-devices",
				(f) -> Map.of("platform", "ANDROID", "provider", "EXPO", "token",
						"ExponentPushToken[" + Ids.newId().toString().replace("-", "") + "]"),
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("DELETE a (somebody else's) push device", HttpMethod.DELETE,
				(f) -> "/api/v1/me/push-devices/" + UUID.randomUUID(), null, allow(404, EVERY_ACTIVE_PERSONA), 404));
		cases.add(new Case("POST realtime ticket", HttpMethod.POST, (f) -> "/api/v1/realtime/tickets", null,
				allow(200, EVERY_ACTIVE_PERSONA)));

		// --- audit log: platform only
		cases.add(new Case("GET audit logs", HttpMethod.GET, (f) -> PLATFORM + "/audit-logs?limit=5", null,
				allow(200, PLATFORM_ADMIN)));
		return cases;
	}


	// ---- school structure, people and classes (M3a) ---------------------------------------------------

	private static final String SCHOOLS = "/api/v1/schools/";

	/** Hold the school-operations permissions over school A (platform, its country, its organization, the school). */
	private static final Name[] SCHOOL_A_MANAGERS = { PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A };

	/** See school A (school.read) but run nothing in it (the campus administrator manages classes of campus A only). */
	private static final Name[] SCHOOL_A_VISITING_ADMINS = { CAMPUS_ADMIN_A };

	private static final Name[] SCHOOL_A_MEMBERS = { TEACHER_LEAD_A, TEACHER_SUBJECT_A, TEACHER_OTHER_A, STUDENT_A1,
			STUDENT_A2, STUDENT_FORMER_A };

	private static final Name[] SCHOOL_A_TEACHERS = { TEACHER_LEAD_A, TEACHER_SUBJECT_A, TEACHER_OTHER_A };

	private static final Name[] SCHOOL_A_STUDENTS = { STUDENT_A1, STUDENT_A2, STUDENT_FORMER_A };

	@SafeVarargs
	private static Map<Name, Integer> merge(Map<Name, Integer>... maps) {
		Map<Name, Integer> merged = new EnumMap<>(Name.class);
		for (Map<Name, Integer> map : maps) {
			merged.putAll(map);
		}
		return merged;
	}

	private static Name[] concat(Name[]... groups) {
		return java.util.Arrays.stream(groups).flatMap(java.util.Arrays::stream).toArray(Name[]::new);
	}

	/** Visible people of school A without the permission get 403, everybody else (not named) 404. */
	private static Case school(String name, HttpMethod method, Function<Fixture, String> url,
			Function<Fixture, Object> body, int success, Name[] allowed, Name[]... forbidden) {
		Map<Name, Integer> expected = merge(allow(success, allowed), allow(403, concat(forbidden)));
		return new Case(name, method, url, body, expected, 404);
	}

	private UUID userOf(Name name) {
		return this.personas.get(name).userId();
	}

	/** A class of school A with the lead (TEACHER_LEAD_A), a subject teacher and student A1, fresh per call. */
	private UUID freshClass(Fixture f) {
		UUID id = Ids.newId();
		this.jdbc.update("insert into classes (id, school_id, academic_year_id, name) values (?, ?, ?, ?)", id, f.schoolA(),
				f.yearA(), "Matrix " + id.toString().substring(24));
		this.personas.addTeacherToClass(f.schoolA(), id, userOf(TEACHER_LEAD_A), "LEAD", null);
		this.personas.addTeacherToClass(f.schoolA(), id, userOf(TEACHER_SUBJECT_A), "SUBJECT_TEACHER", f.subjectA());
		this.personas.addStudentToClass(f.schoolA(), id, userOf(STUDENT_A1));
		return id;
	}

	private UUID freshYear(Fixture f) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into academic_years (id, school_id, name, starts_on, ends_on) values (?, ?, 'Matrix year', date '2030-01-01',
				                                                                              date '2030-12-31')""", id, f.schoolA());
		return id;
	}

	private UUID staffIdOf(Name name) {
		return this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, userOf(name));
	}

	List<Case> schoolCases() {
		List<Case> cases = new ArrayList<>();
		Name[] readers = concat(READERS_OF_SCHOOL_A, SCHOOL_A_MEMBERS);
		Name[] teachingMembers = { TEACHER_LEAD_A, TEACHER_SUBJECT_A };

		// --- academic structure: members read, school.academic.manage writes
		cases.add(school("GET academic years", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/academic-years", null, 200,
				readers));
		cases.add(school("POST academic year", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/academic-years",
				(f) -> Map.of("name", "Matrix", "startsOn", "2031-01-01", "endsOn", "2031-12-31"), 201, SCHOOL_A_MANAGERS,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET academic year", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + f.yearA(), null, 200, readers));
		cases.add(school("PATCH academic year", HttpMethod.PATCH,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + f.yearA(), (f) -> Map.of("name", "Persona year"), 200,
				SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST make year current", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + f.yearA() + "/make-current", null, 200,
				SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("DELETE academic year", HttpMethod.DELETE,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + freshYear(f), null, 204, SCHOOL_A_MANAGERS,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET periods", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + f.yearA() + "/periods", null, 200, readers));
		cases.add(school("POST period", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/academic-years/" + freshYear(f) + "/periods",
				(f) -> Map.of("name", "Term 1", "periodType", "TERM", "startsOn", "2030-02-01", "endsOn", "2030-03-01"), 201,
				SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET grade levels", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/grade-levels", null, 200,
				readers));
		cases.add(school("POST grade level", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/grade-levels",
				(f) -> Map.of("name", "Matrix grade"), 201, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET subjects", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/subjects", null, 200, readers));
		cases.add(school("POST subject", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/subjects",
				(f) -> Map.of("name", "Matrix subject"), 201, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS,
				SCHOOL_A_MEMBERS));

		// --- classes
		Name[] classAdmins = SCHOOL_A_MANAGERS;
		Name[] classViewers8A = concat(classAdmins, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A, STUDENT_A1 });
		cases.add(school("GET classes (filtered to what the caller may see)", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes", null, 200, readers));
		cases.add(school("POST class", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/classes",
				(f) -> Map.of("name", "Matrix class"), 201, concat(classAdmins, SCHOOL_A_TEACHERS),
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		// M5: guardian A1 sees the class (metadata, teachers) through the link, never the roster
		Name[] classMetadataViewers8A = concat(classViewers8A, new Name[] { GUARDIAN_A1 });
		cases.add(school("GET class 8A", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A(), null,
				200, classMetadataViewers8A));
		cases.add(school("PATCH class 8A", HttpMethod.PATCH, (f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A(),
				(f) -> Map.of("name", "8A"), 200, concat(classAdmins, new Name[] { TEACHER_LEAD_A }),
				new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST archive class", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/archive", null, 200,
				concat(classAdmins, new Name[] { TEACHER_LEAD_A }), new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST restore class", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/restore", null, 200,
				concat(classAdmins, new Name[] { TEACHER_LEAD_A }), new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("DELETE empty class", HttpMethod.DELETE, (f) -> {
			UUID id = Ids.newId();
			this.jdbc.update("insert into classes (id, school_id, academic_year_id, name) values (?, ?, ?, 'Empty')", id,
					f.schoolA(), f.yearA());
			return SCHOOLS + f.schoolA() + "/classes/" + id;
		}, null, 204, classAdmins));

		// --- rosters
		Name[] classTeachers = concat(classAdmins, teachingMembers);
		cases.add(school("GET class students", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A() + "/students", null, 200, classViewers8A));
		cases.add(school("POST class students", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/students",
				(f) -> Map.of("studentIds", List.of(this.personas.newStudent(f.schoolA(), "matrix-new"))), 200,
				classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("DELETE class student", HttpMethod.DELETE,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/students/" + userOf(STUDENT_A1), null, 204,
				classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST move class student", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/students/" + userOf(STUDENT_A1) + "/move",
				(f) -> Map.of("toClassId", freshClassWithoutStudents(f)), 204, classTeachers,
				new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("GET class teachers", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A() + "/teachers", null, 200, classMetadataViewers8A));
		cases.add(school("POST class teacher", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/teachers",
				(f) -> Map.of("teacherId", this.personas.newTeacher(f.schoolA(), "matrix-co-teacher"), "role", "CO_TEACHER"),
				200,
				concat(classAdmins, new Name[] { TEACHER_LEAD_A }), new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("DELETE class teacher (the subject teacher's own row)", HttpMethod.DELETE, (f) -> {
			UUID id = freshClass(f);
			UUID row = this.jdbc.queryForObject(
					"select id from class_teachers where class_id = ? and role = 'SUBJECT_TEACHER'", UUID.class, id);
			return SCHOOLS + f.schoolA() + "/classes/" + id + "/teachers/" + row;
		}, null, 204, concat(classAdmins, teachingMembers), new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("GET class groups", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A() + "/groups", null, 200, classTeachers,
				new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST class group", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/groups",
				(f) -> Map.of("name", "Group", "studentIds", List.of(userOf(STUDENT_A1))), 201, classTeachers,
				new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("DELETE class group", HttpMethod.DELETE, (f) -> {
			UUID id = freshClass(f);
			UUID group = Ids.newId();
			this.jdbc.update("insert into student_groups (id, class_id, school_id, name, created_by) values (?, ?, ?, 'G', ?)",
					group, id, f.schoolA(), userOf(TEACHER_LEAD_A));
			return SCHOOLS + f.schoolA() + "/classes/" + id + "/groups/" + group;
		}, null, 204, classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST class login codes", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + freshClass(f) + "/login-codes", (f) -> Map.of("type", "TEXT"),
				200, classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("DELETE class login code", HttpMethod.DELETE, (f) -> {
			UUID id = freshClass(f);
			UUID code = Ids.newId();
			this.jdbc.update("""
					insert into student_login_codes (id, student_id, school_id, class_id, code_type, code_hash, issued_by)
					values (?, ?, ?, ?, 'TEXT', ?, ?)""", code, userOf(STUDENT_A1), f.schoolA(), id, "matrix-" + code,
					userOf(TEACHER_LEAD_A));
			return SCHOOLS + f.schoolA() + "/classes/" + id + "/login-codes/" + code;
		}, null, 204, classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(new Case("GET my classes", HttpMethod.GET, (f) -> "/api/v1/me/classes", null,
				allow(200, EVERY_ACTIVE_PERSONA)));

		// --- staff
		Name[] colleagues = SCHOOL_A_TEACHERS;
		cases.add(school("POST staff", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/staff",
				(f) -> Map.of("phone", Personas.newPhone(), "givenName", "Matrix", "staffType", "TEACHER"), 201,
				SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET staff", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/staff", null, 200,
				concat(SCHOOL_A_MANAGERS, colleagues), SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("GET one staff member", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/staff/" + staffIdOf(TEACHER_LEAD_A), null, 200,
				concat(SCHOOL_A_MANAGERS, colleagues), SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("PATCH staff", HttpMethod.PATCH,
				(f) -> SCHOOLS + f.schoolA() + "/staff/" + staffIdOf(TEACHER_OTHER_A), (f) -> Map.of("jobTitle", "Teacher"),
				200, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST deactivate staff", HttpMethod.POST, (f) -> {
			UUID teacher = this.personas.newTeacher(f.schoolA(), "matrix-leaver");
			UUID staff = this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, teacher);
			return SCHOOLS + f.schoolA() + "/staff/" + staff + "/deactivate";
		}, null, 200, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));

		cases.add(school("GET school summary", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/summary", null, 200,
				concat(SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS), SCHOOL_A_MEMBERS));

		// --- roster imports: school.import.manage (administrators); members get 403, outsiders 404
		String imports = "/imports";
		Name[] importers = SCHOOL_A_MANAGERS;
		cases.add(school("GET import types", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + imports + "/types", null, 200,
				importers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET import template", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + imports + "/templates/STUDENTS?format=csv", null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET imports", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + imports, null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST import", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + imports,
				(f) -> Map.of("importType", "STUDENTS", "mediaId", importMedia(f)), 201, importers, SCHOOL_A_VISITING_ADMINS,
				SCHOOL_A_MEMBERS));
		cases.add(school("GET import", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED"),
				null, 200, importers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET import preview", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED") + "/preview", null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("PUT import mapping", HttpMethod.PUT,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED") + "/mapping",
				(f) -> Map.of("columnMapping", Map.of("given_name", 0, "father_name", 1, "date_of_birth", 2)), 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST import dry run", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED") + "/dry-run", null, 202, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET import rows", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "VALIDATED") + "/rows", null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST resolve import row", HttpMethod.POST, (f) -> {
			UUID job = importJob(f, "VALIDATED");
			UUID row = this.jdbc.queryForObject("select id from import_job_rows where import_job_id = ?", UUID.class, job);
			return SCHOOLS + f.schoolA() + imports + "/" + job + "/rows/" + row + "/resolve";
		}, (f) -> Map.of("resolution", "SKIP"), 200, importers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET import error report", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "VALIDATED") + "/error-report", null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		// the job's hash does not match its mapping: the permission passes, the state refuses (409)
		cases.add(school("POST import commit (changed since the dry run)", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "VALIDATED") + "/commit", null, 409, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST cancel import", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED") + "/cancel", null, 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("DELETE import", HttpMethod.DELETE,
				(f) -> SCHOOLS + f.schoolA() + imports + "/" + importJob(f, "PREVIEWED"), null, 204, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET import mapping profiles", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + imports + "/mapping-profiles",
				null, 200, importers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST import mapping profile", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + imports + "/mapping-profiles",
				(f) -> Map.of("name", "Matrix " + Ids.newId(), "importType", "STUDENTS", "columnMapping",
						Map.of("given_name", "a", "father_name", "b", "date_of_birth", "c")), 201, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("PUT import mapping profile", HttpMethod.PUT,
				(f) -> SCHOOLS + f.schoolA() + imports + "/mapping-profiles/" + importProfile(f),
				(f) -> Map.of("name", "Matrix " + Ids.newId(), "columnMapping",
						Map.of("given_name", "a", "father_name", "b", "date_of_birth", "c")), 200, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("DELETE import mapping profile", HttpMethod.DELETE,
				(f) -> SCHOOLS + f.schoolA() + imports + "/mapping-profiles/" + importProfile(f), null, 204, importers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));

		// --- students
		Name[] studentsOf8A = concat(SCHOOL_A_MANAGERS, teachingMembers);
		cases.add(school("POST student (no class)", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/students",
				(f) -> Map.of("givenName", "Matrix"), 201, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST student into class 8A", HttpMethod.POST, (f) -> SCHOOLS + f.schoolA() + "/students",
				(f) -> Map.of("givenName", "Matrix", "classId", f.class8A()), 201, studentsOf8A, new Name[] { STUDENT_A1 }));
		cases.add(school("GET students", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/students", null, 200,
				concat(SCHOOL_A_MANAGERS, colleagues), SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("GET student A1", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1), null, 200,
				concat(studentsOf8A, new Name[] { STUDENT_A1, GUARDIAN_A1 })));
		cases.add(school("PATCH student A1", HttpMethod.PATCH,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1),
				(f) -> Map.of("studentNumber", "M-" + Ids.newId()), 200, SCHOOL_A_MANAGERS,
				new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A, STUDENT_A1 }));
		cases.add(school("PATCH own avatar (student A1)", HttpMethod.PATCH,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1),
				(f) -> Map.of("avatarConfig", Map.of("hat", "red")), 200, concat(SCHOOL_A_MANAGERS, new Name[] { STUDENT_A1 }),
				new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A }));
		cases.add(school("POST withdraw student", HttpMethod.POST, (f) -> {
			UUID student = this.personas.newStudent(f.schoolA(), "matrix-leaver");
			return SCHOOLS + f.schoolA() + "/students/" + student + "/withdraw";
		}, null, 200, SCHOOL_A_MANAGERS, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST student password (school setting is off)", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/password",
				(f) -> Map.of("password", "a-long-password"), 409, studentsOf8A, new Name[] { STUDENT_A1 }));
		cases.add(school("GET student login codes", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/login-codes", null, 200, studentsOf8A,
				new Name[] { STUDENT_A1 }));
		cases.add(school("POST revoke student login codes", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/login-codes/revoke", null, 200,
				studentsOf8A, new Name[] { STUDENT_A1 }));
		cases.addAll(guardianCases());
		cases.addAll(pointsCases());
		cases.addAll(attendanceCases());
		cases.addAll(feedCases());
		return cases;
	}

	// ---- guardians and consent (M5a) ---------------------------------------------------------------

	private static final Name[] SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS = concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS);

	/** Every active persona with a STANDARD account (not a school-managed student). */
	private static final Name[] ADULTS = java.util.Arrays.stream(EVERY_ACTIVE_PERSONA)
		.filter((n) -> !java.util.List.of(STUDENT_A1, STUDENT_A2, STUDENT_FORMER_A, STUDENT_B1).contains(n))
		.toArray(Name[]::new);

	List<Case> guardianCases() {
		List<Case> cases = new ArrayList<>();
		Name[] inviters = concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A });
		Name[] verifiers = concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_LEAD_A });

		// --- roster privacy: guardians see their child's classes and record, never the roster or other children
		cases.add(school("GET class 7B", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class7B(), null,
				200, concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_OTHER_A, STUDENT_A2, GUARDIAN_OF_A2 })));
		cases.add(school("GET class 7B students", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class7B() + "/students", null, 200,
				concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_OTHER_A, STUDENT_A2 })));
		cases.add(school("GET student A2", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A2),
				null, 200, concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_OTHER_A, STUDENT_A2, GUARDIAN_OF_A2 })));

		// --- invitations
		cases.add(school("POST guardian invitation", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + freshChild(f) + "/guardian-invitations",
				(f) -> Map.of("phone", Personas.newPhone(), "relationshipType", "MOTHER"), 201, inviters));
		cases.add(school("GET guardian invitations", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + freshChild(f) + "/guardian-invitations", null, 200, inviters));
		cases.add(school("POST revoke guardian invitation", HttpMethod.POST, (f) -> {
			UUID child = freshChild(f);
			return SCHOOLS + f.schoolA() + "/guardian-invitations/" + invitation(f, child, "matrix-" + Ids.newId())
					+ "/revoke";
		}, null, 200, inviters));
		cases.add(school("POST parent codes", HttpMethod.POST, (f) -> {
			UUID classId = freshClass(f);
			UUID child = this.personas.newStudent(f.schoolA(), "matrix-coded");
			this.personas.addStudentToClass(f.schoolA(), classId, child);
			this.lastChild = child;
			return SCHOOLS + f.schoolA() + "/classes/" + classId + "/parent-codes";
		}, (f) -> Map.of("studentIds", List.of(this.lastChild)), 200, inviters, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(new Case("POST invitation preview (public)", HttpMethod.POST, (f) -> "/api/v1/invitations/preview",
				(f) -> Map.of("token", freshToken(f)), allow(200, EVERY_ACTIVE_PERSONA), 200, 200));
		cases.add(new Case("POST redeem an invitation while signed in", HttpMethod.POST,
				(f) -> "/api/v1/me/guardian-links", (f) -> Map.of("token", freshToken(f)), allow(200, ADULTS), 403));
		cases.add(new Case("GET my children", HttpMethod.GET, (f) -> "/api/v1/me/children", null,
				allow(200, EVERY_ACTIVE_PERSONA)));

		// --- verification and management
		// Lead teachers of any class of the school see the queue (filtered). "POST class" (and other tests) make the
		// subject teacher the lead of classes they create: end those rows first so the subject teacher is no lead.
		cases.add(school("GET guardian link queue", HttpMethod.GET, (f) -> {
			this.jdbc.update("""
					update class_teachers set status = 'REMOVED', left_at = now()
					where teacher_id = ? and role = 'LEAD' and status = 'ACTIVE'""", userOf(TEACHER_SUBJECT_A));
			return SCHOOLS + f.schoolA() + "/guardian-links";
		}, null, 200, concat(SCHOOL_A_MANAGERS, new Name[] { TEACHER_LEAD_A, TEACHER_OTHER_A }),
				new Name[] { TEACHER_SUBJECT_A, CAMPUS_ADMIN_A, STUDENT_A1, STUDENT_A2, STUDENT_FORMER_A }));
		cases.add(school("POST approve guardian link", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/guardian-links/" + freshLink(f, "PENDING") + "/approve", null, 200,
				verifiers, new Name[] { TEACHER_SUBJECT_A }));
		cases.add(school("POST reject guardian link", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/guardian-links/" + freshLink(f, "PENDING") + "/reject",
				(f) -> Map.of("note", "Unknown to the school"), 200, verifiers, new Name[] { TEACHER_SUBJECT_A }));
		cases.add(school("PATCH guardian link", HttpMethod.PATCH,
				(f) -> SCHOOLS + f.schoolA() + "/guardian-links/" + freshLink(f, "ACTIVE"),
				(f) -> Map.of("permissions", Map.of("canAwardPoints", false)), 200, SCHOOL_A_MANAGERS,
				new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A }));
		cases.add(school("POST revoke guardian link", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/guardian-links/" + freshLink(f, "ACTIVE") + "/revoke", null, 200,
				SCHOOL_A_MANAGERS, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A }));
		cases.add(school("GET student A1 guardians", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/guardians", null, 200, inviters));
		cases.add(school("POST student guardian (created by the school)", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + freshChild(f) + "/guardians",
				(f) -> Map.of("phone", Personas.newPhone(), "name", "Matrix parent", "relationshipType", "FATHER"), 201,
				SCHOOL_A_MANAGERS, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A }));

		// --- policies
		cases.add(new Case("GET policy text (public)", HttpMethod.GET,
				(f) -> "/api/v1/policies/MEDIA_PUBLICATION?schoolId=" + f.schoolA() + "&lang=am", null,
				allow(200, EVERY_ACTIVE_PERSONA), 200, 200));
		cases.add(school("GET school policies", HttpMethod.GET, (f) -> SCHOOLS + f.schoolA() + "/policies", null, 200,
				SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("POST school policy", HttpMethod.POST, (f) -> {
			removeSchoolPolicy(f);
			return SCHOOLS + f.schoolA() + "/policies";
		}, (f) -> Map.of("policyType", "ACCOUNT_PARTICIPATION", "title", "Matrix policy"), 201, SCHOOL_A_MANAGERS,
				SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("PUT school policy", HttpMethod.PUT,
				(f) -> SCHOOLS + f.schoolA() + "/policies/" + freshSchoolPolicy(f), (f) -> Map.of("title", "Renamed"), 200,
				SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("POST school policy version", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/policies/" + freshSchoolPolicy(f) + "/versions",
				(f) -> Map.of("versionLabel", "v1", "languageCode", "en", "content", "Matrix text"), 201,
				SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("PUT school policy version", HttpMethod.PUT, (f) -> {
			UUID doc = freshSchoolPolicy(f);
			return SCHOOLS + f.schoolA() + "/policies/" + doc + "/versions/" + draftVersion(doc);
		}, (f) -> Map.of("content", "Changed text"), 200, SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("POST publish school policy version", HttpMethod.POST, (f) -> {
			UUID doc = freshSchoolPolicy(f);
			return SCHOOLS + f.schoolA() + "/policies/" + doc + "/versions/" + draftVersion(doc) + "/publish";
		}, null, 200, SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));

		// --- consent
		cases.add(new Case("GET my consents", HttpMethod.GET, (f) -> "/api/v1/me/consents", null,
				allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("POST my own TERMS acceptance", HttpMethod.POST, (f) -> "/api/v1/me/consents",
				(f) -> Map.of("consentType", "TERMS", "policyVersionId", countryVersion("TERMS"), "decision", "GRANTED"),
				allow(201, ADULTS)));
		cases.add(new Case("POST consent for child A1 (guardians who may give consent)", HttpMethod.POST,
				(f) -> "/api/v1/me/consents",
				(f) -> Map.of("studentId", userOf(STUDENT_A1), "consentType", "MEDIA_PUBLICATION", "policyVersionId",
						countryVersion("MEDIA_PUBLICATION"), "decision", "GRANTED"),
				allow(201, GUARDIAN_A1), 404));
		cases.add(school("POST paper consent", HttpMethod.POST,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + this.personas.newStudent(f.schoolA(), "matrix-paper")
						+ "/consents",
				(f) -> Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", countryVersion("MEDIA_PUBLICATION"),
						"decision", "DENIED", "guardianName", "Matrix grandmother", "method", "PAPER_RECORDED_BY_SCHOOL"),
				201, SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("GET student A1 consents", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/consents", null, 200,
				SCHOOL_A_MANAGERS, SCHOOL_A_STUDENTS_AND_ADMIN_VISITORS));
		cases.add(school("GET class 8A consent summary", HttpMethod.GET,
				(f) -> SCHOOLS + f.schoolA() + "/classes/" + f.class8A() + "/consent-summary", null, 200, inviters,
				new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		return cases;
	}

	/** The child created by the last {@code url} function (for a body function of the same call). */
	private UUID lastChild;

	/** A new student of school A in a fresh class (lead TEACHER_LEAD_A, subject teacher TEACHER_SUBJECT_A). */
	private UUID freshChild(Fixture f) {
		UUID classId = freshClass(f);
		UUID child = this.personas.newStudent(f.schoolA(), "matrix-child");
		this.personas.addStudentToClass(f.schoolA(), classId, child);
		return child;
	}

	/** A live SMS invitation of school A for the child under a known token; returns the invitation id. */
	private UUID invitation(Fixture f, UUID child, String token) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into invitations (id, invitation_type, school_id, student_id, relationship_type, channel,
				                         destination_e164, token_hash, status, max_uses, invited_by, expires_at)
				values (?, 'GUARDIAN_LINK', ?, ?, 'MOTHER', 'SMS', ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), 'SENT', 1,
				        ?, now() + interval '1 day')""", id, f.schoolA(), child, Personas.newPhone(), token,
				userOf(SCHOOL_ADMIN_A));
		return id;
	}

	/** A token (43 base64url characters) of a fresh live invitation for a new student of school A. */
	private String freshToken(Fixture f) {
		String token = (Ids.newId().toString() + Ids.newId().toString()).replace("-", "").substring(0, 43);
		invitation(f, this.personas.newStudent(f.schoolA(), "matrix-invited"), token);
		return token;
	}

	private UUID freshLink(Fixture f, String status) {
		return this.personas.linkGuardian(freshChild(f), this.personas.newUser("matrix-parent").userId(), status, false);
	}

	/** Removes school A's own ACCOUNT_PARTICIPATION wording (matrix data only). */
	private void removeSchoolPolicy(Fixture f) {
		this.jdbc.update("""
				delete from policy_versions where policy_document_id in (select id from policy_documents
				  where school_id = ? and policy_type = 'ACCOUNT_PARTICIPATION')""", f.schoolA());
		this.jdbc.update("delete from policy_documents where school_id = ? and policy_type = 'ACCOUNT_PARTICIPATION'",
				f.schoolA());
	}

	private UUID freshSchoolPolicy(Fixture f) {
		removeSchoolPolicy(f);
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into policy_documents (id, policy_type, school_id, title) values (?, 'ACCOUNT_PARTICIPATION', ?, 'Matrix')""",
				id, f.schoolA());
		return id;
	}

	private UUID draftVersion(UUID documentId) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into policy_versions (id, policy_document_id, version_label, language_code, content, effective_from)
				values (?, ?, 'draft', 'en', 'Draft text', now())""", id, documentId);
		return id;
	}

	/** The seeded Ethiopian English version of a policy type. */
	private UUID countryVersion(String type) {
		return this.jdbc.queryForObject("""
				select v.id from policy_versions v join policy_documents d on d.id = v.policy_document_id
				where d.policy_type = ? and d.country_code = 'ET' and d.school_id is null and v.language_code = 'en'
				  and v.version_label = '2026-11-draft'""", UUID.class, type);
	}

	/** An empty class of school A (a move target); the lead is the teacher of the fresh source class. */
	private UUID freshClassWithoutStudents(Fixture f) {
		UUID id = freshClass(f);
		this.jdbc.update("delete from class_students where class_id = ?", id);
		return id;
	}

	@Autowired
	com.nemaz.school.media.domain.ObjectStorage storage;

	private static final String IMPORT_CSV = "given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\n";

	/** A READY import file of school A, uploaded by its administrator, with its bytes in storage (a fresh one per call). */
	private String importMedia(Fixture f) {
		UUID id = Ids.newId();
		String key = "schools/" + f.schoolA() + "/import-file/" + id + "/original";
		this.storage.put(key, IMPORT_CSV.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/csv");
		this.jdbc.update("""
				insert into media (id, owner_id, school_id, purpose, context_type, context_id, bucket, object_key, mime_type,
				                   media_kind, size_bytes, status, original_filename)
				values (?, ?, ?, 'IMPORT_FILE', 'SCHOOL', ?, 'school-media-test', ?, 'text/csv', 'OTHER', ?, 'READY', 'matrix.csv')""",
				id, this.personas.get(SCHOOL_ADMIN_A).userId(), f.schoolA(), f.schoolA(), key, IMPORT_CSV.length());
		return id.toString();
	}

	/** An import job of school A in the given state (a VALIDATED one has a row that is a possible duplicate). */
	private UUID importJob(Fixture f, String status) {
		UUID media = UUID.fromString(importMedia(f));
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into import_jobs (id, school_id, import_type, source_media_id, source_filename, content_fingerprint,
				                         column_mapping, headers, status, initiated_by, total_rows, progress_total, validated_hash,
				                         valid_rows, error_rows, duplicate_rows)
				values (?, ?, 'STUDENTS', ?, 'matrix.csv', repeat('a', 64), '{"given_name":0,"father_name":1,"date_of_birth":2}'::jsonb,
				        '["given_name","father_name","date_of_birth"]'::jsonb, ?, ?, 1, 1, ?, 0, 0, 1)""", id, f.schoolA(), media, status,
				this.personas.get(SCHOOL_ADMIN_A).userId(), "VALIDATED".equals(status) ? "not-the-real-hash" : null);
		if ("VALIDATED".equals(status)) {
			this.jdbc.update("""
					insert into import_job_rows (import_job_id, school_id, row_number, raw_data, normalized_data, status,
					                             duplicate_candidates)
					values (?, ?, 2, '["Abebe","Kebede","2012-05-14"]'::jsonb, '{"givenName":"Abebe"}'::jsonb, 'DUPLICATE',
					        '[{"studentId":"00000000-0000-0000-0000-000000000001"}]'::jsonb)""", id, f.schoolA());
		}
		return id;
	}

	private UUID importProfile(Fixture f) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into import_mapping_profiles (id, school_id, name, import_type, column_mapping, created_by)
				values (?, ?, ?, 'STUDENTS', '{"given_name":"a","father_name":"b","date_of_birth":"c"}'::jsonb, ?)""", id,
				f.schoolA(), "Matrix profile " + id, this.personas.get(SCHOOL_ADMIN_A).userId());
		return id;
	}

	/** A branding media row of school A uploaded by its administrator, in the given status (a fresh one per call). */
	private UUID brandingMedia(Fixture f, String status) {
		UUID id = Ids.newId();
		String key = "schools/" + f.schoolA() + "/branding/" + id + "/original";
		this.jdbc.update("""
				insert into media (id, owner_id, school_id, purpose, context_type, context_id, bucket, object_key, mime_type,
				                   media_kind, size_bytes, status, width, height)
				values (?, ?, ?, 'BRANDING', 'SCHOOL', ?, 'school-media-test', ?, 'image/png', 'IMAGE', 1000, ?, 100, 100)""",
				id, this.personas.get(SCHOOL_ADMIN_A).userId(), f.schoolA(), f.schoolA(), key, status);
		this.jdbc.update("""
				insert into media_variants (media_id, variant, object_key, mime_type, width, height, size_bytes)
				values (?, 'THUMB', ?, 'image/jpeg', 100, 100, 500)""", id, key.replace("original", "thumb"));
		return id;
	}

	// ---- classroom engagement (M6a) ----------------------------------------------------------------

	@Autowired
	com.nemaz.school.tenancy.SettingsService settingsService;

	/** School A's own copy of a default category (the first call makes the copy, as the API does). */
	private UUID pointCategory(Fixture f, String code) {
		try {
			this.mvc.perform(request(HttpMethod.GET, SCHOOLS + f.schoolA() + "/point-categories")
				.with(this.personas.get(SCHOOL_ADMIN_A).bearer())).andReturn();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		return this.jdbc.queryForObject("select id from point_categories where school_id = ? and template_code = ?", UUID.class,
				f.schoolA(), code);
	}

	/** An AWARD row of student A1 in the class, written by the lead teacher (fixtures; the API path is tested elsewhere). */
	private UUID ledgerRow(Fixture f, UUID classId, UUID batch) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into point_transactions (id, school_id, student_id, context, class_id, academic_year_id, category_id, polarity,
				                                points, transaction_type, awarded_by, awarder_role, award_batch_id)
				values (?, ?, ?, 'CLASS', ?, ?, ?, 'POSITIVE', 1, 'AWARD', ?, 'TEACHER', ?)""", id, f.schoolA(), userOf(STUDENT_A1),
				classId, f.yearA(), pointCategory(f, "kindness"), userOf(TEACHER_LEAD_A), batch);
		return id;
	}

	private UUID classCategory(Fixture f, UUID classId) {
		UUID id = Ids.newId();
		this.jdbc.update("insert into point_categories (id, school_id, class_id, name_i18n) values (?, ?, ?, '{\"en\": \"Matrix\"}')",
				id, f.schoolA(), classId);
		return id;
	}

	private UUID matrixTemplate(Fixture f, String visibility) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into classroom_activity_templates (id, school_id, owner_id, template_type, title, visibility)
				values (?, ?, ?, 'ROUTINE', 'Matrix routine', ?)""", id, f.schoolA(), userOf(TEACHER_OTHER_A), visibility);
		return id;
	}

	List<Case> pointsCases() {
		List<Case> cases = new ArrayList<>();
		String schools = SCHOOLS;
		Name[] managers = SCHOOL_A_MANAGERS;
		Name[] visitorsAndMembers = concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS);
		Name[] classTeachers = { TEACHER_LEAD_A, TEACHER_SUBJECT_A };
		Name[] classBystanders = { STUDENT_A1, GUARDIAN_A1 };
		Name[] staffAndManagers = concat(managers, SCHOOL_A_TEACHERS);

		// --- categories
		cases.add(school("GET point categories", HttpMethod.GET, (f) -> schools + f.schoolA() + "/point-categories", null, 200,
				staffAndManagers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("GET point categories of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/point-categories?classId=" + freshClass(f), null, 200,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("POST point category", HttpMethod.POST, (f) -> schools + f.schoolA() + "/point-categories",
				(f) -> Map.of("names", Map.of("en", "Matrix skill")), 201, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("PATCH point category", HttpMethod.PATCH,
				(f) -> schools + f.schoolA() + "/point-categories/" + pointCategory(f, "helping"), (f) -> Map.of("sortOrder", 11),
				200, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("POST class point category", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/point-categories",
				(f) -> Map.of("names", Map.of("en", "Matrix class skill")), 201, concat(managers, new Name[] { TEACHER_LEAD_A }),
				new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("PATCH class point category", HttpMethod.PATCH, (f) -> {
			UUID classId = freshClass(f);
			return schools + f.schoolA() + "/classes/" + classId + "/point-categories/" + classCategory(f, classId);
		}, (f) -> Map.of("active", false), 200, concat(managers, new Name[] { TEACHER_LEAD_A }),
				new Name[] { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 }));

		// --- awards: teachers of the class and managers; students and guardians of the class see it and may not
		cases.add(school("POST class points award", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/points/awards",
				(f) -> Map.of("categoryId", pointCategory(f, "kindness"), "studentIds", List.of(userOf(STUDENT_A1))), 201,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("POST school-wide points award", HttpMethod.POST, (f) -> schools + f.schoolA() + "/points/awards",
				(f) -> Map.of("categoryId", pointCategory(f, "kindness"), "studentIds", List.of(userOf(STUDENT_A1))), 201,
				managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		// home points: only an ACTIVE, unrestricted guardian of the child with can_award_points; everybody else is told nothing
		cases.add(new Case("GET home point categories", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/home-point-categories", null,
				allow(200, GUARDIAN_A1), 404));
		cases.add(new Case("POST home points", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/home-points",
				(f) -> Map.of("categoryId", pointCategory(f, "home_homework")), allow(201, GUARDIAN_A1), 404));

		// --- undo: the awarder (lead teacher here) and managers; another class teacher sees it and may not; the rest cannot find it
		cases.add(new Case("POST reverse a point transaction", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/points/transactions/" + ledgerRow(f, freshClass(f), null) + "/reverse", null,
				merge(allow(201, managers), allow(201, TEACHER_LEAD_A), allow(403, TEACHER_SUBJECT_A)), 404));
		cases.add(new Case("POST reverse a point batch", HttpMethod.POST, (f) -> {
			UUID classId = freshClass(f);
			UUID batch = Ids.newId();
			ledgerRow(f, classId, batch);
			ledgerRow(f, classId, batch);
			return schools + f.schoolA() + "/points/batches/" + batch + "/reverse";
		}, null, merge(allow(201, managers), allow(201, TEACHER_LEAD_A), allow(403, TEACHER_SUBJECT_A)), 404));

		// --- reads
		cases.add(school("GET class points overview", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/points", null, 200,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("GET class leaderboard (off by default)", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/leaderboard", null, 403,
				concat(managers, classTeachers, new Name[] { STUDENT_A1 })));
		cases.add(school("GET class leaderboard (switched on): never for guardians", HttpMethod.GET, (f) -> {
			this.settingsService.set("engagement.leaderboard.enabled", com.nemaz.school.tenancy.SettingScope.school(f.schoolA()),
					this.json.valueToTree(true), this.personas.platformAdmin().userId());
			return schools + f.schoolA() + "/classes/" + freshClass(f) + "/leaderboard";
		}, null, 200, concat(managers, classTeachers, new Name[] { STUDENT_A1 })));
		String studentPoints = "/students/";
		Name[] childAndFamilyAndTeachers = concat(managers, classTeachers, new Name[] { STUDENT_A1, GUARDIAN_A1 });
		cases.add(new Case("GET student points", HttpMethod.GET,
				(f) -> schools + f.schoolA() + studentPoints + userOf(STUDENT_A1) + "/points", null,
				allow(200, childAndFamilyAndTeachers), 404));
		cases.add(new Case("GET student point transactions", HttpMethod.GET,
				(f) -> schools + f.schoolA() + studentPoints + userOf(STUDENT_A1) + "/points/transactions", null,
				allow(200, childAndFamilyAndTeachers), 404));
		cases.add(new Case("GET student HOME point transactions: family only", HttpMethod.GET,
				(f) -> schools + f.schoolA() + studentPoints + userOf(STUDENT_A1) + "/points/transactions?section=HOME", null,
				merge(allow(200, STUDENT_A1, GUARDIAN_A1), allow(403, concat(managers, classTeachers))), 404));

		// --- badges
		cases.add(school("GET badges", HttpMethod.GET, (f) -> schools + f.schoolA() + "/badges", null, 200,
				concat(managers, visitorsAndMembers)));
		cases.add(school("POST badge", HttpMethod.POST, (f) -> schools + f.schoolA() + "/badges",
				(f) -> Map.of("code", "matrix_" + Ids.newId().toString().substring(24), "names", Map.of("en", "Matrix badge"),
						"criteriaType", "MANUAL"),
				201, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("PATCH badge", HttpMethod.PATCH, (f) -> {
			pointCategory(f, "helping");
			UUID badge = this.jdbc.queryForObject("select id from badges where school_id = ? and code = 'teachers_pick'", UUID.class,
					f.schoolA());
			return schools + f.schoolA() + "/badges/" + badge;
		}, (f) -> Map.of("sortOrder", 95), 200, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(new Case("POST manual badge", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/badges", (f) -> {
					pointCategory(f, "helping");
					return Map.of("badgeId", this.jdbc.queryForObject(
							"select id from badges where school_id = ? and code = 'teachers_pick'", UUID.class, f.schoolA()));
				}, merge(allow(200, concat(managers, classTeachers)), allow(403, STUDENT_A1, GUARDIAN_A1)), 404));

		// --- reports
		cases.add(school("GET points report of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/points/report?classId=" + freshClass(f), null, 200,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("GET points report of the school", HttpMethod.GET, (f) -> schools + f.schoolA() + "/points/report", null,
				200, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET points report CSV of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/points/report.csv?classId=" + freshClass(f), null, 200,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("GET points report CSV of the school", HttpMethod.GET, (f) -> schools + f.schoolA() + "/points/report.csv",
				null, 200, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));

		// --- toolkit: saved groups and activity templates
		cases.add(school("POST class group set", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/group-sets",
				(f) -> Map.of("groups", List.of(Map.of("name", "Matrix group", "studentIds", List.of(userOf(STUDENT_A1))))), 201,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("PATCH class group", HttpMethod.PATCH, (f) -> {
			UUID classId = freshClass(f);
			UUID group = Ids.newId();
			this.jdbc.update("insert into student_groups (id, class_id, school_id, name, created_by) values (?, ?, ?, 'Matrix', ?)",
					group, classId, f.schoolA(), userOf(TEACHER_LEAD_A));
			return schools + f.schoolA() + "/classes/" + classId + "/groups/" + group;
		}, (f) -> Map.of("name", "Renamed"), 200, concat(managers, classTeachers), classBystanders));
		String templates = "/activity-templates";
		cases.add(school("GET activity templates", HttpMethod.GET, (f) -> schools + f.schoolA() + templates, null, 200,
				staffAndManagers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("POST activity template", HttpMethod.POST, (f) -> schools + f.schoolA() + templates,
				(f) -> Map.of("templateType", "CHECKLIST", "title", "Matrix checklist"), 201, staffAndManagers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("GET activity template", HttpMethod.GET,
				(f) -> schools + f.schoolA() + templates + "/" + matrixTemplate(f, "SCHOOL"), null, 200, staffAndManagers,
				SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("PATCH activity template (owner or points manager)", HttpMethod.PATCH,
				(f) -> schools + f.schoolA() + templates + "/" + matrixTemplate(f, "SCHOOL"), (f) -> Map.of("title", "Renamed"), 200,
				concat(managers, new Name[] { TEACHER_OTHER_A }),
				concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A })));
		cases.add(school("DELETE activity template (owner or points manager)", HttpMethod.DELETE,
				(f) -> schools + f.schoolA() + templates + "/" + matrixTemplate(f, "SCHOOL"), null, 204,
				concat(managers, new Name[] { TEACHER_OTHER_A }),
				concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A })));
		return cases;
	}

	// ---- attendance, absence reports and offline sync (M7a) --------------------------------------------

	private java.time.LocalDate matrixToday() {
		return java.time.LocalDate.now(java.time.ZoneId.of("Africa/Addis_Ababa"));
	}

	/** A SUBMITTED absence report of student A1 filed by guardian A1 (fixtures; the API path is tested elsewhere). */
	private UUID absenceReport(Fixture f) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into absence_reports (id, school_id, student_id, reported_by, from_date, to_date, reason_code)
				values (?, ?, ?, ?, ?, ?, 'ILLNESS')""", id, f.schoolA(), userOf(STUDENT_A1), userOf(GUARDIAN_A1),
				java.sql.Date.valueOf(matrixToday()), java.sql.Date.valueOf(matrixToday()));
		return id;
	}

	List<Case> attendanceCases() {
		List<Case> cases = new ArrayList<>();
		String schools = SCHOOLS;
		Name[] managers = SCHOOL_A_MANAGERS;
		Name[] classTeachers = { TEACHER_LEAD_A, TEACHER_SUBJECT_A };
		Name[] classBystanders = { STUDENT_A1, GUARDIAN_A1 };

		// --- rolls: the class's teachers and attendance managers; children and guardians who see the class may not; the rest cannot find it
		cases.add(school("GET class roll", HttpMethod.GET, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/attendance",
				null, 200, concat(managers, classTeachers), classBystanders));
		cases.add(school("PUT class roll", HttpMethod.PUT, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/attendance",
				(f) -> Map.of("date", matrixToday().toString(), "markAllPresent", true), 200, concat(managers, classTeachers),
				classBystanders));

		// --- a student's history: the child, their guardians, their teachers and attendance readers; nobody else is told the child exists
		cases.add(new Case("GET student attendance history", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/attendance", null,
				allow(200, concat(managers, classTeachers, classBystanders)), 404));

		// --- dashboard and reports
		cases.add(school("GET attendance dashboard", HttpMethod.GET, (f) -> schools + f.schoolA() + "/attendance/dashboard", null,
				200, concat(managers, SCHOOL_A_TEACHERS), SCHOOL_A_VISITING_ADMINS, SCHOOL_A_STUDENTS));
		cases.add(school("GET attendance report of the school", HttpMethod.GET, (f) -> schools + f.schoolA() + "/attendance/report",
				null, 200, managers, SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS));
		cases.add(school("GET attendance report of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/attendance/report?classId=" + freshClass(f), null, 200,
				concat(managers, classTeachers), classBystanders));
		cases.add(school("GET attendance report CSV of the school", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/attendance/report.csv", null, 200, managers, SCHOOL_A_VISITING_ADMINS,
				SCHOOL_A_MEMBERS));
		cases.add(school("GET attendance report CSV of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/attendance/report.csv?classId=" + freshClass(f), null, 200,
				concat(managers, classTeachers), classBystanders));

		// --- absence reports: only the child's own guardian files; the student's teachers and attendance managers decide
		cases.add(new Case("POST absence report", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/absence-reports",
				(f) -> Map.of("fromDate", matrixToday().toString(), "toDate", matrixToday().plusDays(1).toString(), "reasonCode",
						"ILLNESS", "note", "matrix"),
				allow(201, GUARDIAN_A1), 404));
		cases.add(new Case("GET student absence reports", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/students/" + userOf(STUDENT_A1) + "/absence-reports", null,
				allow(200, concat(managers, classTeachers, new Name[] { GUARDIAN_A1 })), 404));
		cases.add(school("GET class absence reports", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/absence-reports", null, 200,
				concat(managers, classTeachers), classBystanders));
		for (String verb : List.of("acknowledge", "decline")) {
			cases.add(new Case("POST " + verb + " absence report", HttpMethod.POST,
					(f) -> schools + f.schoolA() + "/absence-reports/" + absenceReport(f) + "/" + verb, null,
					merge(allow(200, concat(managers, classTeachers)), allow(403, GUARDIAN_A1)), 404));
		}
		cases.add(new Case("POST cancel absence report", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/absence-reports/" + absenceReport(f) + "/cancel", null,
				merge(allow(200, GUARDIAN_A1), allow(403, concat(managers, classTeachers))), 404));

		// --- sync: open to every signed-in user; each operation is authorized as its online endpoint (SyncApiTest)
		cases.add(new Case("POST sync", HttpMethod.POST, (f) -> "/api/v1/sync", (f) -> Map.of("operations", List.of()),
				allow(200, EVERY_ACTIVE_PERSONA)));
		return cases;
	}

	// ---- class story, school story and feed (M8a) ---------------------------------------------------------

	/** A row of the feed as the fixtures need it (the API paths are tested in the feed package). */
	private UUID feedPost(Fixture f, UUID classId, String status, boolean contribution, Name author, String authorRole) {
		UUID id = Ids.newId();
		boolean published = "PUBLISHED".equals(status);
		this.jdbc.update("""
				insert into posts (id, scope_type, school_id, class_id, author_id, author_role, status, published_at, body,
				                   is_contribution, created_at, updated_at)
				values (?, 'CLASS', ?, ?, ?, ?, ?, ?, 'matrix post', ?, now(), now())""", id, f.schoolA(), classId, userOf(author),
				authorRole, status, published ? new java.sql.Timestamp(System.currentTimeMillis()) : null, contribution);
		return id;
	}

	private UUID feedStory(Fixture f, UUID classId) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into stories (id, scope_type, school_id, class_id, author_id, author_role, caption, status, published_at,
				                     expires_at)
				values (?, 'CLASS', ?, ?, ?, 'TEACHER', 'matrix story', 'PUBLISHED', now(), now() + interval '24 hours')""", id,
				f.schoolA(), classId, userOf(TEACHER_LEAD_A));
		return id;
	}

	private String publishedPostUrl(Fixture f, String suffix) {
		UUID post = feedPost(f, freshClass(f), "PUBLISHED", false, TEACHER_LEAD_A, "TEACHER");
		return SCHOOLS + f.schoolA() + "/posts/" + post + suffix;
	}

	List<Case> feedCases() {
		List<Case> cases = new ArrayList<>();
		String schools = SCHOOLS;
		Name[] managers = SCHOOL_A_MANAGERS;
		Name[] poster = concat(managers, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A });
		Name[] moderators = concat(managers, new Name[] { TEACHER_LEAD_A });
		// who sees a published post of the fixture class: the class's teachers and managers, its student and its guardian
		Name[] viewers = concat(managers, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 });
		Name[] viewerOnly = { TEACHER_SUBJECT_A, STUDENT_A1, GUARDIAN_A1 };
		Name[] schoolSeers = concat(managers, SCHOOL_A_MEMBERS, new Name[] { GUARDIAN_A1, GUARDIAN_OF_A2 });
		Name[] schoolNoFeed = concat(SCHOOL_A_VISITING_ADMINS, new Name[] { });

		// --- posting
		cases.add(school("POST class post", HttpMethod.POST, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/posts",
				(f) -> Map.of("body", "matrix"), 201, poster, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST school post", HttpMethod.POST, (f) -> schools + f.schoolA() + "/posts", (f) -> Map.of("body", "matrix"),
				201, managers, concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS, new Name[] { GUARDIAN_A1, GUARDIAN_OF_A2 })));

		// --- feeds
		cases.add(school("GET class feed", HttpMethod.GET, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/feed", null,
				200, viewers, new Name[] { }));
		cases.add(school("GET school feed", HttpMethod.GET, (f) -> schools + f.schoolA() + "/feed", null, 200, schoolSeers,
				schoolNoFeed));
		cases.add(school("GET my posts", HttpMethod.GET, (f) -> schools + f.schoolA() + "/my-posts", null, 200,
				concat(READERS_OF_SCHOOL_A, schoolSeers), new Name[] { }));
		cases.add(school("GET feed capabilities", HttpMethod.GET, (f) -> schools + f.schoolA() + "/feed/capabilities", null, 200,
				concat(READERS_OF_SCHOOL_A, schoolSeers), new Name[] { }));
		cases.add(new Case("GET my feed", HttpMethod.GET, (f) -> "/api/v1/me/feed", null, allow(200, EVERY_ACTIVE_PERSONA)));
		cases.add(new Case("GET my stories", HttpMethod.GET, (f) -> "/api/v1/me/stories", null, allow(200, EVERY_ACTIVE_PERSONA)));

		// --- one post: readable by those who see the class, changed by its author, moderated by the lead and administrators
		cases.add(school("GET post", HttpMethod.GET, (f) -> publishedPostUrl(f, ""), null, 200, viewers, new Name[] { }));
		cases.add(school("PATCH post (author only)", HttpMethod.PATCH, (f) -> publishedPostUrl(f, ""),
				(f) -> Map.of("body", "edited"), 200, new Name[] { TEACHER_LEAD_A },
				concat(managers, viewerOnly)));
		cases.add(school("DELETE post (author or moderator)", HttpMethod.DELETE, (f) -> publishedPostUrl(f, ""), null, 204, moderators,
				viewerOnly));
		cases.add(school("POST hide post (moderators)", HttpMethod.POST, (f) -> publishedPostUrl(f, "/hide"), (f) -> Map.of(), 200,
				moderators, viewerOnly));
		cases.add(school("PUT pin post", HttpMethod.PUT, (f) -> publishedPostUrl(f, "/pin"), null, 200, moderators, viewerOnly));
		cases.add(school("POST archive post", HttpMethod.POST, (f) -> publishedPostUrl(f, "/archive"), null, 200, moderators,
				viewerOnly));
		cases.add(school("POST publish a draft (author or moderator)", HttpMethod.POST, (f) -> {
			UUID draft = feedPost(f, freshClass(f), "DRAFT", false, TEACHER_LEAD_A, "TEACHER");
			return schools + f.schoolA() + "/posts/" + draft + "/publish";
		}, null, 200, moderators, new Name[] { }));

		// --- interactions
		cases.add(school("POST comment", HttpMethod.POST, (f) -> publishedPostUrl(f, "/comments"), (f) -> Map.of("body", "nice"), 201,
				concat(managers, new Name[] { TEACHER_LEAD_A, TEACHER_SUBJECT_A, GUARDIAN_A1 }), new Name[] { STUDENT_A1 }));
		cases.add(school("GET comments", HttpMethod.GET, (f) -> publishedPostUrl(f, "/comments"), null, 200, viewers, new Name[] { }));
		cases.add(school("PUT reaction", HttpMethod.PUT, (f) -> publishedPostUrl(f, "/reaction"), (f) -> Map.of("reaction", "LIKE"), 200,
				viewers, new Name[] { }));
		cases.add(school("DELETE reaction", HttpMethod.DELETE, (f) -> publishedPostUrl(f, "/reaction"), null, 200, viewers, new Name[] { }));
		cases.add(school("POST seen", HttpMethod.POST, (f) -> publishedPostUrl(f, "/view"), null, 204, viewers, new Name[] { }));
		cases.add(school("POST report post", HttpMethod.POST, (f) -> publishedPostUrl(f, "/report"), (f) -> Map.of("reason", "SPAM"), 202,
				viewers, new Name[] { }));
		// translation is off by default: those who see the post are told so, the others find nothing
		cases.add(school("POST translation (off by default)", HttpMethod.POST, (f) -> publishedPostUrl(f, "/translation?language=am"), null,
				403, viewers, new Name[] { }));

		// --- contributions: off by default; the queue and decisions belong to the lead and managers
		cases.add(school("POST contribution (off by default)", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/contributions", (f) -> Map.of("body", "matrix"), 403,
				viewers, new Name[] { }));
		cases.add(school("GET pending contributions of a class", HttpMethod.GET,
				(f) -> schools + f.schoolA() + "/contributions/pending?classId=" + freshClass(f), null, 200, moderators, viewerOnly));
		cases.add(school("POST approve contribution", HttpMethod.POST, (f) -> {
			UUID pending = feedPost(f, freshClass(f), "PENDING_APPROVAL", true, GUARDIAN_A1, "GUARDIAN");
			return schools + f.schoolA() + "/contributions/" + pending + "/approve";
		}, (f) -> Map.of(), 200, moderators, new Name[] { GUARDIAN_A1 }));
		cases.add(school("POST reject contribution", HttpMethod.POST, (f) -> {
			UUID pending = feedPost(f, freshClass(f), "PENDING_APPROVAL", true, GUARDIAN_A1, "GUARDIAN");
			return schools + f.schoolA() + "/contributions/" + pending + "/reject";
		}, (f) -> Map.of("reason", "not suitable"), 200, moderators, new Name[] { GUARDIAN_A1 }));

		// --- stories
		cases.add(school("POST class story", HttpMethod.POST, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/stories",
				(f) -> Map.of("caption", "matrix"), 201, poster, new Name[] { STUDENT_A1, GUARDIAN_A1 }));
		cases.add(school("POST school story", HttpMethod.POST, (f) -> schools + f.schoolA() + "/stories", (f) -> Map.of("caption", "matrix"),
				201, managers, concat(SCHOOL_A_VISITING_ADMINS, SCHOOL_A_MEMBERS, new Name[] { GUARDIAN_A1, GUARDIAN_OF_A2 })));
		cases.add(school("GET class stories", HttpMethod.GET, (f) -> schools + f.schoolA() + "/classes/" + freshClass(f) + "/stories", null,
				200, viewers, new Name[] { }));
		cases.add(school("GET school stories", HttpMethod.GET, (f) -> schools + f.schoolA() + "/stories", null, 200, schoolSeers,
				schoolNoFeed));
		cases.add(school("GET story", HttpMethod.GET, (f) -> schools + f.schoolA() + "/stories/" + feedStory(f, freshClass(f)), null, 200,
				viewers, new Name[] { }));
		cases.add(school("POST story view", HttpMethod.POST,
				(f) -> schools + f.schoolA() + "/stories/" + feedStory(f, freshClass(f)) + "/view", null, 204, viewers, new Name[] { }));
		cases.add(school("DELETE story (author or moderator)", HttpMethod.DELETE,
				(f) -> schools + f.schoolA() + "/stories/" + feedStory(f, freshClass(f)), null, 204, moderators, viewerOnly));
		return cases;
	}

	@TestFactory
	Stream<DynamicTest> everyEndpointAllowsExactlyTheExpectedPersonas() {
		Fixture fixture = this.personas.fixture();
		return cases().stream().map((c) -> DynamicTest.dynamicTest(c.name(), () -> {
			List<String> mismatches = new ArrayList<>();
			Map<Name, Integer> actual = new HashMap<>();
			for (Name name : Name.values()) {
				int expected = (name == SUSPENDED_USER) ? c.anonymous() : c.allowed().getOrDefault(name, c.denied());
				int status = call(c, fixture, this.personas.get(name));
				actual.put(name, status);
				if (status != expected) {
					mismatches.add(name + ": expected " + expected + " but was " + status);
				}
			}
			int anonymous = call(c, fixture, null);
			if (anonymous != c.anonymous()) {
				mismatches.add("anonymous: expected " + c.anonymous() + " but was " + anonymous);
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
