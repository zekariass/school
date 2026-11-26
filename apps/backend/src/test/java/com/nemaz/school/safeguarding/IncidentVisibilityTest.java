package com.nemaz.school.safeguarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Rule book S4.2 and decision 2: the incident visibility matrix. Only the safeguarding lead of the school and the platform
 * leads can read an incident; everybody else gets 404 as if nothing existed.
 */
@IntegrationTest
class IncidentVisibilityTest extends SafeguardingTestSupport {

	@Test
	void theIncidentVisibilityMatrixAnswers404AsIfNothingExistedToEverybodyExceptTheLeads() throws Exception {
		Msg m = school("vis");
		Msg other = school("vis-other");
		Persona lead = safeguardingLead(m, "vis-lead");
		Persona otherLead = safeguardingLead(other, "vis-other-lead");
		Persona platformLead = this.personas.platformLead("vis-platform-lead");
		// a principal who is not a lead: administrator staff with the school-administrator role
		UUID principalId = this.personas.newStaff(m.schoolId(), "vis-principal", "ADMINISTRATOR");
		this.personas.grant(principalId, RoleCodes.SCHOOL_ADMIN, AccessScope.school(m.schoolId()));
		Persona principal = this.personas.personaFor("vis-principal", principalId);
		Persona orgAdmin = this.personas.withRole("vis-country-admin", RoleCodes.COUNTRY_ADMIN, AccessScope.country("ET"));
		Persona moderator = this.personas.withRole("vis-moderator", RoleCodes.SCHOOL_ADMIN, AccessScope.school(m.schoolId()));
		UUID incident = raise(m.co(), m, "HIGH"); // raised by a teacher: the reporter

		Map<String, Persona> readers = new LinkedHashMap<>();
		readers.put("lead of the school", lead);
		readers.put("platform lead", platformLead);
		Map<String, Persona> outsiders = new LinkedHashMap<>();
		outsiders.put("lead of another school", otherLead);
		outsiders.put("school administrator", m.admin());
		outsiders.put("principal who is not a lead", principal);
		outsiders.put("moderator", moderator);
		outsiders.put("country administrator", orgAdmin);
		outsiders.put("platform administrator", this.personas.platformAdmin());
		outsiders.put("platform moderator", this.personas.platformModerator("vis-pm"));
		outsiders.put("support agent", this.personas.supportAgent("vis-support"));
		outsiders.put("teacher", m.lead());
		outsiders.put("reporter (the teacher who raised it)", m.co());
		outsiders.put("student", m.student1());
		outsiders.put("guardian", m.g1());
		outsiders.put("stranger", m.stranger());

		String platformList = "/api/v1/platform/safeguarding/incidents";
		for (Map.Entry<String, Persona> reader : readers.entrySet()) {
			assertThat(statusOf(HttpMethod.GET, incident(incident), reader.getValue(), null)).as("GET by " + reader.getKey()).isEqualTo(200);
			assertThat(statusOf(HttpMethod.POST, incident(incident) + "/open", reader.getValue(), body("reason", "TRIAGE"))).as("open by " + reader.getKey()).isEqualTo(200);
			assertThat(statusOf(HttpMethod.GET, incidents(m), reader.getValue(), null)).as("school list by " + reader.getKey()).isEqualTo(200);
		}
		assertThat(statusOf(HttpMethod.GET, platformList, platformLead, null)).isEqualTo(200);
		for (Map.Entry<String, Persona> outsider : outsiders.entrySet()) {
			Persona who = outsider.getValue();
			String label = outsider.getKey();
			JsonNode read = call(HttpMethod.GET, incident(incident), who, null, 404);
			assertThat(code(read)).as("GET by " + label).isEqualTo("safeguarding.not_found");
			assertThat(code(call(HttpMethod.POST, incident(incident) + "/open", who, body("reason", "TRIAGE"), 404))).as("open by " + label).isEqualTo("safeguarding.not_found");
			assertThat(statusOf(HttpMethod.GET, incidents(m), who, null)).as("school list by " + label).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, platformList, who, null)).as("platform list by " + label).isEqualTo(404);
			// no write either
			assertThat(statusOf(HttpMethod.POST, incident(incident) + "/notes", who, body("note", "an attempt at a note"))).as("note by " + label).isEqualTo(404);
			assertThat(statusOf(HttpMethod.POST, incident(incident) + "/status", who, body("status", "INVESTIGATING"))).as("status by " + label).isEqualTo(404);
			assertThat(statusOf(HttpMethod.POST, incident(incident) + "/assign", who, body("assigneeId", lead.userId()))).as("assign by " + label).isEqualTo(404);
			assertThat(statusOf(HttpMethod.PUT, incident(incident) + "/legal-hold", who, body("held", true))).as("hold by " + label).isEqualTo(404);
		}
		// the answer for an unknown id is exactly the same
		JsonNode unknown = call(HttpMethod.GET, incident(UUID.randomUUID()), lead, null, 404);
		assertThat(code(unknown)).isEqualTo("safeguarding.not_found");
		// nothing changed
		assertThat(this.jdbc.queryForObject("select status from safeguarding_incidents where id = ?", String.class, incident)).isEqualTo("OPEN");
		assertThat(this.jdbc.queryForObject("select legal_hold from safeguarding_incidents where id = ?", Boolean.class, incident)).isFalse();
	}

	@Test
	void anotherSchoolsIncidentsAreInvisibleToALeadAndAListShowsOnlyMetadata() throws Exception {
		Msg m = school("vis-list");
		Msg other = school("vis-list-other");
		Persona lead = safeguardingLead(m, "vis-list-lead");
		UUID mine = raise(m.co(), m, "MEDIUM");
		raise(other.co(), other, "MEDIUM");
		JsonNode page = call(HttpMethod.GET, incidents(m), lead, null, 200);
		assertThat(page.get("items")).hasSize(1);
		JsonNode item = page.get("items").get(0);
		assertThat(item.get("id").asString()).isEqualTo(mine.toString());
		// ids, category, severity, status, school, dates: never any text
		assertThat(item.propertyNames()).doesNotContain("narrative", "note", "notes", "referredTo", "description");
		assertThat(page.toString()).doesNotContain("CANARY");
		assertThat(statusOf(HttpMethod.GET, incident(mine), safeguardingLead(other, "vis-list-other-lead"), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, incidents(other), lead, null)).isEqualTo(404);
	}

	@Test
	void whoCanRaiseAConcernIsActiveStaffOnly() throws Exception {
		Msg m = school("vis-raise");
		for (Persona nobody : new Persona[] { m.student1(), m.g1(), m.stranger(), m.otherAdmin() }) {
			assertThat(statusOf(HttpMethod.POST, concerns(m), nobody, body("category", "CONDUCT", "severity", "LOW", "narrative", NARRATIVE))).isEqualTo(404);
		}
		// a teacher, a non-teaching staff member and a lead can
		raise(m.lead(), m, "LOW");
		raise(m.staff(), m, "LOW");
		raise(safeguardingLead(m, "vis-raise-lead"), m, "LOW");
		// the reporter cannot read it back, and the answer is the id only
		JsonNode created = call(HttpMethod.POST, concerns(m), m.co(), body("category", "WELLBEING", "severity", "LOW", "narrative", NARRATIVE), 201);
		assertThat(created.propertyNames()).containsExactly("id");
		assertThat(statusOf(HttpMethod.GET, incident(UUID.fromString(created.get("id").asString())), m.co(), null)).isEqualTo(404);
		// validation
		assertThat(code(call(HttpMethod.POST, concerns(m), m.lead(), body("category", "NOPE", "severity", "LOW", "narrative", NARRATIVE), 400))).isEqualTo("safeguarding.category_invalid");
		assertThat(code(call(HttpMethod.POST, concerns(m), m.lead(), body("category", "OTHER", "severity", "NOPE", "narrative", NARRATIVE), 400))).isEqualTo("safeguarding.severity_invalid");
		assertThat(statusOf(HttpMethod.POST, concerns(m), m.lead(), body("category", "OTHER", "severity", "LOW", "narrative", "short"))).isEqualTo(400);
		UUID aStudentOfAnotherSchool = other(m);
		assertThat(code(call(HttpMethod.POST, concerns(m), m.lead(), body("category", "OTHER", "severity", "LOW", "narrative", NARRATIVE,
				"concernedStudentId", aStudentOfAnotherSchool), 400))).isEqualTo("safeguarding.student_invalid");
	}

	private UUID other(Msg m) {
		return this.personas.newStudent(this.personas.newSchool("vis-raise-elsewhere"), "elsewhere");
	}

	@Test
	void aLeadWhoIsInvolvedOrTheGuardianOfTheConcernedStudentNeverReadsTheIncident() throws Exception {
		Msg m = school("vis-conflict");
		Persona lead = safeguardingLead(m, "vis-conflict-lead");
		Persona involvedLead = safeguardingLead(m, "vis-conflict-involved");
		Persona guardianLead = safeguardingLead(m, "vis-conflict-guardian");
		this.personas.linkGuardian(m.s1(), guardianLead.userId(), "ACTIVE", false);
		JsonNode created = call(HttpMethod.POST, concerns(m), m.co(), body("category", "CONDUCT", "severity", "HIGH", "narrative", NARRATIVE,
				"concernedStudentId", m.s1(), "involvedUserIds", java.util.List.of(involvedLead.userId())), 201);
		UUID id = UUID.fromString(created.get("id").asString());
		assertThat(open(lead, id).get("narrative").asString()).isEqualTo(NARRATIVE);
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", involvedLead, body("reason", "TRIAGE"))).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", guardianLead, body("reason", "TRIAGE"))).isEqualTo(404);
		assertThat(call(HttpMethod.GET, incidents(m), involvedLead, null, 200).get("items")).isEmpty();
	}

}
