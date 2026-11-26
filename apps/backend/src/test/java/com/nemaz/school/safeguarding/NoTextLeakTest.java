package com.nemaz.school.safeguarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.moderation.application.ModerationModels;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Rule book principle 3, S1.2, S4.4 and S4.5: the text of a report, of a decision reason and of an incident (narrative, notes,
 * referral) exists in its own table and in nothing else: not in the audit log, the stored events, the notifications, the pushes,
 * the application log, nor in the list endpoints of cases and incidents.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NoTextLeakTest extends SafeguardingTestSupport {

	@Test
	void textOfReportsDecisionsAndIncidentsIsNeverInAuditEventsNotificationsPushesLogsOrLists(CapturedOutput output) throws Exception {
		Msg m = school("canary");
		Persona lead = safeguardingLead(m, "canary-lead");
		String leadDevice = device(lead);
		// reports with details, two reporters on one target, a student reporting an adult
		UUID post = post(m, m.lead().userId(), "TEACHER", "CANARY-POST-BODY");
		report(m.student1(), "POST", post, "SAFETY_CONCERN", "CANARY-DETAILS-STUDENT", 201);
		report(m.g1(), "POST", post, "HARASSMENT", "CANARY-DETAILS-GUARDIAN", 201);
		UUID caseId = caseOf("POST", post);
		call(HttpMethod.POST, CASES + caseId + "/assign", m.admin(), body(), 200);
		// a decision with a reason, a hide with a reason, a warning, an escalation whose reason becomes a narrative
		UUID hidden = post(m, m.lead().userId(), "TEACHER", "CANARY-HIDDEN-POST");
		report(m.g1b(), "POST", hidden, "INAPPROPRIATE", "CANARY-DETAILS-HIDE", 201);
		decide(m.admin(), caseOf("POST", hidden), "HIDE", "CANARY-REASON-HIDE explained at length", 200);
		UUID warned = post(m, m.subject().userId(), "TEACHER", "CANARY-WARNED-POST");
		report(m.g2(), "POST", warned, "SPAM", "CANARY-DETAILS-WARN", 201);
		decide(m.admin(), caseOf("POST", warned), "WARN_USER", "CANARY-REASON-WARN explained at length", 200);
		decide(m.admin(), caseId, "ESCALATE", "CANARY-REASON-ESCALATE the child may be at risk", 200);
		UUID incident = this.jdbc.queryForObject("select id from safeguarding_incidents where source_case_id = ?", UUID.class, caseId);
		// incidents: a concern by a teacher, notes, a referral, the guardian-informed record, closing
		UUID concern = raise(m.co(), m, "CONDUCT", "CRITICAL", "CANARY-NARRATIVE a concern raised by a teacher");
		open(lead, concern);
		call(HttpMethod.POST, incident(concern) + "/notes", lead, body("note", "CANARY-NOTE one"), 201);
		call(HttpMethod.POST, incident(concern) + "/referral", lead, body("referredTo", "CANARY-REFERRAL authority"), 200);
		call(HttpMethod.POST, incident(concern) + "/guardian-informed", lead, body("note", "CANARY-INFORMED note"), 200);
		call(HttpMethod.POST, incident(concern) + "/status", lead, body("status", "CLOSED", "note", "CANARY-CLOSING note for the record"), 200);
		open(lead, incident);
		// the read paths a moderator and a lead use: lists must show no text
		JsonNode queue = call(HttpMethod.GET, schoolQueue(m) + "?status=ALL", m.admin(), null, 200);
		assertThat(queue.toString()).doesNotContain("CANARY");
		JsonNode incidentList = call(HttpMethod.GET, incidents(m), lead, null, 200);
		assertThat(incidentList.get("items")).hasSizeGreaterThanOrEqualTo(2);
		assertThat(incidentList.toString()).doesNotContain("CANARY");
		assertThat(call(HttpMethod.GET, incident(concern), lead, null, 200).toString()).doesNotContain("CANARY");
		assertThat(call(HttpMethod.GET, "/api/v1/platform/safeguarding/incidents", this.personas.platformLead("canary-platform"), null, 200).toString())
			.doesNotContain("CANARY");
		// the case page shows the report details only to a lead, never anywhere else
		assertThat(detail(m.admin(), caseId, 200).toString()).doesNotContain("CANARY-DETAILS");
		// wait for asynchronous deliveries (notifications and event listeners)
		await().atMost(Duration.ofSeconds(30)).until(() -> this.jdbc.queryForObject(
				"select count(*) from event_publication where completion_date is null", Integer.class) == 0);
		// the audit log
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_logs where metadata::text like '%CANARY%' or changes::text like '%CANARY%'
				  or target_type::text like '%CANARY%'""", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action like 'moderation.%' or action like 'safeguarding.%'", Integer.class))
			.isGreaterThan(12);
		// the stored events (they carry ids and codes)
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like '%CANARY%'", Integer.class)).isZero();
		// notifications and pushes
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where title like '%CANARY%' or body like '%CANARY%' or data::text like '%CANARY%'", Integer.class))
			.isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where type_code in ('REPORT_ACKNOWLEDGED', 'REPORT_REVIEWED', 'SAFEGUARDING_ALERT', 'ACCOUNT_WARNING')", Integer.class))
			.isGreaterThan(4);
		await().atMost(Duration.ofSeconds(30)).until(() -> !this.pushes.sentTo(leadDevice).isEmpty());
		for (var push : this.pushes.sent()) {
			assertThat(push.toString()).doesNotContain("CANARY");
		}
		// the log
		assertThat(output.getAll()).doesNotContain("CANARY");
		// the text is where it belongs, and encrypted where it must be
		assertThat(this.jdbc.queryForObject("select count(*) from content_reports where details like 'CANARY%'", Integer.class)).isEqualTo(4);
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_actions where notes like 'CANARY%'", Integer.class)).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select count(*) from safeguarding_incidents where narrative_enc like '%CANARY%'", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from safeguarding_incident_notes where note_enc like '%CANARY%'", Integer.class)).isZero();
	}

	@Test
	void requestObjectsAreRedactedInLogs() {
		assertThat(new ModerationModels.ReportRequest("POST", UUID.randomUUID(), "OTHER", "CANARY-DETAILS").toString()).doesNotContain("CANARY");
		assertThat(new ModerationModels.DecisionRequest("HIDE", "CANARY-REASON", null, null, null).toString()).doesNotContain("CANARY");
		assertThat(new ModerationModels.ReasonRequest("CANARY-REASON").toString()).doesNotContain("CANARY");
		assertThat(new ModerationModels.LockRequest("CANARY-REASON").toString()).doesNotContain("CANARY");
		assertThat(new com.nemaz.school.safeguarding.application.SafeguardingModels.ConcernRequest("OTHER", "LOW", "CANARY-NARRATIVE", null, null).toString())
			.doesNotContain("CANARY");
		assertThat(new com.nemaz.school.safeguarding.application.SafeguardingModels.NoteRequest("CANARY-NOTE", null).toString()).doesNotContain("CANARY");
		assertThat(new com.nemaz.school.safeguarding.application.SafeguardingModels.StatusRequest("CLOSED", "CANARY-NOTE").toString()).doesNotContain("CANARY");
		assertThat(new com.nemaz.school.safeguarding.application.SafeguardingModels.ReferralRequest("CANARY-REFERRAL", null, "CANARY-NOTE").toString())
			.doesNotContain("CANARY");
		assertThat(new com.nemaz.school.safeguarding.application.SafeguardingModels.GuardianInformedRequest(null, "CANARY-NOTE").toString())
			.doesNotContain("CANARY");
	}

}
