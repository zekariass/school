package com.nemaz.school.safeguarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.moderation.LegalHold;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Encryption at rest in the database, the legal-hold port, and the per-country emergency text. */
@IntegrationTest
class EncryptionAndHoldTest extends SafeguardingTestSupport {

	@Autowired
	LegalHold legalHold;

	@Test
	void theNarrativeAndTheNotesAreCiphertextInTheDatabaseWithAFreshNoncePerRecord() throws Exception {
		Msg m = school("enc");
		Persona lead = safeguardingLead(m, "enc-lead");
		String text = "CANARY-ENCRYPTED the same words twice";
		UUID first = raise(m.co(), m, "CONDUCT", "LOW", text);
		UUID second = raise(m.co(), m, "CONDUCT", "LOW", text);
		String a = envelope(first);
		String b = envelope(second);
		assertThat(a).startsWith("v1.t1.").doesNotContain("CANARY").doesNotContain("ENCRYPTED");
		assertThat(a.split("\\.")[2]).isNotEqualTo(b.split("\\.")[2]); // the nonces differ
		assertThat(a.split("\\.")[3]).isNotEqualTo(b.split("\\.")[3]);
		assertThat(this.jdbc.queryForObject("select count(*) from safeguarding_incidents where narrative_enc like '%CANARY%' or narrative_enc like '%same words%'", Integer.class)).isZero();
		// a note too, and the lead reads both decrypted
		JsonNode note = call(HttpMethod.POST, incident(first) + "/notes", lead, body("note", "CANARY-NOTE same words"), 201);
		String noteEnvelope = this.jdbc.queryForObject("select note_enc from safeguarding_incident_notes where id = ?", String.class, UUID.fromString(note.get("id").asString()));
		assertThat(noteEnvelope).startsWith("v1.t1.").doesNotContain("CANARY");
		JsonNode detail = open(lead, first);
		assertThat(detail.get("narrative").asString()).isEqualTo(text);
		assertThat(detail.toString()).contains("CANARY-NOTE same words");
		// a ciphertext moved to another incident does not decrypt: the record's own id is authenticated
		this.jdbc.update("update safeguarding_incidents set narrative_enc = ? where id = ?", a, second);
		assertThat(statusOf(HttpMethod.POST, incident(second) + "/open", lead, body("reason", "TRIAGE"))).isEqualTo(500);
		this.jdbc.update("update safeguarding_incidents set narrative_enc = ? where id = ?", b, second);
		assertThat(open(lead, second).get("narrative").asString()).isEqualTo(text);
	}

	@Test
	void heldDataIsRefusedForErasureThroughTheLegalHoldPort() throws Exception {
		Msg m = school("hold-port");
		Persona lead = safeguardingLead(m, "hold-port-lead");
		assertThat(this.legalHold.isHeld(m.student1().userId())).isFalse();
		this.legalHold.requireNotHeld(m.student1().userId());
		// a held CASE: its subject and its reporters are held
		UUID post = post(m, m.lead().userId(), "TEACHER", "held post");
		report(m.g1(), "POST", post, "SPAM");
		UUID caseId = caseOf("POST", post);
		call(HttpMethod.PUT, CASES + caseId + "/legal-hold", lead, body("held", true), 200);
		assertThat(this.legalHold.isHeld(m.lead().userId())).isTrue();
		assertThat(this.legalHold.isHeld(m.g1().userId())).isTrue();
		assertThat(this.legalHold.isHeld(m.g2().userId())).isFalse();
		assertThatThrownBy(() -> this.legalHold.requireNotHeld(m.g1().userId())).isInstanceOfSatisfying(ApiException.class,
				(ex) -> assertThat(ex.code()).isEqualTo("legal_hold.erasure_refused"));
		call(HttpMethod.PUT, CASES + caseId + "/legal-hold", lead, body("held", false), 200);
		assertThat(this.legalHold.isHeld(m.g1().userId())).isFalse();
		// a held INCIDENT: the concerned student, the involved and the raiser are held
		JsonNode created = call(HttpMethod.POST, concerns(m), m.co(), body("category", "CONDUCT", "severity", "LOW", "narrative", NARRATIVE,
				"concernedStudentId", m.s2(), "involvedUserIds", java.util.List.of(m.subject().userId())), 201);
		UUID incident = UUID.fromString(created.get("id").asString());
		call(HttpMethod.PUT, incident(incident) + "/legal-hold", lead, body("held", true), 200);
		for (UUID held : new UUID[] { m.s2(), m.subject().userId(), m.co().userId() }) {
			assertThat(this.legalHold.isHeld(held)).as(held.toString()).isTrue();
		}
		assertThat(this.legalHold.isHeld(m.student3().userId())).isFalse();
		call(HttpMethod.PUT, incident(incident) + "/legal-hold", lead, body("held", false), 200);
		assertThat(this.legalHold.isHeld(m.s2())).isFalse();
	}

	@Test
	void theEmergencyInformationOfACountryIsServedInTheCallersLanguageAndFlaggedUnreviewed() throws Exception {
		Msg m = school("emergency");
		JsonNode english = call(HttpMethod.GET, "/api/v1/safety/emergency-info?country=ET", m.student1(), null, 200);
		assertThat(english.get("country").asString()).isEqualTo("ET");
		assertThat(english.get("text").asString()).containsIgnoringCase("child is in danger").containsIgnoringCase("authorities");
		assertThat(english.get("reviewed").asBoolean()).isFalse(); // a placeholder until the owner supplies the wording
		var amharic = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/safety/emergency-info?country=et")
			.with(m.student1().bearer())
			.header("Accept-Language", "am");
		String body = this.mvc.perform(amharic).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).contains("ሕፃን").doesNotContain("child is in danger");
		assertThat(statusOf(HttpMethod.GET, "/api/v1/safety/emergency-info?country=KE", m.student1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/safety/emergency-info?country=XYZ", m.student1(), null)).isEqualTo(400);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/safety/emergency-info?country=ET", null, null)).isEqualTo(401);
		// the seed is flagged in the data
		assertThat(this.jdbc.queryForObject("select reviewed from emergency_information where country_code = 'ET'", Boolean.class)).isFalse();
	}

}
