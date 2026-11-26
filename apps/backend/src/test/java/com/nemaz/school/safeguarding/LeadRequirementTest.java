package com.nemaz.school.safeguarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.support.Persona;
import com.nemaz.school.tenancy.SettingScope;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Rule book S4.7 and decision 3: a school needs a safeguarding lead before it can switch on student messaging or student
 * contributions; the settings change is refused with a stable code until one is designated.
 */
@IntegrationTest
class LeadRequirementTest extends SafeguardingTestSupport {

	private void set(UUID school, String key, Object value) {
		this.settings.set(key, SettingScope.school(school), this.json.valueToTree(value), this.personas.platformAdmin().userId());
	}

	/** The code the change is refused with (the change must be refused). */
	private String refusal(UUID school, String key, Object value) {
		try {
			set(school, key, value);
		}
		catch (ApiException ex) {
			return ex.code();
		}
		throw new AssertionError(key + " was not refused");
	}

	@Test
	void everyStudentFeatureSettingIsRefusedWithoutALeadAndAllowedWithOne() throws Exception {
		Msg m = school("lead-req");
		UUID school = m.schoolId();
		for (String key : new String[] { "messaging.student.teacher_direct.enabled", "messaging.student.student_direct.enabled",
				"messaging.student.media_enabled", "feed.student_contributions.enabled" }) {
			assertThat(refusal(school, key, true)).as(key).isEqualTo("safeguarding.lead_required");
		}
		assertThat(refusal(school, "messaging.student.group_create", "STUDENTS_WITH_APPROVAL")).isEqualTo("safeguarding.lead_required");
		// switching things OFF, and settings that are not student features, are never blocked
		set(school, "messaging.student.teacher_direct.enabled", false);
		set(school, "messaging.parent_teacher.enabled", true);
		set(school, "messaging.student.group_create", "TEACHER_ONLY");
		set(school, "messaging.student.enabled", false);
		// the default is on: turning it on from off is the guarded change
		assertThat(refusal(school, "messaging.student.enabled", true)).isEqualTo("safeguarding.lead_required");
		// a lead whose account is not ACTIVE does not count, an ACTIVE one does
		Persona lead = safeguardingLead(m, "lead-req-lead");
		this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", lead.userId());
		assertThat(refusal(school, "messaging.student.teacher_direct.enabled", true)).isEqualTo("safeguarding.lead_required");
		this.jdbc.update("update users set status = 'ACTIVE' where id = ?", lead.userId());
		set(school, "messaging.student.enabled", true);
		set(school, "messaging.student.teacher_direct.enabled", true);
		set(school, "feed.student_contributions.enabled", true);
		set(school, "messaging.student.group_create", "STUDENTS_WITH_APPROVAL");
	}

	@Test
	void theRefusalIsAClearStableCodeOverHttpInBothLanguages() throws Exception {
		Msg m = school("lead-req-http");
		Persona platformAdmin = this.personas.platformAdmin();
		Map<String, Object> body = body("key", "messaging.student.student_direct.enabled", "value", true, "scope",
				Map.of("type", "SCHOOL", "schoolId", m.schoolId()));
		JsonNode problem = call(HttpMethod.PUT, "/api/v1/platform/settings", platformAdmin, body, 409);
		assertThat(code(problem)).isEqualTo("safeguarding.lead_required");
		assertThat(problem.get("detail").asString()).contains("safeguarding lead");
		// the Amharic text exists
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/platform/settings")
			.with(platformAdmin.bearer())
			.header("Accept-Language", "am")
			.contentType("application/json")
			.content(this.json.writeValueAsString(body));
		String amharic = this.mvc.perform(request).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(amharic).contains("safeguarding.lead_required").doesNotContain("safeguarding lead");
		// after the school designates a lead the same change is accepted
		safeguardingLead(m, "lead-req-http-lead");
		call(HttpMethod.PUT, "/api/v1/platform/settings", platformAdmin, body, 200);
	}

	@Test
	void switchingItOnAboveTheSchoolIsRefusedAndClearingAnOverrideThatWouldEnableIsToo() throws Exception {
		Msg m = school("lead-req-scope");
		Persona platformAdmin = this.personas.platformAdmin();
		JsonNode country = call(HttpMethod.PUT, "/api/v1/platform/settings", platformAdmin,
				body("key", "messaging.student.student_direct.enabled", "value", true, "scope", Map.of("type", "COUNTRY", "countryCode", "KE")), 409);
		assertThat(code(country)).isEqualTo("safeguarding.lead_required_scope");
		assertThat(code(call(HttpMethod.PUT, "/api/v1/platform/settings", platformAdmin,
				body("key", "messaging.student.student_direct.enabled", "value", true, "scope", Map.of("type", "PLATFORM")), 409))).isEqualTo("safeguarding.lead_required_scope");
		// a school override of false hides a platform value of true: clearing it would enable the feature, so it is guarded too
		set(m.schoolId(), "messaging.student.enabled", false);
		assertThat(statusOf(HttpMethod.DELETE, "/api/v1/platform/settings?key=messaging.student.enabled&scopeType=SCHOOL&schoolId=" + m.schoolId(), platformAdmin, null))
			.isEqualTo(409);
		safeguardingLead(m, "lead-req-scope-lead");
		assertThat(statusOf(HttpMethod.DELETE, "/api/v1/platform/settings?key=messaging.student.enabled&scopeType=SCHOOL&schoolId=" + m.schoolId(), platformAdmin, null))
			.isEqualTo(204);
	}

	@Test
	void theRequirementItselfCannotBeSwitchedOffBySchoolsOnlyByThePlatform() throws Exception {
		Msg m = school("lead-req-off");
		assertThat(refusal(m.schoolId(), "safeguarding.require_lead_for_student_messaging", false)).isEqualTo("setting.scope_not_allowed");
		assertThat(refusal(m.schoolId(), "safeguarding.report.daily_limit", 500)).isEqualTo("setting.scope_not_allowed");
	}

	@Test
	void theCapabilityEndpointTellsTheScreensWhyAndTheAdministratorDesignatesAndRemovesLeads() throws Exception {
		Msg m = school("lead-cap");
		String base = "/api/v1/schools/" + m.schoolId() + "/safeguarding";
		JsonNode before = call(HttpMethod.GET, base + "/capability", m.admin(), null, 200);
		assertThat(before.get("hasLead").asBoolean()).isFalse();
		assertThat(before.get("leadCount").asInt()).isZero();
		assertThat(before.get("leadRequiredForStudentFeatures").asBoolean()).isTrue();
		assertThat(before.get("guardedSettings").toString()).contains("messaging.student.teacher_direct.enabled");
		assertThat(before.get("studentFeaturesOnWithoutLead").asBoolean()).isTrue(); // student messaging is on by default
		// teachers and strangers cannot see it
		assertThat(statusOf(HttpMethod.GET, base + "/capability", m.lead(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, base + "/capability", m.otherAdmin(), null)).isEqualTo(404);
		// the lead must be an ACTIVE member of staff
		assertThat(code(call(HttpMethod.POST, base + "/leads", m.admin(), body("userId", m.student1().userId()), 400))).isEqualTo("safeguarding.lead_must_be_staff");
		assertThat(code(call(HttpMethod.POST, base + "/leads", m.admin(), body("userId", m.g1().userId()), 400))).isEqualTo("safeguarding.lead_must_be_staff");
		assertThat(statusOf(HttpMethod.POST, base + "/leads", m.lead(), body("userId", m.co().userId()))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.POST, base + "/leads", m.otherAdmin(), body("userId", m.co().userId()))).isEqualTo(404);
		JsonNode designated = call(HttpMethod.POST, base + "/leads", m.admin(), body("userId", m.co().userId()), 201);
		assertThat(designated.get("userId").asString()).isEqualTo(m.co().userId().toString());
		call(HttpMethod.POST, base + "/leads", m.admin(), body("userId", m.co().userId()), 201); // repeating changes nothing
		assertThat(this.jdbc.queryForObject("select count(*) from role_assignments where user_id = ? and role_code = 'SAFEGUARDING_LEAD' and status = 'ACTIVE'", Integer.class, m.co().userId())).isEqualTo(1);
		JsonNode after = call(HttpMethod.GET, base + "/capability", m.admin(), null, 200);
		assertThat(after.get("hasLead").asBoolean()).isTrue();
		assertThat(call(HttpMethod.GET, base + "/leads", m.admin(), null, 200)).hasSize(1);
		// the new lead can read incidents at once (the grant cache was evicted)
		UUID incident = raise(m.lead(), m, "LOW");
		assertThat(statusOf(HttpMethod.GET, incident(incident), m.co(), null)).isEqualTo(200);
		// the last lead cannot be removed while student features are on: designate the next one first
		assertThat(code(call(HttpMethod.DELETE, base + "/leads/" + m.co().userId(), m.admin(), null, 409))).isEqualTo("safeguarding.last_lead_required");
		call(HttpMethod.POST, base + "/leads", m.admin(), body("userId", m.staff().userId()), 201);
		call(HttpMethod.DELETE, base + "/leads/" + m.co().userId(), m.admin(), null, 204);
		assertThat(statusOf(HttpMethod.GET, incident(incident), m.co(), null)).isEqualTo(404);
		assertThat(code(call(HttpMethod.DELETE, base + "/leads/" + m.co().userId(), m.admin(), null, 404))).isEqualTo("safeguarding.lead_not_found");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where school_id = ? and action in ('safeguarding.lead.designate', 'safeguarding.lead.remove')", Integer.class, m.schoolId())).isEqualTo(3);
	}

}
