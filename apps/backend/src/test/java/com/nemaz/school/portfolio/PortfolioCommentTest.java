package com.nemaz.school.portfolio;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P5: encouraging comments, who reads and writes them, moderation. */
@IntegrationTest
class PortfolioCommentTest extends PortfolioTestSupport {

	private static String comments(Pf p, UUID item) {
		return p.item(item) + "/comments";
	}

	private JsonNode comment(Pf p, UUID item, Persona who, String text, int status) throws Exception {
		return post(comments(p, item), who, body("body", text), status);
	}

	@Test
	void onlyApprovedGuardianVisibleItemsAcceptComments() throws Exception {
		Pf p = pf("com1");
		UUID submitted = submitted(p, p.student1(), "waiting");
		assertThat(code(call(HttpMethod.POST, comments(p, submitted), p.student1(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		assertThat(code(call(HttpMethod.POST, comments(p, submitted), p.lead(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		UUID teachersOnly = approved(p, p.student1(), "private", "STUDENT_TEACHER");
		assertThat(code(call(HttpMethod.POST, comments(p, teachersOnly), p.lead(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		assertThat(code(call(HttpMethod.POST, comments(p, teachersOnly), p.student1(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		// guardian-visible only through the school's cap
		cap(p, "STUDENT_TEACHER");
		UUID capped = approved(p, p.student1(), "capped", "GUARDIANS");
		assertThat(code(call(HttpMethod.POST, comments(p, capped), p.lead(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		cap(p, "GUARDIANS");
		comment(p, capped, p.lead(), "well done", 201);
		// an archived item takes no new comments
		post(p.item(capped) + "/archive", p.student1(), body(), 200);
		assertThat(code(call(HttpMethod.POST, comments(p, capped), p.student1(), body("body", "hi"), 409))).isEqualTo("portfolio.comments_not_available");
		// and the capability says so
		UUID ok = approved(p, p.student1(), "fine", "GUARDIANS");
		assertThat(get(p.item(ok), p.guardian1()).get("capabilities").get("canComment").asBoolean()).isTrue();
		assertThat(get(p.item(teachersOnly), p.student1()).get("capabilities").get("canComment").asBoolean()).isFalse();
	}

	@Test
	void whoMayWriteComments() throws Exception {
		Pf p = pf("com2");
		cap(p, "CLASS");
		UUID id = id(create(p.student1(), p, textBody("class item", "x", "mode", "SUBMIT", "visibility", "CLASS", "subjectId", p.mathsId()), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(comment(p, id, p.student1(), "thank you", 201).get("authorRole").asString()).isEqualTo("STUDENT");
		assertThat(comment(p, id, p.guardian1(), "so proud", 201).get("authorRole").asString()).isEqualTo("GUARDIAN");
		for (Persona reviewer : List.of(p.lead(), p.co(), p.mathsTeacher(), p.admin())) {
			assertThat(comment(p, id, reviewer, "keep going", 201).get("authorRole").asString()).as(reviewer.name()).isEqualTo("TEACHER");
		}
		// people who see the class item but are not reviewers, classmates, other families, outsiders
		for (Persona seesButCannot : List.of(p.artTeacher(), p.assistant(), p.student2())) {
			assertThat(code(call(HttpMethod.POST, comments(p, id), seesButCannot, body("body", "x"), 403))).as(seesButCannot.name())
				.isEqualTo("portfolio.comments_not_allowed");
		}
		for (Persona nobody : List.of(p.guardian2(), p.restricted(), p.revoked(), p.pending(), p.otherTeacher(), p.otherStudent(), p.adminB(), p.studentB())) {
			assertThat(statusOf(HttpMethod.POST, comments(p, id), nobody, body("body", "x"))).as(nobody.name()).isIn(404);
		}
		assertThat(statusOf(HttpMethod.POST, comments(p, id), null, body("body", "x"))).isEqualTo(401);
	}

	@Test
	void commentsAreVisibleOnlyToStudentOwnGuardiansAndReviewers() throws Exception {
		Pf p = pf("com3");
		UUID id = approved(p, p.student1(), "thread", "GUARDIANS");
		comment(p, id, p.guardian1(), "from the parent", 201);
		comment(p, id, p.lead(), "from the teacher", 201);
		comment(p, id, p.student1(), "from the student", 201);
		for (Persona reader : List.of(p.student1(), p.guardian1(), p.lead(), p.admin(), p.co())) {
			JsonNode page = get(comments(p, id), reader);
			assertThat(page.get("items").size()).as(reader.name()).isEqualTo(3);
		}
		assertThat(get(p.item(id), p.student1()).get("commentCount").asInt()).isEqualTo(3);
		// oldest first, keyset paged
		JsonNode first = get(comments(p, id) + "?limit=2", p.guardian1());
		assertThat(first.get("items").get(0).get("body").asString()).isEqualTo("from the parent");
		JsonNode second = get(comments(p, id) + "?limit=2&cursor=" + first.get("nextCursor").asString(), p.guardian1());
		assertThat(second.get("items").size()).isEqualTo(1);
		assertThat(second.get("items").get(0).get("body").asString()).isEqualTo("from the student");
		for (Persona nobody : List.of(p.student2(), p.guardian2(), p.assistant(), p.otherTeacher(), p.adminB())) {
			assertThat(statusOf(HttpMethod.GET, comments(p, id), nobody, null)).as(nobody.name()).isEqualTo(404);
		}
	}

	@Test
	void commentsStayPrivateOnClassAndSchoolItems() throws Exception {
		Pf p = pf("com4");
		cap(p, "SCHOOL");
		UUID id = approved(p, p.student1(), "everywhere", "SCHOOL");
		comment(p, id, p.guardian1(), "PRIVATE-FAMILY-COMMENT", 201);
		comment(p, id, p.lead(), "PRIVATE-TEACHER-COMMENT", 201);
		for (Persona audience : List.of(p.student2(), p.otherStudent(), p.otherTeacher(), p.assistant(), p.artTeacher(), p.formerClassStudent())) {
			// they see the item and are told how many comments there are: none
			JsonNode item = get(p.item(id), audience);
			assertThat(item.get("commentCount").asInt()).as(audience.name()).isZero();
			assertThat(item.toString()).doesNotContain("PRIVATE-");
			assertThat(code(call(HttpMethod.GET, comments(p, id), audience, null, 403))).as(audience.name()).isEqualTo("portfolio.comments_not_allowed");
			assertThat(code(call(HttpMethod.POST, comments(p, id), audience, body("body", "x"), 403))).as(audience.name()).isEqualTo("portfolio.comments_not_allowed");
			assertThat(get(p.item(id), audience).get("capabilities").get("canComment").asBoolean()).isFalse();
		}
		// the galleries carry no comment text either
		assertThat(get(p.api() + "/portfolio/gallery", p.otherStudent()).toString()).doesNotContain("PRIVATE-");
		assertThat(get(p.classApi() + "/portfolio/gallery", p.student2()).toString()).doesNotContain("PRIVATE-");
	}

	@Test
	void restrictedRevokedAndPendingGuardiansLoseCommentsAtOnce() throws Exception {
		Pf p = pf("com5");
		UUID id = approved(p, p.student1(), "guarded", "GUARDIANS");
		Persona g = this.personas.newUser("com5-g");
		UUID link = this.personas.linkGuardian(p.s1(), g.userId(), "ACTIVE", false);
		comment(p, id, g, "first", 201);
		assertThat(get(comments(p, id), g).get("items").size()).isEqualTo(2 - 1);
		this.jdbc.update("update student_guardians set custody_restricted = true where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, comments(p, id), g, null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, comments(p, id), g, body("body", "second"))).isEqualTo(404);
		this.jdbc.update("update student_guardians set custody_restricted = false where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, comments(p, id), g, null)).isEqualTo(200);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, comments(p, id), g, null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, comments(p, id), g, body("body", "third"))).isEqualTo(404);
		this.jdbc.update("update student_guardians set status = 'PENDING', revoked_at = null where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, comments(p, id), g, null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, comments(p, id), g, body("body", "fourth"))).isEqualTo(404);
		// the fixture's own restricted, revoked and pending guardians never had any access
		for (Persona nobody : List.of(p.restricted(), p.revoked(), p.pending())) {
			assertThat(statusOf(HttpMethod.GET, comments(p, id), nobody, null)).as(nobody.name()).isEqualTo(404);
		}
	}

	@Test
	void guardianCommentsCanBeSwitchedOff() throws Exception {
		Pf p = pf("com6");
		UUID id = approved(p, p.student1(), "switch", "GUARDIANS");
		setting(p.schoolId(), "portfolio.comments.guardians_enabled", false);
		assertThat(code(call(HttpMethod.POST, comments(p, id), p.guardian1(), body("body", "x"), 403))).isEqualTo("portfolio.comments_disabled");
		assertThat(get(comments(p, id), p.guardian1()).get("items").size()).isZero();
		assertThat(get(p.item(id), p.guardian1()).get("capabilities").get("canComment").asBoolean()).isFalse();
		comment(p, id, p.lead(), "teacher still may", 201);
		comment(p, id, p.student1(), "student still may", 201);
		setting(p.schoolId(), "portfolio.comments.guardians_enabled", true);
		comment(p, id, p.guardian1(), "and again", 201);
	}

	@Test
	void authorsAppearByRoleLabelOnly() throws Exception {
		Pf p = pf("com7");
		UUID id = approved(p, p.student1(), "labels", "GUARDIANS");
		comment(p, id, p.guardian1(), "parent words", 201);
		comment(p, id, p.student1(), "student words", 201);
		comment(p, id, p.lead(), "teacher words", 201);
		for (Persona reader : List.of(p.lead(), p.student1(), p.guardian1(), p.admin())) {
			JsonNode items = get(comments(p, id), reader).get("items");
			for (JsonNode c : items) {
				String role = c.get("authorRole").asString();
				if (role.equals("TEACHER")) {
					assertThat(c.get("authorName").asString()).isNotBlank();
				}
				else {
					assertThat(c.get("authorName")).as(reader.name() + " sees " + role).isNull();
				}
				assertThat(c.toString()).doesNotContain("authorId", "Abel", p.guardian1().userId().toString(), p.student1().userId().toString());
				assertThat(c.get("mine").asBoolean()).isEqualTo((role.equals("GUARDIAN") && reader == p.guardian1())
						|| (role.equals("STUDENT") && reader == p.student1()) || (role.equals("TEACHER") && reader == p.lead()));
			}
		}
	}

	@Test
	void reviewerHidesWithAReasonAndLeavesATombstone() throws Exception {
		Pf p = pf("com8");
		UUID id = approved(p, p.student1(), "moderated", "GUARDIANS");
		UUID bad = id(comment(p, id, p.guardian1(), "HIDE-ME-CANARY", 201));
		String hide = comments(p, id) + "/" + bad + "/hide";
		assertThat(code(post(hide, p.lead(), body("reason", "  "), 400))).isEqualTo("portfolio.reason_required");
		assertThat(code(post(hide, p.guardian1(), body("reason", "mine"), 403))).isEqualTo("portfolio.review_not_allowed");
		assertThat(code(post(hide, p.student1(), body("reason", "not mine"), 403))).isEqualTo("portfolio.review_not_allowed");
		JsonNode hidden = post(hide, p.lead(), body("reason", "REASON-CANARY off topic"), 200);
		assertThat(hidden.get("status").asString()).isEqualTo("HIDDEN");
		// idempotent
		post(hide, p.co(), body("reason", "again"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'portfolio.comment.hide' and target_id = ?", Integer.class, bad)).isEqualTo(1);
		// the tombstone: no text for the student, the author and the reviewers still read it
		JsonNode asStudent = get(comments(p, id), p.student1()).get("items").get(0);
		assertThat(asStudent.get("status").asString()).isEqualTo("HIDDEN");
		assertThat(asStudent.get("body")).isNull();
		assertThat(get(comments(p, id), p.guardian1()).get("items").get(0).get("body").asString()).isEqualTo("HIDE-ME-CANARY");
		assertThat(get(comments(p, id), p.lead()).get("items").get(0).get("body").asString()).isEqualTo("HIDE-ME-CANARY");
		assertThat(this.jdbc.queryForObject("select hide_reason from portfolio_comments where id = ?", String.class, bad)).contains("off topic");
		// the audit row says that a reason exists, not what it says, and never the comment
		String audit = this.jdbc.queryForObject("select metadata::text from audit_logs where action = 'portfolio.comment.hide' and target_id = ?", String.class, bad);
		assertThat(audit).doesNotContain("CANARY");
		// hidden comments do not count
		assertThat(get(p.item(id), p.student1()).get("commentCount").asInt()).isZero();
	}

	@Test
	void authorDeletesOwnCommentAndTheTextIsErased() throws Exception {
		Pf p = pf("com9");
		UUID id = approved(p, p.student1(), "deleting", "GUARDIANS");
		UUID mine = id(comment(p, id, p.guardian1(), "DELETE-ME-CANARY", 201));
		call(HttpMethod.DELETE, comments(p, id) + "/" + mine, p.guardian1(), null, 204);
		assertThat(this.jdbc.queryForMap("select status, body from portfolio_comments where id = ?", mine)).containsEntry("status", "DELETED").containsEntry("body", null);
		assertThat(get(comments(p, id), p.lead()).get("items").size()).isZero();
		assertThat(statusOf(HttpMethod.DELETE, comments(p, id) + "/" + mine, p.guardian1(), null)).isEqualTo(404);
	}

	@Test
	void otherPeopleCannotHideOrDelete() throws Exception {
		Pf p = pf("com10");
		UUID id = approved(p, p.student1(), "others", "GUARDIANS");
		UUID parent = id(comment(p, id, p.guardian1(), "parent", 201));
		UUID teacher = id(comment(p, id, p.lead(), "teacher", 201));
		assertThat(code(call(HttpMethod.DELETE, comments(p, id) + "/" + parent, p.lead(), null, 403))).isEqualTo("portfolio.comment_delete_not_allowed");
		assertThat(code(call(HttpMethod.DELETE, comments(p, id) + "/" + teacher, p.guardian1(), null, 403))).isEqualTo("portfolio.comment_delete_not_allowed");
		assertThat(code(call(HttpMethod.DELETE, comments(p, id) + "/" + parent, p.student1(), null, 403))).isEqualTo("portfolio.comment_delete_not_allowed");
		assertThat(statusOf(HttpMethod.DELETE, comments(p, id) + "/" + parent, p.student2(), null)).isIn(403, 404);
		assertThat(statusOf(HttpMethod.POST, comments(p, id) + "/" + parent + "/hide", p.otherTeacher(), body("reason", "x"))).isEqualTo(404);
		// a comment of another item is not found
		UUID other = approved(p, p.student1(), "another item", "GUARDIANS");
		assertThat(code(call(HttpMethod.DELETE, comments(p, other) + "/" + parent, p.guardian1(), null, 404))).isEqualTo("portfolio.comment_not_found");
	}

	@Test
	void commentsAreRateLimited() throws Exception {
		Pf p = pf("com11");
		UUID id = approved(p, p.student1(), "busy", "GUARDIANS");
		int last = 0;
		for (int i = 0; i < 31 && last != 429; i++) {
			last = statusOf(HttpMethod.POST, comments(p, id), p.guardian1(), body("body", "c" + i));
		}
		assertThat(last).isEqualTo(429);
		assertThat(statusOf(HttpMethod.POST, comments(p, id), p.student1(), body("body", "still fine"))).isEqualTo(201);
	}

	@Test
	void commentTextIsCleanedAndBounded() throws Exception {
		Pf p = pf("com12");
		UUID id = approved(p, p.student1(), "clean", "GUARDIANS");
		assertThat(comment(p, id, p.guardian1(), " Well‮ done​\u0000! \r\n ", 201).get("body").asString()).isEqualTo("Well done!");
		assertThat(code(call(HttpMethod.POST, comments(p, id), p.guardian1(), body("body", "  ​ "), 400))).isEqualTo("portfolio.comment_required");
		assertThat(code(call(HttpMethod.POST, comments(p, id), p.guardian1(), body("body", "c".repeat(501)), 400))).isEqualTo("portfolio.comment_too_long");
		assertThat(comment(p, id, p.guardian1(), "c".repeat(500), 201).get("body").asString()).hasSize(500);
		assertThat(comment(p, id, p.guardian1(), "<script>alert(1)</script>", 201).get("body").asString()).isEqualTo("<script>alert(1)</script>");
	}

}
