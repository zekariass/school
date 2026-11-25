package com.nemaz.school.consent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.consent.ConsentStatus.Effective;
import com.nemaz.school.consent.ConsentStatus.Reason;
import com.nemaz.school.guardians.GuardianTestSupport;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Effective consent (most restrictive wins), versions and re-consent, paper consent, restricted guardians, the class
 * summary and policy texts with language fallback.
 */
@IntegrationTest
class ConsentTest extends GuardianTestSupport {

	@Autowired
	ConsentService consent;

	private UUID countryVersion(String type, String language) {
		return this.jdbc.queryForObject("""
				select v.id from policy_versions v join policy_documents d on d.id = v.policy_document_id
				where d.policy_type = ? and d.country_code = 'ET' and d.school_id is null and v.language_code = ?
				  and v.version_label = '2026-11-draft'""", UUID.class, type, language);
	}

	private JsonNode decide(Persona guardian, UUID studentId, String type, UUID versionId, String decision, int status)
			throws Exception {
		return post("/api/v1/me/consents", guardian, Map.of("studentId", studentId, "consentType", type,
				"policyVersionId", versionId, "decision", decision), status);
	}

	private Persona guardianOf(Setup s, String label) {
		UUID adult = this.personas.newUser(label).userId();
		this.personas.linkGuardian(s.studentId(), adult, "ACTIVE", false);
		return as(adult);
	}

	@Test
	void unknownByDefaultAndTheMostRestrictiveDecisionWins() throws Exception {
		Setup s = setup("Consent rule school");
		UUID media = countryVersion("MEDIA_PUBLICATION", "am");
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.MEDIA_PUBLICATION))
			.extracting(ConsentStatus::status, ConsentStatus::reason)
			.containsExactly(Effective.UNKNOWN, Reason.NO_DECISION);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isFalse();

		Persona mother = guardianOf(s, "mother");
		Persona father = guardianOf(s, "father");
		JsonNode granted = decide(mother, s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 201);
		assertThat(granted.get("effectiveStatus").get("status").asString()).isEqualTo("GRANTED");
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();
		// the other kind of consent is separate
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.ACCOUNT_PARTICIPATION).status())
			.isEqualTo(Effective.UNKNOWN);

		decide(father, s.studentId(), "MEDIA_PUBLICATION", media, "DENIED", 201);
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.MEDIA_PUBLICATION))
			.extracting(ConsentStatus::status, ConsentStatus::reason)
			.containsExactly(Effective.DENIED, Reason.DENIED);
		decide(father, s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 201);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();
		decide(mother, s.studentId(), "MEDIA_PUBLICATION", media, "WITHDRAWN", 201);
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.MEDIA_PUBLICATION))
			.extracting(ConsentStatus::status, ConsentStatus::reason)
			.containsExactly(Effective.DENIED, Reason.WITHDRAWN);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isFalse();

		// records are append-only
		assertThat(this.jdbc.queryForObject("select count(*) from consent_records where subject_user_id = ?",
				Integer.class, s.studentId())).isEqualTo(4);
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> this.jdbc.update("update consent_records set decision = 'GRANTED' where subject_user_id = ?",
					s.studentId()))
			.hasMessageContaining("append-only");

		// a revoked guardian's refusal no longer counts
		UUID motherLink = this.jdbc.queryForObject(
				"select id from student_guardians where guardian_id = ? and student_id = ?", UUID.class, mother.userId(),
				s.studentId());
		post(school(s.schoolId()) + "/guardian-links/" + motherLink + "/revoke", s.admin(), null, 200);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();
	}

	@Test
	void restrictedPendingAndStrangerAdultsCannotDecideAndOldDecisionsOfRestrictedLinksAreIgnored() throws Exception {
		Setup s = setup("Restricted consent school");
		UUID media = countryVersion("MEDIA_PUBLICATION", "en");
		Persona guardian = guardianOf(s, "restricted-later");
		decide(guardian, s.studentId(), "MEDIA_PUBLICATION", media, "DENIED", 201);
		UUID link = this.jdbc.queryForObject("select id from student_guardians where guardian_id = ?", UUID.class,
				guardian.userId());
		call(HttpMethod.PATCH, school(s.schoolId()) + "/guardian-links/" + link, s.admin(), Map.of("custodyRestricted", true),
				200);
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.MEDIA_PUBLICATION).status())
			.isEqualTo(Effective.UNKNOWN);
		assertThat(errorCode(decide(guardian, s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 404)))
			.isEqualTo("consent.child_not_found");

		UUID pendingAdult = this.personas.newUser("pending").userId();
		this.personas.linkGuardian(s.studentId(), pendingAdult, "PENDING", false);
		decide(as(pendingAdult), s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 404);
		decide(this.personas.newUser("stranger"), s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 404);
		// without can_give_consent
		Persona noConsent = guardianOf(s, "no-consent");
		this.jdbc.update("update student_guardians set can_give_consent = false where guardian_id = ?", noConsent.userId());
		decide(noConsent, s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 404);
		// the school cannot record paper consent for a restricted link either
		JsonNode problem = post(school(s.schoolId()) + "/students/" + s.studentId() + "/consents", s.admin(),
				Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", media, "decision", "GRANTED",
						"guardianLinkId", link, "method", "PAPER_RECORDED_BY_SCHOOL"),
				409);
		assertThat(errorCode(problem)).isEqualTo("consent.guardian_not_eligible");
	}

	@Test
	void paperConsentFromAGuardianWithoutAnAccountCounts() throws Exception {
		Setup s = setup("Paper consent school");
		UUID media = countryVersion("MEDIA_PUBLICATION", "am");
		Persona mother = guardianOf(s, "app-mother");
		decide(mother, s.studentId(), "MEDIA_PUBLICATION", media, "GRANTED", 201);
		JsonNode paper = post(school(s.schoolId()) + "/students/" + s.studentId() + "/consents", s.admin(),
				Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", media, "decision", "DENIED", "guardianName",
						"Grandfather Tadesse", "method", "PAPER_RECORDED_BY_SCHOOL"),
				201);
		assertThat(paper.get("effectiveStatus").get("status").asString()).isEqualTo("DENIED");
		assertThat(this.jdbc.queryForMap("select granted_by_user_id, granted_by_name, recorded_by, method from consent_records where id = ?::uuid",
				paper.get("id").asString()))
			.containsEntry("granted_by_user_id", null)
			.containsEntry("granted_by_name", "Grandfather Tadesse")
			.containsEntry("recorded_by", s.admin().userId());
		// the same named guardian changes their mind on paper (case-insensitive name)
		post(school(s.schoolId()) + "/students/" + s.studentId() + "/consents", s.admin(),
				Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", media, "decision", "GRANTED", "guardianName",
						"grandfather tadesse", "method", "VERBAL_RECORDED_BY_SCHOOL"),
				201);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();
		JsonNode history = get(school(s.schoolId()) + "/students/" + s.studentId() + "/consents", s.admin());
		assertThat(history.get("records")).hasSize(3);
		assertThat(history.get("mediaPublication").get("status").asString()).isEqualTo("GRANTED");
		// no names in the audit log
		assertThat(this.jdbc.queryForList("select coalesce(metadata::text, '') from audit_logs where school_id = ?",
				String.class, s.schoolId())).noneMatch((m) -> m.toLowerCase().contains("tadesse"));
		// a teacher may not record it
		post(school(s.schoolId()) + "/students/" + s.studentId() + "/consents", s.lead(),
				Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", media, "decision", "GRANTED", "guardianName",
						"X", "method", "PAPER_RECORDED_BY_SCHOOL"),
				403);
	}

	@Test
	void aVersionThatRequiresReconsentMakesOlderGrantsUnknownAndAsksGuardiansAgain() throws Exception {
		Setup s = setup("Reconsent school");
		Persona guardian = guardianOf(s, "reconsent-parent");
		String policies = school(s.schoolId()) + "/policies";
		JsonNode document = post(policies, s.admin(), Map.of("policyType", "MEDIA_PUBLICATION", "title", "Our photos"), 201);
		String docUrl = policies + "/" + document.get("id").asString();
		JsonNode v1 = post(docUrl + "/versions", s.admin(),
				Map.of("versionLabel", "v1", "languageCode", "am", "content", "Version one (am)"), 201);
		// a draft is not in effect yet: the country default still applies
		assertThat(get("/api/v1/policies/MEDIA_PUBLICATION?schoolId=" + s.schoolId() + "&lang=am", null)
			.get("versionLabel").asString()).isEqualTo("2026-11-draft");
		post(docUrl + "/versions/" + v1.get("id").asString() + "/publish", s.admin(), null, 200);
		JsonNode text = get("/api/v1/policies/MEDIA_PUBLICATION?schoolId=" + s.schoolId() + "&lang=en", null);
		// no English version: falls back to any language
		assertThat(text.get("languageCode").asString()).isEqualTo("am");
		assertThat(text.get("content").asString()).isEqualTo("Version one (am)");
		call(HttpMethod.PUT, docUrl + "/versions/" + v1.get("id").asString(), s.admin(), Map.of("content", "changed"), 409);

		UUID v1Id = UUID.fromString(v1.get("id").asString());
		// the country version is no longer current for this school
		assertThat(errorCode(decide(guardian, s.studentId(), "MEDIA_PUBLICATION", countryVersion("MEDIA_PUBLICATION", "en"),
				"GRANTED", 409))).isEqualTo("consent.version_outdated");
		decide(guardian, s.studentId(), "MEDIA_PUBLICATION", v1Id, "GRANTED", 201);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();

		JsonNode v2 = post(docUrl + "/versions", s.admin(), Map.of("versionLabel", "v2", "languageCode", "en", "content",
				"Version two", "requiresReconsent", true), 201);
		post(docUrl + "/versions/" + v2.get("id").asString() + "/publish", s.admin(), null, 200);
		assertThat(this.consent.status(s.studentId(), s.schoolId(), ConsentType.MEDIA_PUBLICATION))
			.extracting(ConsentStatus::status, ConsentStatus::reason)
			.containsExactly(Effective.UNKNOWN, Reason.RECONSENT_REQUIRED);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where user_id = ? and type_code = 'CONSENT_REQUESTED' and source_type = 'policy_version'",
				Integer.class, guardian.userId())).isOne());
		JsonNode mine = get("/api/v1/me/consents", guardian);
		JsonNode item = mine.get("children").get(0).get("items").get(1);
		assertThat(item.get("consentType").asString()).isEqualTo("MEDIA_PUBLICATION");
		assertThat(item.get("outdated").asBoolean()).isTrue();
		assertThat(item.get("needsDecision").asBoolean()).isTrue();
		assertThat(item.get("currentVersion").get("versionLabel").asString()).isEqualTo("v2");
		decide(guardian, s.studentId(), "MEDIA_PUBLICATION", UUID.fromString(v2.get("id").asString()), "GRANTED", 201);
		assertThat(this.consent.mediaPublicationAllowed(s.studentId(), s.schoolId())).isTrue();
	}

	@Test
	void theClassSummaryShowsStatusesWithoutGuardians() throws Exception {
		Setup s = setup("Summary school");
		Persona guardian = guardianOf(s, "summary-parent");
		decide(guardian, s.studentId(), "ACCOUNT_PARTICIPATION", countryVersion("ACCOUNT_PARTICIPATION", "am"), "GRANTED",
				201);
		JsonNode summary = get(school(s.schoolId()) + "/classes/" + s.classId() + "/consent-summary", s.lead());
		assertThat(summary).hasSize(1);
		assertThat(summary.get(0).get("accountParticipation").get("status").asString()).isEqualTo("GRANTED");
		assertThat(summary.get(0).get("mediaPublication").get("status").asString()).isEqualTo("UNKNOWN");
		assertThat(summary.toString()).doesNotContain(guardian.userId().toString()).doesNotContain("summary-parent");
		call(HttpMethod.GET, school(s.schoolId()) + "/classes/" + s.classId() + "/consent-summary", guardian, null, 403);
	}

	@Test
	void adultsAcceptTermsForThemselvesAndPolicyTextsFallBackToEnglish() throws Exception {
		Persona adult = this.personas.newUser("terms-adult");
		UUID terms = countryVersion("TERMS", "en");
		JsonNode recorded = post("/api/v1/me/consents", adult, Map.of("consentType", "TERMS", "policyVersionId", terms,
				"decision", "GRANTED"), 201);
		assertThat(recorded.has("studentId")).isFalse();
		JsonNode mine = get("/api/v1/me/consents", adult);
		assertThat(mine.get("account").get(0).get("myDecision").get("decision").asString()).isEqualTo("GRANTED");
		assertThat(mine.get("account").get(0).get("needsDecision").asBoolean()).isFalse();
		assertThat(mine.get("account").get(1).get("needsDecision").asBoolean()).isTrue();
		post("/api/v1/me/consents", adult, Map.of("consentType", "MEDIA_PUBLICATION", "policyVersionId", terms,
				"decision", "GRANTED"), 400);

		JsonNode oromo = get("/api/v1/policies/PRIVACY?lang=om", null);
		assertThat(oromo.get("languageCode").asString()).isEqualTo("en");
		assertThat(oromo.get("content").asString()).startsWith("DRAFT PLACEHOLDER");
		JsonNode amharic = get("/api/v1/policies/PRIVACY?lang=am", null);
		assertThat(amharic.get("languageCode").asString()).isEqualTo("am");
		assertThat(Instant.parse(amharic.get("publishedAt").asString())).isBefore(Instant.now());
		call(HttpMethod.GET, "/api/v1/policies/PRIVACY?country=KE", null, null, 404);
	}

}
