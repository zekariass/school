package com.nemaz.school.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.moderation.application.ModerationRetention;
import com.nemaz.school.support.FakeModerationTarget;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book S2.3 to S2.7 and S3: queues, routing, decisions and their effects, conflicts, reversals, reads. */
@IntegrationTest
class CaseWorkflowTest extends ModerationTestSupport {

	@Autowired
	ModerationRetention retention;

	private UUID reportedPost(Msg m, String reason, Persona reporter) throws Exception {
		UUID post = post(m, m.lead().userId(), "TEACHER", "ORIGINAL-POST-TEXT");
		report(reporter, "POST", post, reason);
		return post;
	}

	// --- S2.3 queues and routing ------------------------------------------------------------------------------------

	@Test
	void aSchoolCaseIsInTheSchoolQueueAndOnlyThere() throws Exception {
		Msg m = school("wf-queue");
		UUID post = reportedPost(m, "SPAM", m.g1());
		UUID caseId = caseOf("POST", post);
		JsonNode queue = call(HttpMethod.GET, schoolQueue(m), m.admin(), null, 200);
		assertThat(queueIds(queue)).contains(caseId.toString());
		// the platform queue is another list, and a school administrator is not allowed into it
		assertThat(statusOf(HttpMethod.GET, "/api/v1/platform/moderation/cases", m.admin(), null)).isEqualTo(403);
		Persona platformModerator = this.personas.platformModerator("wf-queue-pm");
		assertThat(queueIds(call(HttpMethod.GET, "/api/v1/platform/moderation/cases", platformModerator, null, 200))).doesNotContain(caseId.toString());
		// a platform moderator works the platform queue only: the school's case is not theirs
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, platformModerator, null)).isEqualTo(404);
	}

	@Test
	void aCaseAboutASchoolAdministratorSkipsTheSchoolQueueAndGoesToThePlatformQueue() throws Exception {
		Msg m = school("wf-admin-subject");
		UUID post = post(m, m.admin().userId(), "SCHOOL_ADMIN", "from the administrator");
		report(m.student1(), "POST", post, "SPAM");
		UUID caseId = caseOf("POST", post);
		assertThat(row(caseId)).containsEntry("queue", "PLATFORM");
		// the school's administrators and moderators never see it, not even the administrator who is the subject
		assertThat(queueIds(call(HttpMethod.GET, schoolQueue(m), m.admin(), null, 200))).doesNotContain(caseId.toString());
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, m.admin(), null)).isEqualTo(404);
		Persona otherAdmin = this.personas.schoolAdmin(m.schoolId(), "wf-admin-colleague");
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, otherAdmin, null)).isEqualTo(404);
		// the platform moderator does
		Persona pm = this.personas.platformModerator("wf-admin-pm");
		assertThat(queueIds(call(HttpMethod.GET, "/api/v1/platform/moderation/cases", pm, null, 200))).contains(caseId.toString());
		JsonNode detail = detail(pm, caseId, 200);
		assertThat(detail.get("summary").get("queue").asString()).isEqualTo("PLATFORM");
	}

	@Test
	void aCaseAboutAModeratorOrASafeguardingLeadAlsoGoesToThePlatformQueue() throws Exception {
		Msg m = school("wf-lead-subject");
		Persona lead = safeguardingLead(m, "wf-lead-subject-lead");
		UUID post = post(m, lead.userId(), "TEACHER", "from the lead");
		report(m.g1(), "POST", post, "SPAM");
		assertThat(row(caseOf("POST", post))).containsEntry("queue", "PLATFORM");
		Persona moderator = this.personas.withRole("wf-moderator", com.nemaz.school.authorization.RoleCodes.COUNTRY_ADMIN, AccessScope.country("ET"));
		UUID post2 = post(m, moderator.userId(), "SCHOOL_ADMIN", "from the country admin");
		report(m.g1(), "POST", post2, "SPAM");
		assertThat(row(caseOf("POST", post2))).containsEntry("queue", "PLATFORM");
	}

	@Autowired
	FakeModerationTarget fake;

	@Test
	void aTargetOfALaterMilestoneNeedsOnlyTheSpisAndAPlatformScopeTargetGoesToThePlatformQueue() throws Exception {
		Msg m = school("wf-platform-scope");
		// a type nobody owns yet is refused; a type registered through the SPIs works with no change to moderation
		assertThat(code(report(m.g1(), "COURSE", UUID.randomUUID(), "OTHER", null, 400))).isEqualTo("moderation.target_type_unsupported");
		UUID resource = this.fake.resource(m.lead().userId(), m.g1().userId());
		assertThat(code(report(m.g2(), FakeModerationTarget.TYPE, resource, "COPYRIGHT", null, 404))).isEqualTo("moderation.target_not_found");
		report(m.g1(), FakeModerationTarget.TYPE, resource, "COPYRIGHT");
		UUID caseId = caseOf(FakeModerationTarget.TYPE, resource);
		assertThat(row(caseId)).containsEntry("queue", "PLATFORM").containsEntry("school_id", null);
		assertThat(this.fake.calls()).contains("hold " + resource);
		// the school's moderators have no say; a platform moderator decides, and the owner carries the decision out
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, m.admin(), null)).isEqualTo(404);
		Persona pm = this.personas.platformModerator("wf-platform-scope-pm");
		decide(pm, caseId, "HIDE", REASON, 200);
		call(HttpMethod.POST, CASES + caseId + "/restore", pm, body("reason", "Restoring the resource as agreed"), 200);
		call(HttpMethod.GET, CASES + caseId + "/content", pm, null, 200);
		assertThat(this.fake.calls()).contains("hide " + resource, "restore " + resource, "read " + resource);
		// a school-level suspension needs a school
		UUID second = this.fake.resource(m.lead().userId(), m.g1b().userId());
		report(m.g1b(), FakeModerationTarget.TYPE, second, "COPYRIGHT");
		assertThat(code(decide(pm, caseOf(FakeModerationTarget.TYPE, second), "SUSPEND_USER", REASON, 400))).isEqualTo("moderation.decision_not_applicable");
	}

	@Test
	void everyQueueIsFilteredOnTheServerByRole() throws Exception {
		Msg m = school("wf-roles");
		UUID post = reportedPost(m, "SPAM", m.g1());
		UUID caseId = caseOf("POST", post);
		for (Persona nobody : new Persona[] { m.student1(), m.lead(), m.co(), m.staff() }) {
			assertThat(statusOf(HttpMethod.GET, schoolQueue(m), nobody, null)).as("queue of " + nobody.name()).isEqualTo(403);
			assertThat(statusOf(HttpMethod.GET, CASES + caseId, nobody, null)).as("case for " + nobody.name()).isEqualTo(404);
		}
		// guardians are not members of the school (R-02): the school's queue does not exist for them
		assertThat(statusOf(HttpMethod.GET, schoolQueue(m), m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, schoolQueue(m), m.otherAdmin(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, m.otherAdmin(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, schoolQueue(m), m.stranger(), null)).isEqualTo(404);
		// the lead of the school is a moderator too
		Persona lead = safeguardingLead(m, "wf-roles-lead");
		assertThat(queueIds(call(HttpMethod.GET, schoolQueue(m), lead, null, 200))).contains(caseId.toString());
		// another school's lead sees nothing of this school
		Msg other = school("wf-roles-other");
		Persona otherLead = safeguardingLead(other, "wf-roles-other-lead");
		assertThat(statusOf(HttpMethod.GET, schoolQueue(m), otherLead, null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, otherLead, null)).isEqualTo(404);
	}

	@Test
	void theQueueIsKeysetPagedHighPriorityFirst() throws Exception {
		Msg m = school("wf-paging");
		UUID normal1 = reportedPost(m, "SPAM", m.g1());
		UUID normal2 = reportedPost(m, "SPAM", m.g1b());
		UUID high = reportedPost(m, "SAFETY_CONCERN", m.g2());
		JsonNode first = call(HttpMethod.GET, schoolQueue(m) + "?limit=2", m.admin(), null, 200);
		assertThat(first.get("items")).hasSize(2);
		assertThat(first.get("items").get(0).get("priority").asString()).isEqualTo("HIGH");
		assertThat(first.get("items").get(0).get("targetId").asString()).isEqualTo(high.toString());
		String next = first.get("nextCursor").asString();
		JsonNode second = call(HttpMethod.GET, schoolQueue(m) + "?limit=2&cursor=" + next, m.admin(), null, 200);
		assertThat(second.get("items")).hasSize(1);
		assertThat(queueIds(first)).doesNotContainAnyElementsOf(queueIds(second));
		assertThat(queueIds(call(HttpMethod.GET, schoolQueue(m) + "?priority=HIGH", m.admin(), null, 200))).hasSize(1);
		assertThat(normal1).isNotEqualTo(normal2);
	}

	// --- S1.7 reporter identity -------------------------------------------------------------------------------------

	@Test
	void reporterIdentityIsVisibleOnlyToLeadsAndPlatformModerators() throws Exception {
		Msg m = school("wf-identity");
		Persona lead = safeguardingLead(m, "wf-identity-lead");
		UUID post = post(m, m.lead().userId(), "TEACHER", "who reported");
		report(m.student1(), "POST", post, "INAPPROPRIATE", "DETAILS-WITH-A-NAME-ABEL", 201);
		UUID caseId = caseOf("POST", post);
		// the school's administrator sees the role and the reason, never the person or the free text
		JsonNode asAdmin = detail(m.admin(), caseId, 200);
		assertThat(asAdmin.get("reporterIdentityVisible").asBoolean()).isFalse();
		JsonNode entry = asAdmin.get("reports").get(0);
		assertThat(entry.get("reporterRole").asString()).isEqualTo("STUDENT");
		assertThat(entry.get("reasonCode").asString()).isEqualTo("INAPPROPRIATE");
		assertThat(entry.has("reporterId") && !entry.get("reporterId").isNull()).isFalse();
		assertThat(entry.has("details") && !entry.get("details").isNull()).isFalse();
		assertThat(asAdmin.toString()).doesNotContain(m.student1().userId().toString()).doesNotContain("DETAILS-WITH-A-NAME-ABEL");
		// the lead sees both
		JsonNode asLead = detail(lead, caseId, 200);
		assertThat(asLead.get("reporterIdentityVisible").asBoolean()).isTrue();
		assertThat(asLead.get("reports").get(0).get("reporterId").asString()).isEqualTo(m.student1().userId().toString());
		assertThat(asLead.get("reports").get(0).get("details").asString()).isEqualTo("DETAILS-WITH-A-NAME-ABEL");
		// a platform moderator handling a platform case sees them too; a platform administrator works the queue but does not see them
		UUID adminPost = post(m, m.admin().userId(), "SCHOOL_ADMIN", "admin post");
		report(m.student1(), "POST", adminPost, "SPAM", "ANOTHER-DETAIL", 201);
		UUID platformCase = caseOf("POST", adminPost);
		Persona pm = this.personas.platformModerator("wf-identity-pm");
		assertThat(detail(pm, platformCase, 200).get("reports").get(0).get("reporterId").asString()).isEqualTo(m.student1().userId().toString());
		JsonNode asPlatformAdmin = detail(this.personas.platformAdmin(), platformCase, 200);
		assertThat(asPlatformAdmin.get("reporterIdentityVisible").asBoolean()).isFalse();
		assertThat(asPlatformAdmin.toString()).doesNotContain(m.student1().userId().toString()).doesNotContain("ANOTHER-DETAIL");
	}

	// --- S2.4 decisions ----------------------------------------------------------------------------------------------

	@Test
	void everyDecisionNeedsAReasonOfTenToFiveHundredCharacters() throws Exception {
		Msg m = school("wf-reason");
		UUID caseId = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		assertThat(code(decide(m.admin(), caseId, "NO_ACTION", "short", 400))).isEqualTo("moderation.reason_required");
		assertThat(code(decide(m.admin(), caseId, "NO_ACTION", "x".repeat(501), 400))).isEqualTo("moderation.reason_too_long");
		assertThat(code(decide(m.admin(), caseId, "NOPE", REASON, 400))).isEqualTo("moderation.decision_invalid");
		assertThat(row(caseId)).containsEntry("status", "OPEN");
		decide(m.admin(), caseId, "NO_ACTION", "y".repeat(500), 200);
		assertThat(code(decide(m.admin(), caseId, "NO_ACTION", REASON, 409))).isEqualTo("moderation.case_closed");
	}

	@Test
	void hideKeepsTheOriginalAndTheLeadStillReadsItThroughTheCase() throws Exception {
		Msg m = school("wf-hide");
		Persona lead = safeguardingLead(m, "wf-hide-lead");
		UUID post = reportedPost(m, "INAPPROPRIATE", m.g1());
		UUID caseId = caseOf("POST", post);
		decide(m.admin(), caseId, "HIDE", REASON, 200);
		// hidden from families, the original is still there, in the post and in the case-bound read
		assertThat(this.jdbc.queryForMap("select status, body, moderation_case_id, moderation_hidden_at from posts where id = ?", post))
			.containsEntry("status", "HIDDEN")
			.containsEntry("body", "ORIGINAL-POST-TEXT")
			.containsEntry("moderation_case_id", caseId);
		assertThat(code(report(m.g1b(), "POST", post, "SPAM", null, 404))).isEqualTo("moderation.target_not_found");
		JsonNode content = call(HttpMethod.GET, CASES + caseId + "/content", lead, null, 200);
		assertThat(content.get("kind").asString()).isEqualTo("TEXT");
		assertThat(content.get("items").get(0).get("text").asString()).isEqualTo("ORIGINAL-POST-TEXT");
		assertThat(content.get("items").get(0).get("status").asString()).isEqualTo("HIDDEN");
		// the history row keeps the reason; the audit row does not
		assertThat(this.jdbc.queryForObject("select notes from moderation_actions where case_id = ? and action = 'HIDE'", String.class, caseId)).isEqualTo(REASON);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and metadata::text like ?", Integer.class, caseId, "%" + REASON + "%"))
			.isZero();
	}

	@Test
	void theFeedsOwnUnhideCannotUndoACaseAndOnlyTheCaseRestoresIt() throws Exception {
		Msg m = school("wf-unhide");
		UUID post = reportedPost(m, "INAPPROPRIATE", m.g1());
		UUID caseId = caseOf("POST", post);
		decide(m.admin(), caseId, "HIDE", REASON, 200);
		String unhide = "/api/v1/schools/" + m.schoolId() + "/posts/" + post + "/unhide";
		assertThat(code(call(HttpMethod.POST, unhide, m.lead(), body("confirm", true), 409))).isEqualTo("moderation.content_held");
		assertThat(code(call(HttpMethod.POST, unhide, m.admin(), body("confirm", true), 409))).isEqualTo("moderation.content_held");
		assertThat(this.jdbc.queryForObject("select status from posts where id = ?", String.class, post)).isEqualTo("HIDDEN");
		// the case restores it (the same or a more senior moderator), once
		call(HttpMethod.POST, CASES + caseId + "/restore", m.admin(), body("reason", "The post was fine after all, restoring it"), 200);
		assertThat(this.jdbc.queryForObject("select status from posts where id = ?", String.class, post)).isEqualTo("PUBLISHED");
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/restore", m.admin(), body("reason", "Restoring it a second time"), 409)))
			.isEqualTo("moderation.nothing_to_restore");
	}

	@Test
	void removeKeepsTheOriginalToo_andTheCaseCannotRestoreIt() throws Exception {
		Msg m = school("wf-remove");
		UUID post = reportedPost(m, "INAPPROPRIATE", m.g1());
		UUID caseId = caseOf("POST", post);
		decide(m.admin(), caseId, "REMOVE", REASON, 200);
		assertThat(this.jdbc.queryForMap("select status, body from posts where id = ?", post)).containsEntry("status", "HIDDEN")
			.containsEntry("body", "ORIGINAL-POST-TEXT");
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/restore", m.admin(), body("reason", "Trying to restore a removal"), 409)))
			.isEqualTo("moderation.removal_not_reversible");
	}

	@Test
	void commentsAndStoriesAreHiddenAndRestoredThroughTheirOwner() throws Exception {
		Msg m = school("wf-comment-story");
		UUID post = post(m, m.lead().userId(), "TEACHER", "post");
		UUID comment = comment(m, post, m.student2().userId(), "STUDENT", "COMMENT-ORIGINAL");
		report(m.student1(), "POST_COMMENT", comment, "HARASSMENT");
		UUID commentCase = caseOf("POST_COMMENT", comment);
		decide(m.admin(), commentCase, "HIDE", REASON, 200);
		assertThat(this.jdbc.queryForMap("select status, body from post_comments where id = ?", comment)).containsEntry("status", "HIDDEN")
			.containsEntry("body", "COMMENT-ORIGINAL");
		// the comment's reader context returns the post and its comments, the reported one highlighted
		Persona lead = safeguardingLead(m, "wf-comment-lead");
		JsonNode content = call(HttpMethod.GET, CASES + commentCase + "/content", lead, null, 200);
		assertThat(content.get("items").get(0).get("type").asString()).isEqualTo("POST");
		assertThat(content.toString()).contains("COMMENT-ORIGINAL");
		call(HttpMethod.POST, CASES + commentCase + "/restore", m.admin(), body("reason", "Restoring the comment as agreed"), 200);
		assertThat(this.jdbc.queryForObject("select status from post_comments where id = ?", String.class, comment)).isEqualTo("VISIBLE");
		UUID story = story(m, m.lead().userId(), "STORY-CAPTION");
		report(m.g1(), "STORY", story, "INAPPROPRIATE");
		UUID storyCase = caseOf("STORY", story);
		decide(m.admin(), storyCase, "HIDE", REASON, 200);
		assertThat(this.jdbc.queryForMap("select status, caption from stories where id = ?", story)).containsEntry("status", "HIDDEN")
			.containsEntry("caption", "STORY-CAPTION");
	}

	@Test
	void aReportedMessageIsHiddenThroughMessagingAndStaysReadableOnlyThroughTheAuditedReview() throws Exception {
		Msg m = school("wf-message");
		UUID thread = teacherStudentThread(m);
		UUID message = send(m.lead(), thread, "MESSAGE-ORIGINAL");
		report(m.g1(), "MESSAGE", message, "INAPPROPRIATE"); // an observer guardian reports it
		UUID caseId = caseOf("MESSAGE", message);
		// the case is HIGH (safeguarded conversation) and the case-bound read is a pointer, never the text
		assertThat(row(caseId)).containsEntry("priority", "HIGH");
		JsonNode pointer = call(HttpMethod.GET, CASES + caseId + "/content", m.admin(), null, 200);
		assertThat(pointer.get("kind").asString()).isEqualTo("MESSAGING_REVIEW");
		assertThat(pointer.get("reviewConversationId").asString()).isEqualTo(thread.toString());
		assertThat(pointer.toString()).doesNotContain("MESSAGE-ORIGINAL");
		decide(m.admin(), caseId, "HIDE", REASON, 200);
		assertThat(this.jdbc.queryForMap("select status, body from messages where id = ?", message)).containsEntry("status", "HIDDEN")
			.containsEntry("body", "MESSAGE-ORIGINAL");
		// the sender's own unhide is refused; the audited review still reads the original
		assertThat(code(call(HttpMethod.POST, conv(thread) + "/messages/" + message + "/unhide", m.admin(), null, 409))).isEqualTo("moderation.content_held");
		JsonNode review = post(m.api() + "/review/conversations/" + thread + "/read", m.admin(), body("reason", "reviewing the hidden message of a case"), 200);
		assertThat(review.toString()).contains("MESSAGE-ORIGINAL");
		call(HttpMethod.POST, CASES + caseId + "/restore", m.admin(), body("reason", "Restoring the message as agreed"), 200);
		assertThat(this.jdbc.queryForObject("select status from messages where id = ?", String.class, message)).isEqualTo("SENT");
	}

	@Test
	void warnUserRecordsAWarningAndSendsAGenericNoticeWithoutQuotingAnything() throws Exception {
		Msg m = school("wf-warn");
		UUID post = reportedPost(m, "INAPPROPRIATE", m.g1());
		UUID caseId = caseOf("POST", post);
		decide(m.admin(), caseId, "WARN_USER", REASON, 200);
		assertThat(this.jdbc.queryForObject("select count(*) from account_warnings where user_id = ? and case_id = ?", Integer.class, m.lead().userId(), caseId))
			.isEqualTo(1);
		assertThat(notificationsOf(m.lead().userId(), "ACCOUNT_WARNING")).isEqualTo(1);
		var n = this.jdbc.queryForMap("select title, body, coalesce(data::text, '') as data from notifications where user_id = ? and type_code = 'ACCOUNT_WARNING'", m.lead().userId());
		String text = n.get("title") + " " + n.get("body") + " " + n.get("data");
		assertThat(text).doesNotContain("ORIGINAL-POST-TEXT").doesNotContain(REASON).doesNotContain(m.g1().userId().toString())
			.doesNotContain(caseId.toString());
	}

	@Test
	void aSchoolSuspensionOfAStudentEndsTheirActivityAtTheSchoolAndIsReversible() throws Exception {
		Msg m = school("wf-suspend-student");
		UUID post = post(m, m.lead().userId(), "TEACHER", "post");
		UUID comment = comment(m, post, m.student2().userId(), "STUDENT", "unkind");
		report(m.student1(), "POST_COMMENT", comment, "HARASSMENT");
		UUID caseId = caseOf("POST_COMMENT", comment);
		decide(m.admin(), caseId, "SUSPEND_USER", REASON, 200);
		assertThat(this.jdbc.queryForObject("select status from school_students where student_id = ? and school_id = ?", String.class, m.student2().userId(), m.schoolId()))
			.isEqualTo("SUSPENDED");
		assertThat(this.jdbc.queryForObject("select status from class_students where student_id = ? and school_id = ?", String.class, m.student2().userId(), m.schoolId()))
			.isEqualTo("FORMER");
		// nothing is deleted
		assertThat(this.jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, m.student2().userId())).isEqualTo(1);
		call(HttpMethod.POST, CASES + caseId + "/lift-suspension", m.admin(), body("reason", "The suspension is over, lifting it"), 200);
		assertThat(this.jdbc.queryForObject("select status from school_students where student_id = ? and school_id = ?", String.class, m.student2().userId(), m.schoolId()))
			.isEqualTo("ACTIVE");
		assertThat(this.jdbc.queryForObject("select status from class_students where student_id = ? and school_id = ?", String.class, m.student2().userId(), m.schoolId()))
			.isEqualTo("ACTIVE");
	}

	@Test
	void aSchoolSuspensionOfATeacherAndOfAGuardianTouchesOnlyWhatItOwnsAndLiftsExactlyThat() throws Exception {
		Msg m = school("wf-suspend-adults");
		// a teacher
		UUID post = post(m, m.subject().userId(), "TEACHER", "teacher post");
		report(m.g1(), "POST", post, "INAPPROPRIATE");
		UUID teacherCase = caseOf("POST", post);
		decide(m.admin(), teacherCase, "SUSPEND_USER", REASON, 200);
		assertThat(this.jdbc.queryForObject("select status from school_staff where user_id = ? and school_id = ?", String.class, m.subject().userId(), m.schoolId()))
			.isEqualTo("SUSPENDED");
		assertThat(this.jdbc.queryForObject("select status from class_teachers where teacher_id = ?", String.class, m.subject().userId())).isEqualTo("FORMER");
		call(HttpMethod.POST, CASES + teacherCase + "/lift-suspension", m.admin(), body("reason", "Teacher is back, lifting it"), 200);
		assertThat(this.jdbc.queryForObject("select status from school_staff where user_id = ? and school_id = ?", String.class, m.subject().userId(), m.schoolId()))
			.isEqualTo("ACTIVE");
		// a guardian: the link is restricted and un-restricted again; a restriction that was already there is never touched
		UUID post2 = post(m, m.lead().userId(), "TEACHER", "another");
		UUID guardianComment = comment(m, post2, m.g1b().userId(), "GUARDIAN", "a guardian comment");
		report(m.g2(), "POST_COMMENT", guardianComment, "HARASSMENT");
		UUID guardianCase = caseOf("POST_COMMENT", guardianComment);
		decide(m.admin(), guardianCase, "SUSPEND_USER", REASON, 200);
		assertThat(this.jdbc.queryForObject("select custody_restricted from student_guardians where guardian_id = ?", Boolean.class, m.g1b().userId())).isTrue();
		call(HttpMethod.POST, CASES + guardianCase + "/lift-suspension", m.admin(), body("reason", "Guardian reinstated, lifting it"), 200);
		assertThat(this.jdbc.queryForObject("select custody_restricted from student_guardians where guardian_id = ?", Boolean.class, m.g1b().userId())).isFalse();
		UUID restrictedComment = comment(m, post2, m.restricted().userId(), "GUARDIAN", "from a restricted guardian");
		report(m.g2(), "POST_COMMENT", restrictedComment, "SPAM");
		assertThat(code(decide(m.admin(), caseOf("POST_COMMENT", restrictedComment), "SUSPEND_USER", REASON, 409))).isEqualTo("moderation.subject_not_a_member");
		assertThat(this.jdbc.queryForObject("select custody_restricted from student_guardians where guardian_id = ?", Boolean.class, m.restricted().userId())).isTrue();
	}

	@Test
	void aPlatformLockIsForPlatformModeratorsAndRevokesEverySession() throws Exception {
		Msg m = school("wf-lock");
		UUID post = post(m, m.admin().userId(), "SCHOOL_ADMIN", "from an administrator");
		report(m.student1(), "POST", post, "SAFETY_CONCERN");
		UUID caseId = caseOf("POST", post);
		Persona pm = this.personas.platformModerator("wf-lock-pm");
		// a school moderator cannot lock, not even through a SUSPEND_USER decision at platform level
		Persona schoolModerator = this.personas.schoolAdmin(m.schoolId(), "wf-lock-schoolmod");
		UUID other = post(m, m.lead().userId(), "TEACHER", "x");
		report(m.g1(), "POST", other, "SPAM");
		JsonNode refused = call(HttpMethod.POST, CASES + caseOf("POST", other) + "/decision", schoolModerator,
				body("decision", "SUSPEND_USER", "reason", REASON, "level", "PLATFORM"), 403);
		assertThat(code(refused)).isEqualTo("moderation.platform_lock_not_allowed");
		assertThat(this.jdbc.queryForObject("select status from users where id = ?", String.class, m.lead().userId())).isEqualTo("ACTIVE");
		// the platform moderator locks the administrator the case is about: every session ends at once
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", m.admin(), null)).isEqualTo(200);
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where user_id = ? and revoked_at is null", Integer.class, m.admin().userId()))
			.isPositive();
		call(HttpMethod.POST, CASES + caseId + "/decision", pm, body("decision", "SUSPEND_USER", "reason", REASON, "level", "PLATFORM"), 200);
		assertThat(this.jdbc.queryForObject("select status from users where id = ?", String.class, m.admin().userId())).isEqualTo("LOCKED");
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where user_id = ? and revoked_at is null", Integer.class, m.admin().userId()))
			.isZero();
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", m.admin(), null)).isEqualTo(401);
		// nothing was deleted, and the lock is reversible by whoever may lock
		assertThat(this.jdbc.queryForObject("select count(*) from posts where id = ?", Integer.class, post)).isEqualTo(1);
		assertThat(statusOf(HttpMethod.POST, CASES + caseId + "/lift-suspension", schoolModerator, body("reason", "Lifting the lock of the case"))).isEqualTo(404);
		call(HttpMethod.POST, CASES + caseId + "/lift-suspension", pm, body("reason", "Lifting the lock of the case"), 200);
		assertThat(this.jdbc.queryForObject("select status from users where id = ?", String.class, m.admin().userId())).isEqualTo("ACTIVE");
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", this.personas.personaFor("again", m.admin().userId()), null)).isEqualTo(200);
	}

	@Test
	void theDirectPlatformLockEndpointsNeedThePermissionAReasonAndAreAudited() throws Exception {
		Msg m = school("wf-lock-direct");
		Persona pm = this.personas.platformModerator("wf-lock-direct-pm");
		String lock = "/api/v1/platform/users/" + m.student1().userId() + "/lock";
		String unlock = "/api/v1/platform/users/" + m.student1().userId() + "/unlock";
		assertThat(statusOf(HttpMethod.POST, lock, m.admin(), body("reason", REASON))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.POST, lock, m.lead(), body("reason", REASON))).isEqualTo(403);
		assertThat(code(call(HttpMethod.POST, lock, pm, body("reason", "short"), 400))).isEqualTo("moderation.reason_required");
		// a student can be locked by a platform moderator (never by a school moderator)
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", m.student1(), null)).isEqualTo(200);
		call(HttpMethod.POST, lock, pm, body("reason", REASON), 200);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", m.student1(), null)).isEqualTo(401);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'moderation.user.lock' and target_id = ?", Integer.class, m.student1().userId()))
			.isEqualTo(1);
		call(HttpMethod.POST, unlock, pm, body("reason", REASON), 200);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/me", this.personas.personaFor("student again", m.student1().userId()), null)).isEqualTo(200);
		// nobody locks themselves
		assertThat(code(call(HttpMethod.POST, "/api/v1/platform/users/" + pm.userId() + "/lock", pm, body("reason", REASON), 409))).isEqualTo("moderation.cannot_lock_self");
	}

	// --- S2.5 conflicts ------------------------------------------------------------------------------------------------

	@Test
	void nobodyWorksACaseAboutThemselvesTheirChildOrOneTheyReported() throws Exception {
		Msg m = school("wf-conflict");
		// the second administrator is the guardian of Bethlehem (student2) and reports a post
		Persona conflicted = this.personas.schoolAdmin(m.schoolId(), "wf-conflict-admin");
		this.personas.linkGuardian(m.s2(), conflicted.userId(), "ACTIVE", false);
		UUID post = post(m, m.lead().userId(), "TEACHER", "p");
		UUID comment = comment(m, post, m.student2().userId(), "STUDENT", "about my child");
		report(m.student1(), "POST_COMMENT", comment, "SPAM");
		UUID aboutHerChild = caseOf("POST_COMMENT", comment);
		assertThat(statusOf(HttpMethod.GET, CASES + aboutHerChild, conflicted, null)).isEqualTo(404);
		assertThat(queueIds(call(HttpMethod.GET, schoolQueue(m), conflicted, null, 200))).doesNotContain(aboutHerChild.toString());
		assertThat(statusOf(HttpMethod.POST, CASES + aboutHerChild + "/decision", conflicted, body("decision", "NO_ACTION", "reason", REASON))).isEqualTo(404);
		// the other administrator can, and cannot assign it to the conflicted one
		assertThat(statusOf(HttpMethod.GET, CASES + aboutHerChild, m.admin(), null)).isEqualTo(200);
		assertThat(code(call(HttpMethod.POST, CASES + aboutHerChild + "/assign", m.admin(), body("assigneeId", conflicted.userId()), 400)))
			.isEqualTo("moderation.assignee_invalid");
		// a moderator who reported something does not work that case
		UUID reported = post(m, m.lead().userId(), "TEACHER", "reported by the administrator");
		report(m.admin(), "POST", reported, "SPAM");
		assertThat(statusOf(HttpMethod.GET, CASES + caseOf("POST", reported), m.admin(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, CASES + caseOf("POST", reported), conflicted, null)).isEqualTo(200);
		// the subject of a case never works it: a school administrator who wrote the reported post
		UUID adminPost = post(m, conflicted.userId(), "SCHOOL_ADMIN", "mine");
		report(m.g1(), "POST", adminPost, "SPAM");
		assertThat(statusOf(HttpMethod.GET, CASES + caseOf("POST", adminPost), conflicted, null)).isEqualTo(404);
	}

	// --- S2.6 assignment, reopening, hand-up ------------------------------------------------------------------------

	@Test
	void assignmentAndReassignmentAreAuditedAndTheAssigneeIsNotifiedGenerically() throws Exception {
		Msg m = school("wf-assign");
		UUID caseId = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		Persona second = this.personas.schoolAdmin(m.schoolId(), "wf-assign-second");
		JsonNode assigned = call(HttpMethod.POST, CASES + caseId + "/assign", m.admin(), body(), 200);
		assertThat(assigned.get("summary").get("status").asString()).isEqualTo("ASSIGNED");
		call(HttpMethod.POST, CASES + caseId + "/assign", m.admin(), body("assigneeId", second.userId()), 200);
		assertThat(row(caseId)).containsEntry("assigned_to", second.userId());
		assertThat(notificationsOf(second.userId(), "CASE_ASSIGNED")).isEqualTo(1);
		assertThat(this.jdbc.queryForList("select action from moderation_actions where case_id = ? order by created_at, id", String.class, caseId))
			.containsExactly("ASSIGN", "REASSIGN");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action in ('moderation.case.assign', 'moderation.case.reassign')",
				Integer.class, caseId)).isEqualTo(2);
		// somebody without queue access cannot be assigned
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/assign", m.admin(), body("assigneeId", m.lead().userId()), 400))).isEqualTo("moderation.assignee_invalid");
	}

	@Test
	void aResolvedCaseIsReopenedWithAReasonAndOnlyOnceAtATime() throws Exception {
		Msg m = school("wf-reopen");
		UUID post = reportedPost(m, "SPAM", m.g1());
		UUID caseId = caseOf("POST", post);
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/reopen", m.admin(), body("reason", "Not resolved yet but trying"), 409))).isEqualTo("moderation.case_not_resolved");
		decide(m.admin(), caseId, "NO_ACTION", REASON, 200);
		assertThat(code(call(HttpMethod.POST, CASES + caseId + "/reopen", m.admin(), body("reason", "short"), 400))).isEqualTo("moderation.reason_required");
		call(HttpMethod.POST, CASES + caseId + "/reopen", m.admin(), body("reason", "New information came in, reopening"), 200);
		assertThat(row(caseId)).containsEntry("status", "OPEN");
		assertThat(this.jdbc.queryForList("select action from moderation_actions where case_id = ? order by created_at, id", String.class, caseId))
			.containsExactly("NO_ACTION", "REOPEN");
		// a new report after the reopening joins the same case
		report(m.g1b(), "POST", post, "SPAM");
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where target_id = ?", Integer.class, post)).isEqualTo(1);
	}

	@Test
	void aSchoolModeratorHandsACaseUpToThePlatformQueue() throws Exception {
		Msg m = school("wf-referup");
		UUID caseId = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		call(HttpMethod.POST, CASES + caseId + "/refer-up", m.admin(), body("reason", "A matter above the school, handing it up"), 200);
		assertThat(row(caseId)).containsEntry("queue", "PLATFORM");
		assertThat(statusOf(HttpMethod.GET, CASES + caseId, m.admin(), null)).isEqualTo(404);
		Persona pm = this.personas.platformModerator("wf-referup-pm");
		assertThat(queueIds(call(HttpMethod.GET, "/api/v1/platform/moderation/cases", pm, null, 200))).contains(caseId.toString());
		decide(pm, caseId, "NO_ACTION", REASON, 200);
	}

	// --- S2.4 the append-only history ----------------------------------------------------------------------------------

	@Test
	void theHistoryOfACaseIsAppendOnly() throws Exception {
		Msg m = school("wf-append");
		UUID caseId = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		decide(m.admin(), caseId, "NO_ACTION", REASON, 200);
		assertThatThrownBy(() -> this.jdbc.update("update moderation_actions set notes = 'edited' where case_id = ?", caseId))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("delete from moderation_actions where case_id = ?", caseId)).hasMessageContaining("append-only");
	}

	// --- S2.2 priority by leads, S3 the case-bound read ---------------------------------------------------------------

	@Test
	void onlyLeadsSetThePriorityAndCriticalOnASafetyConcernRaisesTheIncidentOnce() throws Exception {
		Msg m = school("wf-critical");
		Persona lead = safeguardingLead(m, "wf-critical-lead");
		UUID caseId = caseOf("POST", reportedPost(m, "SAFETY_CONCERN", m.g1()));
		assertThat(code(call(HttpMethod.PUT, CASES + caseId + "/priority", m.admin(), body("priority", "CRITICAL"), 403))).isEqualTo("moderation.priority_not_allowed");
		call(HttpMethod.PUT, CASES + caseId + "/priority", lead, body("priority", "CRITICAL"), 200);
		call(HttpMethod.PUT, CASES + caseId + "/priority", lead, body("priority", "CRITICAL"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from safeguarding_incidents where source_case_id = ?", Integer.class, caseId)).isEqualTo(1);
		assertThat(row(caseId).get("safeguarding_incident_id")).isNotNull();
		// the moderator is not told that an incident exists
		assertThat(detail(m.admin(), caseId, 200).toString()).doesNotContain("incidentId");
		assertThat(detail(lead, caseId, 200).get("incidentId").asString()).isNotBlank();
	}

	@Test
	void theContentReadIsAuditedWithoutTextAndRateLimited() throws Exception {
		Msg m = school("wf-read");
		Persona reader = this.personas.schoolAdmin(m.schoolId(), "wf-read-admin");
		UUID post = reportedPost(m, "INAPPROPRIATE", m.g1());
		UUID caseId = caseOf("POST", post);
		call(HttpMethod.GET, CASES + caseId + "/content", reader, null, 200);
		var rows = this.jdbc.queryForList("select actor_user_id, target_id, metadata::text as metadata from audit_logs where action = 'moderation.case.content_read' and target_id = ?", caseId);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("actor_user_id")).isEqualTo(reader.userId());
		assertThat((String) rows.getFirst().get("metadata")).doesNotContain("ORIGINAL-POST-TEXT").contains(post.toString());
		// a person who may not work the case reads nothing and leaves no read row
		assertThat(statusOf(HttpMethod.GET, CASES + caseId + "/content", m.lead(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, CASES + caseId + "/content", m.otherAdmin(), null)).isEqualTo(404);
		for (int i = 1; i < 60; i++) {
			call(HttpMethod.GET, CASES + caseId + "/content", reader, null, 200);
		}
		assertThat(code(call(HttpMethod.GET, CASES + caseId + "/content", reader, null, 429))).isEqualTo("moderation.read_rate_limited");
	}

	// --- S6 retention and legal hold ----------------------------------------------------------------------------------

	@Test
	void theRetentionPurgeRemovesOnlyExpiredUnheldCasesAndIsIdempotent() throws Exception {
		Msg m = school("wf-purge");
		Persona lead = safeguardingLead(m, "wf-purge-lead");
		UUID old = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		UUID recent = caseOf("POST", reportedPost(m, "SPAM", m.g1b()));
		UUID held = caseOf("POST", reportedPost(m, "SPAM", m.g2()));
		UUID open = caseOf("POST", reportedPost(m, "SPAM", m.student1()));
		for (UUID c : List.of(old, recent, held)) {
			decide(m.admin(), c, "NO_ACTION", REASON, 200);
		}
		call(HttpMethod.PUT, CASES + held + "/legal-hold", lead, body("held", true), 200);
		this.jdbc.update("update moderation_cases set resolved_at = ? where id in (?, ?)", ago(java.time.Period.ofYears(3)), old, held);
		this.jdbc.update("update moderation_cases set resolved_at = ? where id = ?", ago(java.time.Period.ofMonths(6)), recent);
		int purged = this.retention.purgeExpired();
		assertThat(purged).isEqualTo(1);
		assertThat(this.jdbc.queryForList("select id from moderation_cases where id in (?, ?, ?, ?)", UUID.class, old, recent, held, open))
			.containsExactlyInAnyOrder(recent, held, open);
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_actions where case_id = ?", Integer.class, old)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from content_reports where case_id = ?", Integer.class, old)).isZero();
		assertThat(this.retention.purgeExpired()).isZero(); // idempotent
		// lifting the hold makes it purgeable
		call(HttpMethod.PUT, CASES + held + "/legal-hold", lead, body("held", false), 200);
		assertThat(this.retention.purgeExpired()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where id = ?", Integer.class, held)).isZero();
	}

	@Test
	void legalHoldIsSetAndLiftedOnlyByLeadsAndPlatformModeratorsAndIsAudited() throws Exception {
		Msg m = school("wf-hold-who");
		Persona lead = safeguardingLead(m, "wf-hold-lead");
		UUID caseId = caseOf("POST", reportedPost(m, "SPAM", m.g1()));
		assertThat(statusOf(HttpMethod.PUT, CASES + caseId + "/legal-hold", m.admin(), body("held", true))).isEqualTo(404);
		assertThat(statusOf(HttpMethod.PUT, CASES + caseId + "/legal-hold", m.lead(), body("held", true))).isEqualTo(404);
		call(HttpMethod.PUT, CASES + caseId + "/legal-hold", lead, body("held", true), 200);
		Persona pm = this.personas.platformModerator("wf-hold-pm");
		call(HttpMethod.PUT, CASES + caseId + "/legal-hold", pm, body("held", false), 200);
		assertThat(this.jdbc.queryForList("select action from audit_logs where target_id = ? and action like 'moderation.case.hold_%' order by id", String.class, caseId))
			.containsExactly("moderation.case.hold_set", "moderation.case.hold_lifted");
	}

}
