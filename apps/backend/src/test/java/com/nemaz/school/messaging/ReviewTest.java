package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R5b and R13: the audited review of admin-visible and reported conversations. */
@IntegrationTest
class ReviewTest extends MessagingTestSupport {

	/** The safeguarded conversation of Chala, who has no guardian: admin-visible. */
	private UUID adminVisibleThread(Msg m) throws Exception {
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		return conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
	}

	private JsonNode review(Persona who, Msg m, UUID thread, String reason, int status) throws Exception {
		return post(m.api() + "/review/conversations/" + thread + "/read", who, body("reason", reason), status);
	}

	@Test
	void reviewNeedsPermissionAndAReason() throws Exception {
		Msg m = school("rev-perm");
		UUID thread = adminVisibleThread(m);
		send(m.lead(), thread, "hello Chala");
		// the permission: administrators of the school; everybody else is refused, outsiders as if it did not exist
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.lead(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.student1(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.stranger(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.otherAdmin(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", m.lead(), body("reason", "curious teacher")))
			.isEqualTo(403);
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", m.otherAdmin(), body("reason", "another school")))
			.isEqualTo(404);
		// the reason is mandatory
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", m.admin(), body())).isEqualTo(400);
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", m.admin(), body("reason", "short"))).isEqualTo(400);
		assertThat(code(review(m.admin(), m, thread, "          ​   ", 400))).isEqualTo("messaging.review_reason_required");
		JsonNode page = review(m.admin(), m, thread, "periodic safeguarding review", 200);
		assertThat(page.get("messages")).hasSize(1);
		assertThat(page.get("messages").get(0).get("body").asString()).isEqualTo("hello Chala");
		// a conversation of another school or an unknown one
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + UUID.randomUUID() + "/read", m.admin(), body("reason", "unknown id here")))
			.isEqualTo(404);
	}

	@Test
	void everyReadIsAudited() throws Exception {
		Msg m = school("rev-audit");
		UUID thread = adminVisibleThread(m);
		send(m.lead(), thread, "CANARY-REVIEW-TEXT");
		get(m.api() + "/review/conversations", m.admin());
		review(m.admin(), m, thread, "checking the safeguarding register", 200);
		review(m.admin(), m, thread, "second look, same register", 200);
		var rows = this.jdbc.queryForList("""
				select actor_user_id, school_id, target_id, metadata::text as metadata from audit_logs
				where action = 'messaging.review.read' and target_id = ? order by id""", thread);
		assertThat(rows).hasSize(2);
		assertThat(rows.getFirst().get("actor_user_id")).isEqualTo(m.admin().userId());
		assertThat(rows.getFirst().get("school_id")).isEqualTo(m.schoolId());
		String metadata = (String) rows.getFirst().get("metadata");
		assertThat(metadata).contains("checking the safeguarding register").contains("\"messages\": 1")
			.doesNotContain("CANARY");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'messaging.review.list' and school_id = ?", Integer.class,
				m.schoolId())).isEqualTo(1);
		// a refused read leaves no read row
		assertThat(statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", m.lead(), body("reason", "not a reviewer"))).isEqualTo(403);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'messaging.review.read' and target_id = ?", Integer.class,
				thread)).isEqualTo(2);
	}

	@Test
	void onlyAdminVisibleOrReportedConversationsAreReviewable() throws Exception {
		Msg m = school("rev-scope");
		UUID ordinary = parentTeacherThread(m);
		send(m.g1(), ordinary, "a normal parent-teacher thread");
		UUID observed = teacherStudentThread(m);
		send(m.lead(), observed, "observed by the guardians");
		UUID classThread = classConversation(m);
		UUID classMessage = send(m.student1(), classThread, "class chat");
		for (UUID not : new UUID[] { ordinary, observed, classThread }) {
			assertThat(code(review(m.admin(), m, not, "should not be possible", 403))).isEqualTo("messaging.not_reviewable");
		}
		assertThat(get(m.api() + "/review/conversations", m.admin()).get("items")).isEmpty();
		// a report opens it
		call(HttpMethod.POST, conv(ordinary) + "/messages/" + send(m.lead(), ordinary, "reply") + "/report", m.g1(), body("reason", "OTHER"), 204);
		call(HttpMethod.POST, conv(classThread) + "/messages/" + classMessage + "/report", m.student2(), body("reason", "SPAM"), 204);
		assertThat(get(m.api() + "/review/conversations", m.admin()).get("items")).hasSize(2);
		JsonNode page = review(m.admin(), m, ordinary, "report received for this thread", 200);
		assertThat(page.get("messages")).hasSize(2);
		review(m.admin(), m, classThread, "report received for the class chat", 200);
		assertThat(code(review(m.admin(), m, observed, "still not reviewable", 403))).isEqualTo("messaging.not_reviewable");
		// when the observers are gone the observed one is too
		restrict(m.g1Link());
		restrict(linkOf(m.g1b(), m.s1()));
		assertThat(get(m.api() + "/review/conversations", m.admin()).get("items")).hasSize(3);
		review(m.admin(), m, observed, "no guardian can observe it now", 200);
		// reading as a reviewer does not make the reviewer a member
		assertThat(statusOf(HttpMethod.GET, conv(observed), m.admin(), null)).isEqualTo(404);
	}

	@Test
	void deletedAndEditedOriginalsAreOnlyInTheReview() throws Exception {
		Msg m = school("rev-history");
		UUID thread = adminVisibleThread(m);
		UUID edited = send(m.student3(), thread, "CANARY-ORIGINAL first draft");
		call(HttpMethod.PATCH, conv(thread) + "/messages/" + edited, m.student3(), body("body", "second draft"), 200);
		UUID deleted = send(m.student3(), thread, "CANARY-DELETED what I really said");
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + deleted, m.student3(), null, 204);
		UUID hidden = send(m.student3(), thread, "CANARY-HIDDEN please hide this");
		call(HttpMethod.POST, conv(thread) + "/messages/" + hidden + "/report", m.lead(), body("reason", "INAPPROPRIATE"), 204);
		call(HttpMethod.POST, conv(thread) + "/messages/" + hidden + "/hide", m.admin(), body("reason", "as reported"), 204);
		// the people in the conversation never get an original back
		String everything = messagesOf(m.lead(), thread).toString() + messagesOf(m.student3(), thread) + inbox(m.lead()) + inbox(m.student3())
			+ get(conv(thread) + "/messages/" + deleted, m.student3()) + get(conv(thread) + "/messages/" + edited, m.student3());
		assertThat(everything).doesNotContain("CANARY-ORIGINAL").doesNotContain("CANARY-DELETED").doesNotContain("CANARY-HIDDEN");
		// the review has the whole history
		JsonNode page = review(m.admin(), m, thread, "looking at everything that was said", 200);
		String review = page.toString();
		assertThat(review).contains("CANARY-ORIGINAL").contains("CANARY-DELETED").contains("CANARY-HIDDEN").contains("second draft");
		for (JsonNode message : page.get("messages")) {
			String status = message.get("status").asString();
			if ("DELETED".equals(status)) {
				assertThat(message.get("body").asString()).startsWith("CANARY-DELETED");
				assertThat(message.get("history").get(0).get("kind").asString()).isEqualTo("DELETE");
			}
			if ("EDITED".equals(status)) {
				assertThat(message.get("body").asString()).isEqualTo("second draft");
				assertThat(message.get("history").get(0).get("body").asString()).startsWith("CANARY-ORIGINAL");
			}
			if ("HIDDEN".equals(status)) {
				assertThat(message.get("hideReason").asString()).isEqualTo("as reported");
				assertThat(message.get("reportCount").asInt()).isEqualTo(1);
			}
		}
		// and nowhere else: not in the audit log, the events or the notifications
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where metadata::text like '%CANARY%' or changes::text like '%CANARY%'", Integer.class))
			.isZero();
	}

	@Test
	void reviewIsRateLimited() throws Exception {
		Msg m = school("rev-limit");
		UUID thread = adminVisibleThread(m);
		send(m.lead(), thread, "hello");
		Persona reviewer = this.personas.schoolAdmin(m.schoolId(), "rev-limit-reviewer");
		int limited = -1;
		for (int i = 1; i <= 40 && limited < 0; i++) {
			int status = statusOf(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", reviewer,
					body("reason", "routine review number " + i));
			if (status == 429) {
				limited = i;
			}
			else {
				assertThat(status).isEqualTo(200);
			}
		}
		assertThat(limited).isBetween(31, 40);
		JsonNode problem = call(HttpMethod.POST, m.api() + "/review/conversations/" + thread + "/read", reviewer,
				body("reason", "one more review please"), 429);
		assertThat(code(problem)).isEqualTo("messaging.review_rate_limited");
	}

	@Test
	void platformAdminsCanListReviews() throws Exception {
		Msg m = school("rev-platform");
		UUID thread = adminVisibleThread(m);
		review(m.admin(), m, thread, "audit trail check for platform", 200);
		JsonNode logs = get("/api/v1/platform/audit-logs?actionPrefix=messaging.review.&schoolId=" + m.schoolId(), this.personas.platformAdmin());
		List<String> actions = new java.util.ArrayList<>();
		logs.get("items").forEach((e) -> actions.add(e.get("action").asString()));
		assertThat(actions).contains("messaging.review.read");
		assertThat(logs.get("items").get(0).get("actorUserId").asString()).isEqualTo(m.admin().userId().toString());
		assertThat(statusOf(HttpMethod.GET, "/api/v1/platform/audit-logs?actionPrefix=messaging.review.", m.admin(), null)).isEqualTo(403);
	}

	@Test
	void safeguardingLeadCanReview() throws Exception {
		Msg m = school("rev-lead");
		UUID thread = adminVisibleThread(m);
		send(m.lead(), thread, "for the safeguarding lead");
		Persona safeguarding = this.personas.withRole("rev-lead-safeguarding", RoleCodes.SAFEGUARDING_LEAD, AccessScope.school(m.schoolId()));
		assertThat(get(m.api() + "/review/conversations", safeguarding).get("items")).hasSize(1);
		assertThat(review(safeguarding, m, thread, "I am the safeguarding lead", 200).get("messages")).hasSize(1);
		// but is no moderator of groups
		assertThat(code(post(m.api() + "/groups", safeguarding, body("kind", "SCHOOL_STAFF", "title", "x"), 403))).isEqualTo("messaging.group_not_allowed");
		// a campus administrator holds neither permission
		Persona campus = this.personas.withRole("rev-lead-campus", RoleCodes.CAMPUS_ADMIN, AccessScope.campus(m.schoolId(),
				this.jdbc.queryForObject("insert into campuses (id, school_id, name, code) values (uuidv7(), ?, 'C', 'C') returning id", UUID.class, m.schoolId())));
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", campus, null)).isEqualTo(403);
	}

	@Test
	void permissionsAreSeeded() {
		for (String permission : new String[] { "school.messaging.moderate", "school.messaging.review" }) {
			for (String role : new String[] { "PLATFORM_ADMIN", "COUNTRY_ADMIN", "ORGANIZATION_ADMIN", "SCHOOL_ADMIN" }) {
				assertThat(this.jdbc.queryForObject("select count(*) from role_permissions where role_code = ? and permission_code = ?", Integer.class,
						role, permission)).as(role + " " + permission).isEqualTo(1);
			}
			assertThat(this.jdbc.queryForObject("select count(*) from role_permissions where role_code in ('CAMPUS_ADMIN', 'SCHOOL_STAFF', 'SUPPORT_AGENT') and permission_code = ?",
					Integer.class, permission)).isZero();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from role_permissions where role_code = 'SAFEGUARDING_LEAD' and permission_code = 'school.messaging.review'",
				Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from role_permissions where role_code = 'SAFEGUARDING_LEAD' and permission_code = 'school.messaging.moderate'",
				Integer.class)).isZero();
	}

}
