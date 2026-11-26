package com.nemaz.school.portfolio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P1.8: a teacher captures an item for a student of a class they teach. */
@IntegrationTest
class PortfolioQuickCaptureTest extends PortfolioTestSupport {

	private static String capture(Pf p) {
		return p.classApi() + "/portfolio/quick-capture";
	}

	private Map<String, Object> text(Pf p, String title, Object... extra) {
		Map<String, Object> map = body("studentId", p.s1(), "itemType", "TEXT", "title", title, "textContent", "text of " + title);
		for (int i = 0; i < extra.length; i += 2) {
			map.put((String) extra[i], extra[i + 1]);
		}
		return map;
	}

	private Map<String, Object> photoOf(Pf p, Persona teacher, String title, Object... extra) throws Exception {
		Map<String, Object> map = body("studentId", p.s1(), "itemType", "PHOTO", "title", title, "mediaIds", List.of(photo(teacher, p.schoolId())));
		for (int i = 0; i < extra.length; i += 2) {
			map.put((String) extra[i], extra[i + 1]);
		}
		return map;
	}

	@Test
	void leadCoAndSubjectTeachersMayCapture() throws Exception {
		Pf p = pf("qc1");
		for (Persona teacher : List.of(p.lead(), p.co(), p.mathsTeacher(), p.artTeacher())) {
			JsonNode item = post(capture(p), teacher, text(p, "by " + teacher.name()), 201);
			assertThat(item.get("status").asString()).isEqualTo("APPROVED");
			assertThat(item.get("createdByRole").asString()).isEqualTo("TEACHER");
			assertThat(item.get("classId").asString()).isEqualTo(p.classId().toString());
			assertThat(this.jdbc.queryForObject("select created_by from portfolio_items where id = ?::uuid", UUID.class, item.get("id").asString()))
				.isEqualTo(teacher.userId());
		}
	}

	@Test
	void assistantsOnlyWhenTheSettingIsOn() throws Exception {
		Pf p = pf("qc2");
		assertThat(code(post(capture(p), p.assistant(), text(p, "no"), 403))).isEqualTo("portfolio.capture_not_allowed");
		setting(p.schoolId(), "portfolio.assistants_can_capture", true);
		post(capture(p), p.assistant(), text(p, "yes"), 201);
		setting(p.schoolId(), "portfolio.assistants_can_capture", false);
		assertThat(statusOf(HttpMethod.POST, capture(p), p.assistant(), text(p, "no again"))).isEqualTo(403);
	}

	@Test
	void teacherOfAnotherClassIsRefused() throws Exception {
		Pf p = pf("qc3");
		assertThat(code(post(capture(p), p.otherTeacher(), text(p, "x"), 404))).isEqualTo("class.not_found");
		// a teacher who captures in their own class cannot name a student of another class
		JsonNode other = post(p.api() + "/classes/" + p.otherClassId() + "/portfolio/quick-capture", p.otherTeacher(), text(p, "x"), 404);
		assertThat(code(other)).isEqualTo("student.not_found");
	}

	@Test
	void managersStudentsAndGuardiansCannotCapture() throws Exception {
		Pf p = pf("qc4");
		for (Persona who : List.of(p.admin(), p.student1(), p.student2(), p.guardian1(), p.guardian2())) {
			assertThat(code(post(capture(p), who, text(p, "x"), 403))).as(who.name()).isEqualTo("portfolio.capture_not_allowed");
		}
		for (Persona who : List.of(p.adminB(), p.studentB(), p.restricted(), p.revoked(), p.withdrawnStudent())) {
			assertThat(statusOf(HttpMethod.POST, capture(p), who, text(p, "x"))).as(who.name()).isEqualTo(404);
		}
		assertThat(statusOf(HttpMethod.POST, capture(p), null, text(p, "x"))).isEqualTo(401);
	}

	@Test
	void studentMustBeActiveInThatClass() throws Exception {
		Pf p = pf("qc5");
		UUID former = this.jdbc.queryForObject("select student_id from class_students where status = 'FORMER' and class_id = ?", UUID.class, p.classId());
		for (UUID not : List.of(p.o1(), former, newId())) {
			assertThat(code(post(capture(p), p.lead(), text(p, "x", "studentId", not), 404))).as(not.toString()).isEqualTo("student.not_found");
		}
		// an archived class takes no captures
		this.jdbc.update("update classes set status = 'ARCHIVED', archived_at = now() where id = ?", p.classId());
		assertThat(code(post(capture(p), p.lead(), text(p, "x"), 409))).isEqualTo("class.archived");
	}

	@Test
	void capturedItemIsApprovedAndAuditedWithoutText() throws Exception {
		Pf p = pf("qc6");
		JsonNode item = post(capture(p), p.lead(), text(p, "CAPTURE-TITLE-CANARY", "reflection", "CAPTURE-REFLECTION-CANARY"), 201);
		UUID id = id(item);
		assertThat(item.get("status").asString()).isEqualTo("APPROVED");
		assertThat(this.jdbc.queryForObject("select reviewed_by from portfolio_items where id = ?", UUID.class, id)).isEqualTo(p.lead().userId());
		List<Map<String, Object>> history = this.jdbc.queryForList("select action, from_status, to_status, actor_id from portfolio_approvals where item_id = ?", id);
		assertThat(history).hasSize(1);
		assertThat(history.getFirst().get("action")).isEqualTo("CAPTURED");
		assertThat(history.getFirst().get("to_status")).isEqualTo("APPROVED");
		assertThat(history.getFirst().get("actor_id")).isEqualTo(p.lead().userId());
		List<String> audit = this.jdbc.queryForList(
				"select action || ' ' || coalesce(metadata::text, '') from audit_logs where target_id = ? and action like 'portfolio.%'", String.class, id);
		assertThat(audit).anySatisfy((row) -> assertThat(row).startsWith("portfolio.item.quick_capture"));
		assertThat(String.join("|", audit)).doesNotContain("CANARY");
		// the student sees it as their own item in the list of their own
		assertThat(ids(get(p.api() + "/portfolio/mine", p.student1()))).contains(id.toString());
		assertThat(ids(get(p.api() + "/portfolio/mine", p.lead()))).contains(id.toString());
	}

	@Test
	void capturedItemFollowsTheVisibilityRules() throws Exception {
		Pf p = pf("qc7");
		UUID family = id(post(capture(p), p.lead(), text(p, "family"), 201));
		assertThat(statusOf(HttpMethod.GET, p.item(family), p.guardian1(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, p.item(family), p.student2(), null)).isEqualTo(404);
		UUID privateItem = id(post(capture(p), p.lead(), text(p, "private", "visibility", "STUDENT_TEACHER"), 201));
		assertThat(statusOf(HttpMethod.GET, p.item(privateItem), p.guardian1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(privateItem), p.student1(), null)).isEqualTo(200);
		cap(p, "CLASS");
		UUID wide = id(post(capture(p), p.lead(), text(p, "class wide", "visibility", "CLASS"), 201));
		assertThat(statusOf(HttpMethod.GET, p.item(wide), p.student2(), null)).isEqualTo(200);
		// the student cannot delete or edit what a teacher captured; a reviewer can delete it
		assertThat(statusOf(HttpMethod.PATCH, p.item(family), p.student1(), body("title", "mine now"))).isEqualTo(403);
		assertThat(code(call(HttpMethod.DELETE, p.item(family), p.student1(), null, 403))).isEqualTo("portfolio.delete_not_allowed");
		call(HttpMethod.DELETE, p.item(family), p.lead(), null, 204);
		// a captured photo for the family only needs no consent when the teacher says no other child appears
		JsonNode photo = post(capture(p), p.lead(), photoOf(p, p.lead(), "alone", "showsOtherChildren", false), 201);
		assertThat(get(p.item(id(photo)), p.guardian1()).get("media").size()).isEqualTo(1);
		assertThat(mediaUrlStatus(p.guardian1(), mediaOf(photo))).isEqualTo(200);
		assertThat(mediaUrlStatus(p.student1(), mediaOf(photo))).isEqualTo(200);
	}

	@Test
	void showsOtherChildrenDefaultsToTrue() throws Exception {
		Pf p = pf("qc8");
		// omitted: treated as showing others, so the student's consent, then a confirmation or tags are needed
		JsonNode missing = post(capture(p), p.lead(), photoOf(p, p.lead(), "unsaid"), 422);
		assertThat(code(missing)).isEqualTo("portfolio.media_consent_missing");
		grant(p.guardian1(), p.s1());
		assertThat(code(post(capture(p), p.lead(), photoOf(p, p.lead(), "unsaid"), 422))).isEqualTo("portfolio.media_confirmation_required");
		JsonNode confirmed = post(capture(p), p.lead(), photoOf(p, p.lead(), "unsaid", "confirmNoUnconsentedStudents", true), 201);
		assertThat(confirmed.get("showsOtherChildren").asBoolean()).isTrue();
		assertThat(confirmed.get("mediaConfirmed").asBoolean()).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'portfolio.item.media_confirmed' and target_id = ?::uuid",
				Integer.class, confirmed.get("id").asString())).isEqualTo(1);
		// explicit false: nothing is asked
		withdraw(p.guardian1(), p.s1());
		post(capture(p), p.lead(), photoOf(p, p.lead(), "explicit", "showsOtherChildren", false), 201);
		// a text-only capture asks nothing at all
		post(capture(p), p.lead(), text(p, "words"), 201);
	}

	@Test
	void groupPhotoNeedsTagsOrAConfirmation() throws Exception {
		Pf p = pf("qc9");
		grant(p.guardian1(), p.s1());
		List<UUID> group = List.of(p.s2(), p.s3());
		JsonNode refused = post(capture(p), p.lead(), photoOf(p, p.lead(), "group", "taggedStudentIds", group), 422);
		assertThat(code(refused)).isEqualTo("portfolio.media_consent_missing");
		List<String> lacking = new ArrayList<>();
		refused.get("students").forEach((s) -> lacking.add(s.get("studentId").asString()));
		assertThat(lacking).containsExactlyInAnyOrder(p.s2().toString(), p.s3().toString());
		grant(p.guardian2(), p.s2());
		// Chala has no guardian at all: no consent can exist, so the tag must go
		JsonNode still = post(capture(p), p.lead(), photoOf(p, p.lead(), "group", "taggedStudentIds", group), 422);
		assertThat(still.get("students").size()).isEqualTo(1);
		assertThat(still.get("students").get(0).get("studentId").asString()).isEqualTo(p.s3().toString());
		JsonNode created = post(capture(p), p.lead(), photoOf(p, p.lead(), "group", "taggedStudentIds", List.of(p.s2())), 201);
		assertThat(created.get("taggedStudentCount").asInt()).isEqualTo(1);
		assertThat(created.get("showsOtherChildren").asBoolean()).isTrue();
		// the tag is named to the reviewing teacher, not to the family
		assertThat(created.get("taggedStudents").get(0).get("displayName").asString()).isEqualTo("Bethlehem");
		assertThat(get(p.item(id(created)), p.guardian1()).get("taggedStudents")).isNull();
		// a tag of somebody who is not a student of the school is refused
		assertThat(code(post(capture(p), p.lead(), photoOf(p, p.lead(), "x", "taggedStudentIds", List.of(newId())), 400))).isEqualTo("portfolio.tag_invalid");
	}

}
