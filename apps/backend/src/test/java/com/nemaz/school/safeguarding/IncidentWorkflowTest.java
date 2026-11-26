package com.nemaz.school.safeguarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.safeguarding.application.SafeguardingRetention;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book S4.3 to S4.8 and S6: creation, reads, workflow, notifications, manual fields, legal hold, retention. */
@IntegrationTest
class IncidentWorkflowTest extends SafeguardingTestSupport {

	@Autowired
	SafeguardingRetention retention;

	// --- S4.5 every read is audited ---------------------------------------------------------------------------------

	@Test
	void everyOpenIsAuditedWithTheReasonAndNoText() throws Exception {
		Msg m = school("inc-audit");
		Persona lead = safeguardingLead(m, "inc-audit-lead");
		UUID id = raise(m.co(), m, "MEDIUM");
		open(lead, id);
		open(lead, id);
		call(HttpMethod.GET, incident(id), lead, null, 200);
		call(HttpMethod.GET, incidents(m), lead, null, 200);
		var opens = this.jdbc.queryForList("select actor_user_id, school_id, target_id, metadata::text as metadata from audit_logs where action = 'safeguarding.incident.open' and target_id = ?", id);
		assertThat(opens).hasSize(2);
		assertThat(opens.getFirst().get("actor_user_id")).isEqualTo(lead.userId());
		assertThat(opens.getFirst().get("school_id")).isEqualTo(m.schoolId());
		assertThat((String) opens.getFirst().get("metadata")).contains("TRIAGE").doesNotContain("CANARY");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'safeguarding.incident.view' and target_id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'safeguarding.incident.list' and school_id = ?", Integer.class, m.schoolId())).isEqualTo(1);
		// creation is audited too, with the category and severity only
		var created = this.jdbc.queryForMap("select metadata::text as metadata from audit_logs where action = 'safeguarding.incident.create' and target_id = ?", id);
		assertThat((String) created.get("metadata")).contains("MEDIUM").doesNotContain("CANARY");
		// a refused read leaves no read row
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", m.admin(), body("reason", "TRIAGE"))).isEqualTo(404);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'safeguarding.incident.open' and target_id = ?", Integer.class, id)).isEqualTo(2);
	}

	@Test
	void theReasonIsAPickListNotFreeText() throws Exception {
		Msg m = school("inc-reason");
		Persona lead = safeguardingLead(m, "inc-reason-lead");
		UUID id = raise(m.co(), m, "LOW");
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", lead, body("reason", "I want to read what the child said"))).isEqualTo(400);
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", lead, body())).isEqualTo(400);
		for (String reason : List.of("TRIAGE", "INVESTIGATION", "FOLLOW_UP", "REFERRAL", "OVERSIGHT", "LEGAL_REQUEST")) {
			assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", lead, body("reason", reason))).isEqualTo(200);
		}
	}

	@Test
	void openingIsRateLimited() throws Exception {
		Msg m = school("inc-rate");
		Persona lead = safeguardingLead(m, "inc-rate-lead");
		UUID id = raise(m.co(), m, "LOW");
		for (int i = 0; i < 60; i++) {
			open(lead, id);
		}
		assertThat(code(open(lead, id, 429))).isEqualTo("safeguarding.rate_limited");
	}

	@Test
	void platformAdministratorsListTheAuditRowsOfSafeguardingReadsWithoutReadingIncidents() throws Exception {
		Msg m = school("inc-oversight");
		Persona lead = safeguardingLead(m, "inc-oversight-lead");
		UUID id = raise(m.co(), m, "HIGH");
		open(lead, id);
		Persona platformAdmin = this.personas.platformAdmin();
		JsonNode rows = call(HttpMethod.GET, "/api/v1/platform/audit-logs?actionPrefix=safeguarding.incident.open&schoolId=" + m.schoolId(), platformAdmin, null, 200);
		assertThat(rows.get("items")).hasSize(1);
		String listing = rows.toString();
		assertThat(listing).contains(id.toString()).doesNotContain("CANARY").doesNotContain("narrative");
		// while the same person gets 404 on the incident itself
		assertThat(statusOf(HttpMethod.GET, incident(id), platformAdmin, null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, incident(id) + "/open", platformAdmin, body("reason", "OVERSIGHT"))).isEqualTo(404);
	}

	// --- S4.6 workflow -----------------------------------------------------------------------------------------------

	@Test
	void theWorkflowGoesOpenInvestigatingActionTakenReferredClosedAndEveryChangeIsAnAppendOnlyRow() throws Exception {
		Msg m = school("inc-flow");
		Persona lead = safeguardingLead(m, "inc-flow-lead");
		UUID id = raise(m.co(), m, "MEDIUM");
		String status = incident(id) + "/status";
		assertThat(code(call(HttpMethod.POST, status, lead, body("status", "ACTION_TAKEN"), 409))).isEqualTo("safeguarding.status_transition_invalid");
		call(HttpMethod.POST, status, lead, body("status", "INVESTIGATING"), 200);
		call(HttpMethod.POST, status, lead, body("status", "ACTION_TAKEN", "note", "The class teacher was spoken to"), 200);
		call(HttpMethod.POST, status, lead, body("status", "REFERRED"), 200);
		// closing needs a closing note, re-opening a reason
		assertThat(code(call(HttpMethod.POST, status, lead, body("status", "CLOSED"), 400))).isEqualTo("safeguarding.closing_note_required");
		assertThat(code(call(HttpMethod.POST, status, lead, body("status", "CLOSED", "note", "too short"), 400))).isEqualTo("safeguarding.closing_note_required");
		JsonNode closed = call(HttpMethod.POST, status, lead, body("status", "CLOSED", "note", "Matter closed after the follow-up meeting"), 200);
		assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
		assertThat(closed.get("closedAt").asString()).isNotBlank();
		assertThat(code(call(HttpMethod.POST, status, lead, body("status", "INVESTIGATING"), 400))).isEqualTo("safeguarding.reopen_reason_required");
		JsonNode reopened = call(HttpMethod.POST, status, lead, body("status", "INVESTIGATING", "note", "New information about the matter"), 200);
		assertThat(reopened.get("status").asString()).isEqualTo("INVESTIGATING");
		assertThat(!reopened.has("closedAt") || reopened.get("closedAt").isNull()).isTrue();
		// the history is rows, readable decrypted by a lead, and append-only in the database
		JsonNode detail = open(lead, id);
		List<String> kinds = detail.get("notes").valueStream().map((n) -> n.get("kind").asString()).toList();
		assertThat(kinds).containsExactly("STATUS", "STATUS", "STATUS", "STATUS", "CLOSE", "REOPEN");
		assertThat(detail.toString()).contains("The class teacher was spoken to").contains("Matter closed after the follow-up meeting");
		assertThatThrownBy(() -> this.jdbc.update("update safeguarding_incident_notes set kind = 'NOTE' where incident_id = ?", id)).hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("delete from safeguarding_incident_notes where incident_id = ?", id)).hasMessageContaining("append-only");
	}

	@Test
	void notesAreEncryptedAndTheAssigneeCanOnlyBeALead() throws Exception {
		Msg m = school("inc-assign");
		Persona lead = safeguardingLead(m, "inc-assign-lead");
		Persona second = safeguardingLead(m, "inc-assign-second");
		UUID id = raise(m.co(), m, "LOW");
		JsonNode note = call(HttpMethod.POST, incident(id) + "/notes", lead, body("note", "NOTE-PLAINTEXT about the follow-up call"), 201);
		assertThat(note.get("note").asString()).contains("NOTE-PLAINTEXT");
		String stored = this.jdbc.queryForObject("select note_enc from safeguarding_incident_notes where id = ?", String.class, UUID.fromString(note.get("id").asString()));
		assertThat(stored).startsWith("v1.").doesNotContain("NOTE-PLAINTEXT");
		call(HttpMethod.POST, incident(id) + "/assign", lead, body("assigneeId", second.userId()), 200);
		assertThat(this.jdbc.queryForObject("select assigned_to from safeguarding_incidents where id = ?", UUID.class, id)).isEqualTo(second.userId());
		for (UUID notALead : new UUID[] { m.lead().userId(), m.admin().userId(), m.student1().userId() }) {
			assertThat(code(call(HttpMethod.POST, incident(id) + "/assign", lead, body("assigneeId", notALead), 400))).isEqualTo("safeguarding.assignee_invalid");
		}
		// a lead of another school cannot be the assignee, a platform lead can
		Msg other = school("inc-assign-other");
		assertThat(code(call(HttpMethod.POST, incident(id) + "/assign", lead, body("assigneeId", safeguardingLead(other, "inc-assign-other-lead").userId()), 400))).isEqualTo("safeguarding.assignee_invalid");
		call(HttpMethod.POST, incident(id) + "/assign", lead, body("assigneeId", this.personas.platformLead("inc-assign-platform").userId()), 200);
	}

	@Test
	void guardiansAreInformedAndReferralsAreRecordedOnlyByAHumanAndAreAudited() throws Exception {
		Msg m = school("inc-manual");
		Persona lead = safeguardingLead(m, "inc-manual-lead");
		UUID id = raise(m.co(), m, "HIGH");
		Instant before = Instant.now().minusSeconds(5);
		assertThat(this.jdbc.queryForObject("select guardian_informed_at is null and referred_at is null from safeguarding_incidents where id = ?", Boolean.class, id)).isTrue();
		call(HttpMethod.POST, incident(id) + "/guardian-informed", lead, body("note", "Told the mother in person"), 200);
		JsonNode referral = call(HttpMethod.POST, incident(id) + "/referral", lead, body("referredTo", "REFERRED-TO-AUTHORITY-NAME"), 200);
		assertThat(referral.get("referredAt").asString()).isNotBlank();
		var row = this.jdbc.queryForMap("select guardian_informed_at, referred_at, referred_to_enc from safeguarding_incidents where id = ?", id);
		assertThat(((Timestamp) row.get("guardian_informed_at")).toInstant()).isAfter(before);
		assertThat((String) row.get("referred_to_enc")).startsWith("v1.").doesNotContain("REFERRED-TO");
		assertThat(open(lead, id).get("referredTo").asString()).isEqualTo("REFERRED-TO-AUTHORITY-NAME");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action in ('safeguarding.incident.guardian_informed', 'safeguarding.incident.referral')", Integer.class, id)).isEqualTo(2);
		// a date in the future is refused
		assertThat(code(call(HttpMethod.POST, incident(id) + "/guardian-informed", lead, body("at", Instant.now().plus(2, ChronoUnit.DAYS).toString()), 400))).isEqualTo("safeguarding.date_invalid");
		// the system told nobody: not the guardians of any student, not the administrator, not the reported adult, not the raiser
		for (UUID nobody : new UUID[] { m.g1().userId(), m.g1b().userId(), m.admin().userId(), m.lead().userId(), m.co().userId(), m.student1().userId() }) {
			assertThat(this.jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, nobody)).as("notifications of " + nobody).isZero();
		}
	}

	// --- S4.7 escalation and notification ----------------------------------------------------------------------------

	@Test
	void highAndCriticalAlertTheSchoolsLeadsGenericallyAndNobodyElse() throws Exception {
		Msg m = school("inc-alert");
		Persona lead = safeguardingLead(m, "inc-alert-lead");
		Persona second = safeguardingLead(m, "inc-alert-second");
		this.personas.platformLead("inc-alert-platform");
		UUID low = raise(m.co(), m, "LOW");
		UUID medium = raise(m.co(), m, "MEDIUM");
		assertThat(notificationsOf(lead.userId(), "SAFEGUARDING_ALERT")).isZero();
		UUID high = raise(m.co(), m, "HIGH");
		UUID critical = raise(m.lead(), m, "CRITICAL");
		for (Persona l : List.of(lead, second)) {
			assertThat(notificationsOf(l.userId(), "SAFEGUARDING_ALERT")).isEqualTo(2);
		}
		var n = this.jdbc.queryForMap("select title, body, data::text as data from notifications where user_id = ? and type_code = 'SAFEGUARDING_ALERT' limit 1", lead.userId());
		String text = n.get("title") + " " + n.get("body") + " " + n.get("data");
		assertThat(text).doesNotContain("CANARY").doesNotContain(m.s1().toString()).doesNotContain(high.toString());
		// not the raiser, not the guardians, not the administrator
		for (UUID nobody : new UUID[] { m.co().userId(), m.lead().userId(), m.g1().userId(), m.admin().userId(), m.student1().userId() }) {
			assertThat(notificationsOf(nobody, "SAFEGUARDING_ALERT")).isZero();
		}
		assertThat(List.of(low, medium, high, critical)).doesNotHaveDuplicates();
	}

	@Test
	void withoutALeadTheAlertGoesToThePlatformLeads() throws Exception {
		Msg m = school("inc-alert-none");
		Persona platformLead = this.personas.platformLead("inc-alert-none-platform");
		raise(m.co(), m, "CRITICAL");
		assertThat(notificationsOf(platformLead.userId(), "SAFEGUARDING_ALERT")).isEqualTo(1);
	}

	@Test
	void escalatingACaseCreatesTheIncidentAndModeratorsNeverLearnItExists() throws Exception {
		Msg m = school("inc-escalate");
		Persona lead = safeguardingLead(m, "inc-escalate-lead");
		UUID post = post(m, m.lead().userId(), "TEACHER", "reported post");
		report(m.g1(), "POST", post, "SAFETY_CONCERN");
		UUID caseId = caseOf("POST", post);
		String escalation = "ESCALATION-REASON-TEXT: the post suggests a child may be at risk";
		JsonNode decided = call(HttpMethod.POST, CASES + caseId + "/decision", m.admin(), body("decision", "ESCALATE", "reason", escalation, "category", "WELLBEING"), 200);
		assertThat(decided.get("summary").get("status").asString()).isEqualTo("ESCALATED");
		assertThat(decided.toString()).doesNotContain("incidentId").doesNotContain(escalation);
		UUID incident = this.jdbc.queryForObject("select id from safeguarding_incidents where source_case_id = ?", UUID.class, caseId);
		// the lead reads the escalation reason as the narrative; the case history does not keep it
		JsonNode detail = open(lead, incident);
		assertThat(detail.get("narrative").asString()).isEqualTo(escalation);
		assertThat(detail.get("summary").get("category").asString()).isEqualTo("WELLBEING");
		assertThat(this.jdbc.queryForObject("select notes from moderation_actions where case_id = ? and action = 'ESCALATE'", String.class, caseId)).isNull();
		assertThat(this.jdbc.queryForObject("select narrative_enc from safeguarding_incidents where id = ?", String.class, incident)).doesNotContain("ESCALATION");
		// the moderator gets 404 on it; the lead sees the link from the case, the moderator does not
		assertThat(statusOf(HttpMethod.GET, incident(incident), m.admin(), null)).isEqualTo(404);
		assertThat(detail(lead, caseId, 200).get("incidentId").asString()).isEqualTo(incident.toString());
		// escalation is idempotent per case, and a case escalated to safeguarding is reopened by leads only
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/reopen", m.admin(), body("reason", "Trying to reopen the escalated case"), 403))).isEqualTo("moderation.case_escalated");
		call(HttpMethod.POST, CASES + caseId + "/reopen", lead, body("reason", "Reopening the case as the safeguarding lead"), 200);
	}

	// --- S6 legal hold and retention --------------------------------------------------------------------------------

	@Test
	void legalHoldBlocksTheIncidentPurgeAndTheExpiredUnheldOneIsRemovedOnce() throws Exception {
		Msg m = school("inc-purge");
		Persona lead = safeguardingLead(m, "inc-purge-lead");
		UUID expired = raise(m.co(), m, "LOW");
		UUID held = raise(m.co(), m, "LOW");
		UUID recent = raise(m.co(), m, "LOW");
		UUID open = raise(m.co(), m, "LOW");
		for (UUID id : List.of(expired, held, recent)) {
			call(HttpMethod.POST, incident(id) + "/status", lead, body("status", "CLOSED", "note", "Closed for the retention test"), 200);
		}
		call(HttpMethod.PUT, incident(held) + "/legal-hold", lead, body("held", true), 200);
		this.jdbc.update("update safeguarding_incidents set closed_at = now() - interval '8 years' where id in (?, ?)", expired, held);
		this.jdbc.update("update safeguarding_incidents set closed_at = now() - interval '3 years' where id = ?", recent);
		int purged = this.retention.purgeExpired();
		assertThat(purged).isEqualTo(1);
		assertThat(this.jdbc.queryForList("select id from safeguarding_incidents where id in (?, ?, ?, ?)", UUID.class, expired, held, recent, open))
			.containsExactlyInAnyOrder(held, recent, open);
		assertThat(this.jdbc.queryForObject("select count(*) from safeguarding_incident_notes where incident_id = ?", Integer.class, expired)).isZero();
		assertThat(this.retention.purgeExpired()).isZero();
		call(HttpMethod.PUT, incident(held) + "/legal-hold", lead, body("held", false), 200);
		assertThat(this.retention.purgeExpired()).isEqualTo(1);
		// the platform's own hold endpoint changes the flag and answers the id only
		Persona pm = this.personas.platformModerator("inc-purge-pm");
		JsonNode hold = call(HttpMethod.PUT, "/api/v1/platform/safeguarding/incidents/" + recent + "/legal-hold", pm, body("held", true), 200);
		assertThat(hold.propertyNames()).containsExactlyInAnyOrder("id", "legalHold");
		assertThat(this.jdbc.queryForObject("select legal_hold from safeguarding_incidents where id = ?", Boolean.class, recent)).isTrue();
		assertThat(statusOf(HttpMethod.PUT, "/api/v1/platform/safeguarding/incidents/" + recent + "/legal-hold", lead, body("held", false))).isEqualTo(403);
	}

}
