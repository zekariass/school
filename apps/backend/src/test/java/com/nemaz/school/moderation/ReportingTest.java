package com.nemaz.school.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.feed.ContentReported;
import com.nemaz.school.messaging.MessageReported;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book S1 (reporting) and S2.1 to S2.3 (cases, priority, routing): one test per rule. */
@IntegrationTest
class ReportingTest extends ModerationTestSupport {

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	ReportIntake intake;

	// --- S1.1 visibility ----------------------------------------------------------------------------------------------

	@Test
	void aReportOfAVisiblePostCreatesOneCaseAndOneAcknowledgement() throws Exception {
		Msg m = school("rep-ok");
		UUID post = post(m, m.lead().userId(), "TEACHER", "A post");
		JsonNode receipt = report(m.g1(), "POST", post, "INAPPROPRIATE");
		assertThat(receipt.get("id").asString()).isNotBlank();
		UUID caseId = caseOf("POST", post);
		assertThat(row(caseId)).containsEntry("status", "OPEN").containsEntry("queue", "SCHOOL").containsEntry("priority", "NORMAL");
		assertThat(row(caseId).get("report_count")).isEqualTo(1);
		// one acknowledgement to the reporter, with nothing in it
		assertThat(notificationsOf(m.g1().userId(), "REPORT_ACKNOWLEDGED")).isEqualTo(1);
		String data = this.jdbc.queryForObject("select coalesce(data::text, '') from notifications where user_id = ? and type_code = 'REPORT_ACKNOWLEDGED'",
				String.class, m.g1().userId());
		assertThat(data).doesNotContain(caseId.toString());
	}

	@Test
	void aReportOfSomethingTheReporterCannotSeeIsTheGeneric404() throws Exception {
		Msg m = school("rep-404");
		UUID post = post(m, m.lead().userId(), "TEACHER", "A post for 1A only");
		// somebody of the school who is not in the class, a stranger, another school's administrator
		for (Persona nobody : new Persona[] { m.stranger(), m.otherAdmin(), m.otherStudent(), m.otherGuardian() }) {
			JsonNode problem = report(nobody, "POST", post, "OTHER", null, 404);
			assertThat(code(problem)).isEqualTo("moderation.target_not_found");
		}
		// a custody-restricted, a revoked and a pending guardian of a child of the class see nothing, so they cannot report
		for (Persona guardian : new Persona[] { m.restricted(), m.revoked(), m.pending() }) {
			assertThat(code(report(guardian, "POST", post, "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		}
		// an id that does not exist answers exactly the same
		assertThat(code(report(m.g1(), "POST", UUID.randomUUID(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertNoCase("POST", post);
	}

	@Test
	void aMessageOfAConversationTheReporterIsNotInIsTheGeneric404() throws Exception {
		Msg m = school("rep-msg-404");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "hello teacher");
		assertThat(code(report(m.g2(), "MESSAGE", message, "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.stranger(), "MESSAGE", message, "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		// the other party of the conversation can report it, and the case is for the school
		report(m.lead(), "MESSAGE", message, "INAPPROPRIATE");
		assertThat(row(caseOf("MESSAGE", message))).containsEntry("school_id", m.schoolId());
	}

	@Test
	void theTargetTypeAndTheReasonMustBeKnownAndDetailsAreBounded() throws Exception {
		Msg m = school("rep-validate");
		UUID post = post(m, m.lead().userId(), "TEACHER", "x");
		assertThat(code(report(m.g1(), "NOPE", post, "OTHER", null, 400))).isEqualTo("moderation.target_type_invalid");
		assertThat(code(report(m.g1(), "POST", post, "NOPE", null, 400))).isEqualTo("moderation.reason_invalid");
		assertThat(code(report(m.g1(), "TUTOR_PROFILE", post, "OTHER", null, 400))).isEqualTo("moderation.target_type_unsupported");
		assertThat(code(report(m.g1(), "POST", post, "OTHER", "x".repeat(1001), 400))).isEqualTo("moderation.details_too_long");
		// bounded and cleaned: control and invisible characters go, 1,000 characters are fine
		report(m.g1(), "POST", post, "OTHER", "  hello\u0000‮world \r\n" + "y".repeat(900), 201);
		String stored = this.jdbc.queryForObject("select details from content_reports where reporter_id = ?", String.class, m.g1().userId());
		assertThat(stored).startsWith("helloworld").doesNotContain("\u0000").doesNotContain("‮");
	}

	// --- S1.3 duplicates and limits ---------------------------------------------------------------------------------

	@Test
	void aSecondReportOfTheSameTargetAnswers200WithTheFirstReportAndChangesNothing() throws Exception {
		Msg m = school("rep-dup");
		UUID post = post(m, m.lead().userId(), "TEACHER", "dup");
		JsonNode first = report(m.g1(), "POST", post, "SPAM");
		JsonNode second = report(m.g1(), "POST", post, "OTHER", null, 200);
		assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
		assertThat(row(caseOf("POST", post)).get("report_count")).isEqualTo(1);
		assertThat(notificationsOf(m.g1().userId(), "REPORT_ACKNOWLEDGED")).isEqualTo(1);
	}

	@Test
	void reportsAreRateLimitedTwentyADayAndStudentsTenADay() throws Exception {
		Msg m = school("rep-limit");
		for (int i = 0; i < 10; i++) {
			report(m.student1(), "POST", post(m, m.lead().userId(), "TEACHER", "p" + i), "SPAM");
		}
		UUID eleventh = post(m, m.lead().userId(), "TEACHER", "p11");
		assertThat(code(report(m.student1(), "POST", eleventh, "SPAM", null, 429))).isEqualTo("moderation.report_rate_limited");
		for (int i = 0; i < 20; i++) {
			report(m.g1(), "POST", post(m, m.lead().userId(), "TEACHER", "g" + i), "SPAM");
		}
		assertThat(code(report(m.g1(), "POST", post(m, m.lead().userId(), "TEACHER", "g21"), "SPAM", null, 429)))
			.isEqualTo("moderation.report_rate_limited");
		// a repeat of an open report is not counted against the limit
		UUID again = post(m, m.lead().userId(), "TEACHER", "again");
		report(m.g1b(), "POST", again, "SPAM");
		report(m.g1b(), "POST", again, "SPAM", null, 200);
	}

	@Test
	void aReporterCannotReportTheirOwnContent() throws Exception {
		Msg m = school("rep-own");
		UUID post = post(m, m.lead().userId(), "TEACHER", "mine");
		assertThat(code(report(m.lead(), "POST", post, "OTHER", null, 409))).isEqualTo("moderation.cannot_report_own");
	}

	// --- S5.1 reporting a person ----------------------------------------------------------------------------------------

	@Test
	void aPersonCanBeReportedOnlyInASharedContextAndNeverBySomebodyWhoJustKnowsTheirId() throws Exception {
		Msg m = school("rep-user");
		// a student reports a classmate (NORMAL) and a teacher of the class (HIGH: a student about an adult)
		report(m.student1(), "USER", m.s2(), "HARASSMENT", null, 201);
		assertThat(row(caseOf("USER", m.s2()))).containsEntry("school_id", m.schoolId());
		report(m.student1(), "USER", m.lead().userId(), "OTHER", null, 201);
		assertThat(row(caseOf("USER", m.lead().userId()))).containsEntry("priority", "HIGH").containsEntry("subject_user_id", m.lead().userId());
		// a teacher reports a student of their class; a guardian reports staff of the child's school
		report(m.lead(), "USER", m.s3(), "OTHER", null, 201);
		report(m.g1(), "USER", m.subject().userId(), "OTHER", null, 201);
		// nobody reports a stranger, a student of another class, another family's guardian, a restricted guardian's teacher or themselves
		assertThat(code(report(m.student1(), "USER", m.o1(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.student1(), "USER", m.stranger().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.student1(), "USER", m.g1().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.g1(), "USER", m.g2().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.g1(), "USER", m.s2(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.restricted(), "USER", m.lead().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.stranger(), "USER", m.lead().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		assertThat(code(report(m.student1(), "USER", m.student1().userId(), "OTHER", null, 404))).isEqualTo("moderation.target_not_found");
		// a shared conversation is a context too (messaging): the guardian and the teacher of a parent-teacher thread
		parentTeacherThread(m);
		report(m.g1(), "USER", m.lead().userId(), "SPAM", null, 201); // joins the open case about the teacher
		assertThat(row(caseOf("USER", m.lead().userId())).get("reporter_count")).isEqualTo(2);
		// never visible to the person reported: they have no access to the case
		assertThat(statusOf(HttpMethod.GET, CASES + caseOf("USER", m.lead().userId()), m.lead(), null)).isEqualTo(404);
	}

	// --- S1.4 and S1.5 students and the notices ----------------------------------------------------------------------

	@Test
	void studentsCanAlwaysReportAndTheReportIsNeverShownToClassmatesOrTheReportedPerson() throws Exception {
		Msg m = school("rep-student");
		UUID comment = comment(m, post(m, m.lead().userId(), "TEACHER", "post"), m.student2().userId(), "STUDENT", "mean words");
		report(m.student1(), "POST_COMMENT", comment, "HARASSMENT");
		UUID caseId = caseOf("POST_COMMENT", comment);
		// the classmate who wrote it, the other classmate and the guardians have no access to the case
		for (Persona nobody : new Persona[] { m.student2(), m.student3(), m.g1(), m.g2(), m.stranger() }) {
			assertThat(statusOf(HttpMethod.GET, CASES + caseId, nobody, null)).isIn(403, 404);
		}
		assertThat(statusOf(HttpMethod.GET, schoolQueue(m), m.student1(), null)).isEqualTo(403);
	}

	@Test
	void theReporterGetsOneGenericReviewedNoticeAtResolutionAndNothingElse() throws Exception {
		Msg m = school("rep-reviewed");
		UUID post = post(m, m.lead().userId(), "TEACHER", "reviewed");
		report(m.g1(), "POST", post, "SPAM");
		report(m.g1b(), "POST", post, "SPAM");
		UUID caseId = caseOf("POST", post);
		decide(m.admin(), caseId, "NO_ACTION", REASON, 200);
		assertThat(notificationsOf(m.g1().userId(), "REPORT_REVIEWED")).isEqualTo(1);
		assertThat(notificationsOf(m.g1b().userId(), "REPORT_REVIEWED")).isEqualTo(1);
		// no outcome, no names, no content, no case in the text or the data of any notice the reporter got
		for (Object[] n : this.jdbc.query("select title, body, coalesce(data::text, '') from notifications where user_id = ? order by created_at",
				(rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getString(3) }, m.g1().userId())) {
			String all = String.join(" ", (String) n[0], String.valueOf(n[1]), (String) n[2]).toLowerCase();
			assertThat(all).doesNotContain("no_action").doesNotContain("hide").doesNotContain(caseId.toString()).doesNotContain(post.toString());
		}
	}

	// --- S1.6 the existing hooks ------------------------------------------------------------------------------------

	@Test
	void theFeedHookCreatesTheReportAndTheCase() throws Exception {
		Msg m = school("rep-feedhook");
		UUID post = post(m, m.lead().userId(), "TEACHER", "hooked");
		call(HttpMethod.POST, "/api/v1/schools/" + m.schoolId() + "/posts/" + post + "/report", m.g1(), body("reason", "SPAM"), 202);
		await().atMost(Duration.ofSeconds(20)).until(() -> this.jdbc.queryForObject(
				"select count(*) from content_reports where target_id = ?", Integer.class, post) == 1);
		assertThat(row(caseOf("POST", post))).containsEntry("school_id", m.schoolId());
		assertThat(notificationsOf(m.g1().userId(), "REPORT_ACKNOWLEDGED")).isEqualTo(1);
	}

	@Test
	void theMessagingHookCreatesTheReportAndTheCase() throws Exception {
		Msg m = school("rep-msghook");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "hello");
		call(HttpMethod.POST, conv(thread) + "/messages/" + message + "/report", m.lead(), body("reason", "BULLYING"), 204);
		await().atMost(Duration.ofSeconds(20)).until(() -> this.jdbc.queryForObject(
				"select count(*) from content_reports where target_id = ?", Integer.class, message) == 1);
		UUID caseId = caseOf("MESSAGE", message);
		assertThat(row(caseId)).containsEntry("priority", "HIGH"); // BULLYING is HARASSMENT, which starts HIGH
		assertThat(this.jdbc.queryForObject("select reason_code from content_reports where case_id = ?", String.class, caseId)).isEqualTo("HARASSMENT");
	}

	@Test
	void redeliveryOfTheFeedAndMessagingEventsChangesNothing() throws Exception {
		Msg m = school("rep-redeliver");
		UUID post = post(m, m.lead().userId(), "TEACHER", "twice");
		UUID reportId = Ids.newId();
		ContentReported event = new ContentReported(reportId, "POST", post, m.schoolId(), m.g1().userId(), "SPAM");
		// the listener's own entry point, three times: the case, the report and the acknowledgement exist exactly once
		for (int i = 0; i < 3; i++) {
			this.intake.ingest("content_report:" + reportId, event.contentType(), event.contentId(), event.reporterId(), event.reasonCode(), null);
		}
		assertThat(this.jdbc.queryForObject("select count(*) from content_reports where target_id = ?", Integer.class, post)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where target_id = ?", Integer.class, post)).isEqualTo(1);
		assertThat(row(caseOf("POST", post)).get("report_count")).isEqualTo(1);
		assertThat(notificationsOf(m.g1().userId(), "REPORT_ACKNOWLEDGED")).isEqualTo(1);
		// the same through the events themselves (published twice, delivered at least once)
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "hello");
		UUID messageReport = Ids.newId();
		MessageReported hook = new MessageReported(messageReport, message, thread, m.schoolId(), m.lead().userId(), "SPAM");
		for (int i = 0; i < 2; i++) {
			this.tx.executeWithoutResult((s) -> this.events.publishEvent(hook));
		}
		await().atMost(Duration.ofSeconds(20)).until(() -> this.jdbc.queryForObject(
				"select count(*) from content_reports where target_id = ?", Integer.class, message) >= 1);
		Thread.sleep(1500);
		assertThat(this.jdbc.queryForObject("select count(*) from content_reports where target_id = ?", Integer.class, message)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where target_id = ?", Integer.class, message)).isEqualTo(1);
		assertThat(notificationsOf(m.lead().userId(), "REPORT_ACKNOWLEDGED")).isEqualTo(1);
	}

	// --- S2.1 one open case per target, S2.2 priority ---------------------------------------------------------------

	@Test
	void reportsAboutOneTargetJoinOneOpenCaseAndTheDatabaseForbidsASecond() throws Exception {
		Msg m = school("rep-onecase");
		UUID post = post(m, m.lead().userId(), "TEACHER", "one case");
		report(m.g1(), "POST", post, "SPAM");
		report(m.g1b(), "POST", post, "OTHER");
		report(m.g2(), "POST", post, "PRIVACY"); // a guardian of another child of the class
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where target_type = 'POST' and target_id = ?", Integer.class, post))
			.isEqualTo(1);
		assertThat(row(caseOf("POST", post)).get("report_count")).isEqualTo(3);
	}

	@Test
	void theCaseCountsReportersAndPastTheThresholdItsPriorityRises() throws Exception {
		Msg m = school("rep-threshold");
		UUID post = post(m, m.lead().userId(), "TEACHER", "popular");
		report(m.g1(), "POST", post, "SPAM");
		report(m.g1b(), "POST", post, "SPAM");
		UUID caseId = caseOf("POST", post);
		assertThat(row(caseId)).containsEntry("priority", "NORMAL");
		assertThat(row(caseId).get("reporter_count")).isEqualTo(2);
		report(m.g2(), "POST", post, "SPAM");
		assertThat(row(caseId)).containsEntry("priority", "HIGH");
		assertThat(row(caseId).get("reporter_count")).isEqualTo(3);
		assertThat(row(caseId).get("report_count")).isEqualTo(3);
		// the unique index: a second OPEN case for the target cannot exist
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.jdbc.update("""
				insert into moderation_cases (id, school_id, queue, target_type, target_id, subject_user_id)
				values (?, ?, 'SCHOOL', 'POST', ?, ?)""", Ids.newId(), m.schoolId(), post, m.lead().userId()))
			.hasMessageContaining("ux_open_case_per_target");
	}

	@Test
	void theFourStartingPrioritiesAreHighAndTheRestIsNormal() throws Exception {
		Msg m = school("rep-priority");
		// SAFETY_CONCERN
		UUID a = post(m, m.lead().userId(), "TEACHER", "a");
		report(m.g1(), "POST", a, "SAFETY_CONCERN");
		assertThat(row(caseOf("POST", a))).containsEntry("priority", "HIGH");
		// HARASSMENT
		UUID b = post(m, m.lead().userId(), "TEACHER", "b");
		report(m.g1(), "POST", b, "HARASSMENT");
		assertThat(row(caseOf("POST", b))).containsEntry("priority", "HIGH");
		// a student reporting an adult, whatever the reason
		UUID c = post(m, m.lead().userId(), "TEACHER", "c");
		report(m.student1(), "POST", c, "SPAM");
		assertThat(row(caseOf("POST", c))).containsEntry("priority", "HIGH");
		// a student reporting a classmate is NORMAL
		UUID d = comment(m, post(m, m.lead().userId(), "TEACHER", "d"), m.student2().userId(), "STUDENT", "x");
		report(m.student1(), "POST_COMMENT", d, "SPAM");
		assertThat(row(caseOf("POST_COMMENT", d))).containsEntry("priority", "NORMAL");
		// a message of a safeguarded (adult-student) conversation
		UUID thread = teacherStudentThread(m);
		UUID message = send(m.lead(), thread, "hi Abel");
		report(m.g1(), "MESSAGE", message, "SPAM", null, 201);
		assertThat(row(caseOf("MESSAGE", message))).containsEntry("priority", "HIGH");
	}

	@Test
	void aHighCaseAlertsTheSchoolsLeadsAtOnceWithAGenericTextAndNobodyElse() throws Exception {
		Msg m = school("rep-alert");
		Persona lead = safeguardingLead(m, "alert-lead");
		UUID post = post(m, m.lead().userId(), "TEACHER", "alert me");
		report(m.student1(), "POST", post, "SAFETY_CONCERN");
		assertThat(notificationsOf(lead.userId(), "SAFEGUARDING_ALERT")).isEqualTo(1);
		// generic: no content, no names, no outcome, no case id in the text
		var n = this.jdbc.queryForMap("select title, body, data::text as data from notifications where user_id = ? and type_code = 'SAFEGUARDING_ALERT'",
				lead.userId());
		assertThat(n.get("title").toString() + n.get("body")).doesNotContain("alert me").doesNotContainIgnoringCase("student");
		// not the reporter, not the reported teacher, not the administrator
		for (UUID nobody : new UUID[] { m.student1().userId(), m.lead().userId(), m.admin().userId() }) {
			assertThat(notificationsOf(nobody, "SAFEGUARDING_ALERT")).isZero();
		}
		// a second report of the same case does not alert again
		report(m.g1(), "POST", post, "SAFETY_CONCERN");
		assertThat(notificationsOf(lead.userId(), "SAFEGUARDING_ALERT")).isEqualTo(1);
	}

	@Test
	void withoutALeadTheAlertGoesToThePlatformLeads() throws Exception {
		Msg m = school("rep-alert-platform");
		Persona platformLead = this.personas.platformLead("alert-platform-lead");
		UUID post = post(m, m.lead().userId(), "TEACHER", "platform alert");
		report(m.g1(), "POST", post, "SAFETY_CONCERN");
		assertThat(notificationsOf(platformLead.userId(), "SAFEGUARDING_ALERT")).isEqualTo(1);
	}

	// --- feature flag -------------------------------------------------------------------------------------------------

	@Test
	void whenTheSchoolSwitchedModerationOffReportingAnswers403() throws Exception {
		Msg m = school("rep-flag");
		UUID post = post(m, m.lead().userId(), "TEACHER", "flag");
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('moderation', 'SCHOOL', ?, false)", m.schoolId());
		try {
			clearConfigurationCache();
			assertThat(code(report(m.g1(), "POST", post, "SPAM", null, 403))).isEqualTo("moderation.feature_disabled");
			assertThat(statusOf(HttpMethod.GET, schoolQueue(m), m.admin(), null)).isEqualTo(403);
		}
		finally {
			this.jdbc.update("delete from feature_overrides where school_id = ?", m.schoolId());
			clearConfigurationCache();
		}
	}

	@Autowired
	org.springframework.cache.CacheManager caches;

	private void clearConfigurationCache() {
		this.caches.getCache("school-configuration").clear();
	}

}
