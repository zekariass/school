package com.nemaz.school.engagement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.DomainEventRecorder;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Who may award, to whom, in which category, and what an award writes. */
@IntegrationTest
class PointAwardApiTest extends PointsTestSupport {

	@Autowired
	DomainEventRecorder recorder;

	@Test
	void aTeacherAwardsOneStudentSeveralAndTheWholeClassWithSharedBatches() throws Exception {
		Setup s = setup("Award school");
		UUID kindness = category(s, "kindness");

		JsonNode single = award(s, s.lead(), kindness, List.of(s.s1()), 201);
		assertThat(absent(single, "batchId")).isTrue();
		assertThat(single.get("replayed").asBoolean()).isFalse();
		assertThat(single.get("undoableUntil").asString()).isNotBlank();
		JsonNode first = single.get("transactions").get(0);
		assertThat(first.get("points").asInt()).isEqualTo(1);
		assertThat(first.get("context").asString()).isEqualTo("CLASS");
		assertThat(first.get("type").asString()).isEqualTo("AWARD");
		assertThat(uuid(first, "classId")).isEqualTo(s.classId());

		JsonNode several = award(s, s.assistant(), kindness, List.of(s.s1(), s.s2()), 201);
		assertThat(several.get("transactions")).hasSize(2);
		UUID batch = uuid(several, "batchId");
		assertThat(ledgerRows("award_batch_id = ?", batch)).isEqualTo(2);

		JsonNode whole = call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
				Map.of("categoryId", category(s, "persistence"), "wholeClass", true, "note", "Great week"), 201);
		assertThat(whole.get("transactions")).hasSize(2);
		assertThat(whole.get("transactions").get(0).get("points").asInt()).isEqualTo(2);
		assertThat(absent(whole, "batchId")).isFalse();
		awaitClassTotal(s.classId(), s.s1(), 1 + 1 + 2);
		awaitClassTotal(s.classId(), s.s2(), 1 + 2);
	}

	@Test
	void pointsOverrideIsBoundedAndTheSignComesFromTheCategory() throws Exception {
		Setup s = setup("Bounds school");
		JsonNode ok = call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
				Map.of("categoryId", category(s, "kindness"), "studentIds", List.of(s.s1()), "points", 5), 201);
		assertThat(ok.get("transactions").get(0).get("points").asInt()).isEqualTo(5);
		call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
				Map.of("categoryId", category(s, "kindness"), "studentIds", List.of(s.s1()), "points", 11), 400);
		call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
				Map.of("categoryId", category(s, "kindness"), "studentIds", List.of(s.s1()), "points", 0), 400);
		JsonNode needsWork = award(s, s.lead(), category(s, "disruptive"), List.of(s.s1()), 201);
		assertThat(needsWork.get("transactions").get(0).get("points").asInt()).isEqualTo(-1);
		assertThat(needsWork.get("transactions").get(0).get("polarity").asString()).isEqualTo("NEEDS_WORK");
		awaitClassTotal(s.classId(), s.s1(), 4);
	}

	@Test
	void theSameClientOperationNeverAwardsTwice() throws Exception {
		Setup s = setup("Replay school");
		UUID kindness = category(s, "kindness");
		UUID op = Ids.newId();
		JsonNode first = awardWithOp(s, s.lead(), kindness, List.of(s.s1(), s.s2()), op, 201);
		JsonNode again = awardWithOp(s, s.lead(), kindness, List.of(s.s1(), s.s2()), op, 200);
		assertThat(again.get("replayed").asBoolean()).isTrue();
		assertThat(again.get("transactions").findValuesAsString("id"))
			.containsExactlyInAnyOrderElementsOf(first.get("transactions").findValuesAsString("id"));
		assertThat(ledgerRows("client_op_id = ?", op)).isEqualTo(2);
		// another awarder may reuse the id: operation ids belong to the awarder
		JsonNode other = awardWithOp(s, s.assistant(), kindness, List.of(s.s1()), op, 201);
		assertThat(other.get("replayed").asBoolean()).isFalse();
		assertThat(ledgerRows("client_op_id = ?", op)).isEqualTo(3);
		awaitClassTotal(s.classId(), s.s1(), 2);
	}

	@Test
	void parallelCallsWithTheSameClientOperationWriteOnce() throws Exception {
		Setup s = setup("Parallel op school");
		UUID kindness = category(s, "kindness");
		UUID op = Ids.newId();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				Callable<Integer> call = () -> perform(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
						Map.of("categoryId", kindness, "studentIds", List.of(s.s1()), "clientOpId", op))
					.andReturn()
					.getResponse()
					.getStatus();
				results.add(pool.submit(call));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : results) {
				statuses.add(f.get());
			}
			assertThat(statuses).containsOnly(200, 201);
			assertThat(statuses.stream().filter((x) -> x == 201).count()).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(ledgerRows("client_op_id = ?", op)).isEqualTo(1);
		awaitClassTotal(s.classId(), s.s1(), 1);
	}

	@Test
	void idempotencyKeyHeaderRepliesTheSameResponse() throws Exception {
		Setup s = setup("Header school");
		UUID kindness = category(s, "kindness");
		String key = "key-" + Ids.newId();
		MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(s.classApi() + "/points/awards").with(s.lead().bearer())
			.header("Idempotency-Key", key)
			.contentType("application/json")
			.content(this.json.writeValueAsString(Map.of("categoryId", kindness, "studentIds", List.of(s.s1()))));
		assertThat(this.mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(201);
		var replay = this.mvc.perform(request).andReturn().getResponse();
		assertThat(replay.getStatus()).isEqualTo(201);
		assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
		assertThat(ledgerRows("student_id = ?", s.s1())).isEqualTo(1);
	}

	@Test
	void onlyActiveTeachersOfTheClassAndTheStudentsInItCanBeAwarded() throws Exception {
		Setup s = setup("Rules school");
		UUID kindness = category(s, "kindness");
		// students and guardians see the class but never award
		assertThat(errorCode(award(s, s.student1(), kindness, List.of(s.s2()), 403))).isEqualTo("class.access_denied");
		assertThat(errorCode(award(s, s.guardian(), kindness, List.of(s.s1()), 403))).isEqualTo("class.access_denied");
		// a teacher of another class and another school's administrator do not see the class at all
		assertThat(errorCode(award(s, s.otherTeacher(), kindness, List.of(s.s1()), 404))).isEqualTo("class.not_found");
		Setup elsewhere = setup("Elsewhere school");
		assertThat(errorCode(award(s, elsewhere.admin(), kindness, List.of(s.s1()), 404))).isEqualTo("class.not_found");
		// a student of another class of the same school, a student who left, and an unknown id
		UUID outsider = this.personas.newStudent(s.schoolId(), "Outsider");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), outsider);
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(outsider), 409))).isEqualTo("points.student_not_in_class");
		UUID former = this.personas.newStudent(s.schoolId(), "Former");
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", Ids.newId(), s.classId(), s.schoolId(), former);
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(former), 409))).isEqualTo("points.student_not_in_class");
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(s.s1(), outsider), 409))).isEqualTo("points.student_not_in_class");
		assertThat(ledgerRows("school_id = ?", s.schoolId())).isZero();
		// a teacher who left the class cannot award any more
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ?", s.assistant().userId());
		assertThat(errorCode(award(s, s.assistant(), kindness, List.of(s.s1()), 404))).isEqualTo("class.not_found");
		// no students chosen, and an archived class
		call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(), Map.of("categoryId", kindness), 409);
		this.jdbc.update("update classes set status = 'ARCHIVED', archived_at = now() where id = ?", s.classId());
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(s.s1()), 409))).isEqualTo("class.archived");
	}

	@Test
	void categoriesMustBeUsableInTheClass() throws Exception {
		Setup s = setup("Category rules school");
		UUID kindness = category(s, "kindness");
		// a class category of another class, a home category, an unknown one, a deactivated one
		UUID classOnly = uuid(call(HttpMethod.POST, s.api() + "/classes/" + s.otherClassId() + "/point-categories", s.otherTeacher(),
				Map.of("names", Map.of("en", "Other class skill")), 201), "id");
		assertThat(errorCode(award(s, s.lead(), classOnly, List.of(s.s1()), 404))).isEqualTo("points.category_not_found");
		assertThat(errorCode(award(s, s.lead(), category(s, "home_homework"), List.of(s.s1()), 409)))
			.isEqualTo("points.category_is_home");
		assertThat(errorCode(award(s, s.lead(), Ids.newId(), List.of(s.s1()), 404))).isEqualTo("points.category_not_found");
		call(HttpMethod.PATCH, s.api() + "/point-categories/" + kindness, s.admin(), Map.of("active", false), 200);
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(s.s1()), 409))).isEqualTo("points.category_inactive");
		// a class's own category works in that class
		UUID own = uuid(call(HttpMethod.POST, s.classApi() + "/point-categories", s.lead(),
				Map.of("names", Map.of("en", "Tidy desk"), "defaultPoints", 3), 201), "id");
		assertThat(award(s, s.lead(), own, List.of(s.s1()), 201).get("transactions").get(0).get("points").asInt()).isEqualTo(3);
	}

	@Test
	void needsWorkPointsFollowTheSchoolSetting() throws Exception {
		Setup s = setup("Needs work school");
		UUID disruptive = category(s, "disruptive");
		setting(s.schoolId(), "engagement.needs_work.enabled", false);
		assertThat(errorCode(award(s, s.lead(), disruptive, List.of(s.s1()), 409))).isEqualTo("points.needs_work_disabled");
		// and the award palette no longer offers them
		List<String> offered = get(s.api() + "/point-categories?classId=" + s.classId(), s.lead()).findValuesAsString("templateCode");
		assertThat(offered).contains("kindness").doesNotContain("disruptive", "unkind_words");
		setting(s.schoolId(), "engagement.needs_work.enabled", true);
		award(s, s.lead(), disruptive, List.of(s.s1()), 201);
	}

	@Test
	void guardiansAwardOnlyHomePointsThroughAnActiveLinkWithTheFlag() throws Exception {
		Setup s = setup("Home school");
		UUID homework = category(s, "home_homework");
		UUID kindness = category(s, "kindness");

		JsonNode categories = get(s.api() + "/students/" + s.s1() + "/home-point-categories", s.guardian());
		assertThat(categories.findValuesAsString("templateCode")).containsExactlyInAnyOrder("home_homework", "home_reading",
				"home_helping", "home_manners");

		JsonNode home = awardHome(s, s.guardian(), s.s1(), homework, 201);
		JsonNode tx = home.get("transactions").get(0);
		assertThat(tx.get("context").asString()).isEqualTo("HOME");
		assertThat(absent(tx, "classId")).isTrue();
		assertThat(this.jdbc.queryForMap("select awarder_role, awarded_by, class_id from point_transactions where id = ?::uuid",
				tx.get("id").asString())).containsEntry("awarder_role", "GUARDIAN")
			.containsEntry("awarded_by", s.guardian().userId())
			.containsEntry("class_id", null);
		// a class category cannot be used at home
		assertThat(errorCode(awardHome(s, s.guardian(), s.s1(), kindness, 409))).isEqualTo("points.category_not_home");
		// home points are not class points
		assertThat(this.jdbc.queryForObject("select count(*) from point_balances where student_id = ? and context = 'CLASS'",
				Integer.class, s.s1())).isZero();
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select positive_total from point_balances where student_id = ? and context = 'HOME'", Integer.class, s.s1()))
			.containsExactly(1));

		// the DB itself refuses a teacher-awarded HOME row and a guardian-awarded class row
		UUID year = this.personas.currentYear(s.schoolId());
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into point_transactions (school_id, student_id, context, academic_year_id, category_id, polarity, points,
				                                awarder_role, awarded_by)
				values (?, ?, 'HOME', ?, ?, 'POSITIVE', 1, 'TEACHER', ?)""", s.schoolId(), s.s1(), year, homework, s.leadId()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into point_transactions (school_id, student_id, context, class_id, academic_year_id, category_id, polarity,
				                                points, awarder_role, awarded_by)
				values (?, ?, 'CLASS', ?, ?, ?, 'POSITIVE', 1, 'GUARDIAN', ?)""", s.schoolId(), s.s1(), s.classId(), year,
				kindness, s.guardian().userId())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void guardianAwardRulesDenyEveryoneElse() throws Exception {
		Setup s = setup("Home deny school");
		UUID homework = category(s, "home_homework");
		// a teacher, the student, an unrelated adult and the guardian of another child: no link, no child
		assertThat(errorCode(awardHome(s, s.lead(), s.s1(), homework, 404))).isEqualTo("student.not_found");
		assertThat(errorCode(awardHome(s, s.student1(), s.s1(), homework, 404))).isEqualTo("student.not_found");
		assertThat(errorCode(awardHome(s, s.admin(), s.s1(), homework, 404))).isEqualTo("student.not_found");
		Persona other = this.personas.newUser("other-parent");
		this.personas.linkGuardian(s.s2(), other.userId(), "ACTIVE", false);
		assertThat(errorCode(awardHome(s, other, s.s1(), homework, 404))).isEqualTo("student.not_found");
		call(HttpMethod.GET, s.api() + "/students/" + s.s1() + "/home-point-categories", other, null, 404);
		// pending, revoked and custody-restricted links grant nothing
		for (String status : List.of("PENDING", "REVOKED")) {
			Persona p = this.personas.newUser("parent-" + status);
			this.personas.linkGuardian(s.s1(), p.userId(), status, false);
			awardHome(s, p, s.s1(), homework, 404);
		}
		Persona restricted = this.personas.newUser("restricted-parent");
		this.personas.linkGuardian(s.s1(), restricted.userId(), "ACTIVE", true);
		awardHome(s, restricted, s.s1(), homework, 404);
		// an active link without can_award_points is 403
		this.jdbc.update("update student_guardians set can_award_points = false where guardian_id = ?", s.guardian().userId());
		assertThat(errorCode(awardHome(s, s.guardian(), s.s1(), homework, 403))).isEqualTo("points.award_not_allowed");
		call(HttpMethod.GET, s.api() + "/students/" + s.s1() + "/home-point-categories", s.guardian(), null, 403);
		assertThat(ledgerRows("school_id = ?", s.schoolId())).isZero();
	}

	@Test
	void theNoteIsNeverLoggedAuditedOrPutInEventsOrNotifications() throws Exception {
		Setup s = setup("Note school");
		String note = "secret-note-" + Ids.newId();
		call(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
				Map.of("categoryId", category(s, "kindness"), "studentIds", List.of(s.s1()), "note", note), 201);
		assertThat(this.jdbc.queryForObject("select count(*) from point_transactions where reason = ?", Integer.class, note))
			.isEqualTo(1);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'POINT_AWARDED' and user_id = ?", Integer.class,
				s.guardian().userId())).isEqualTo(1));
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where cast(metadata as text) like ?", Integer.class,
				"%" + note + "%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?",
				Integer.class, "%" + note + "%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where title like ? or body like ? or cast(data as text) like ?",
				Integer.class, "%" + note + "%", "%" + note + "%", "%" + note + "%")).isZero();
		assertThat(this.recorder.of(PointTransactionRecorded.class)).anyMatch((e) -> e.studentId().equals(s.s1()));
	}

	@Test
	void anAwardNotifiesTheChildAndTheirGuardiansInTheirLanguage() throws Exception {
		Setup s = setup("Notify school");
		this.jdbc.update("update users set preferred_language_code = 'am' where id = ?", s.guardian().userId());
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select user_id from notifications where type_code = 'POINT_AWARDED' and user_id in (?, ?)", UUID.class,
				s.s1(), s.guardian().userId())).containsExactlyInAnyOrder(s.s1(), s.guardian().userId()));
		String body = this.jdbc.queryForObject("select body from notifications where type_code = 'POINT_AWARDED' and user_id = ?",
				String.class, s.guardian().userId());
		assertThat(body).contains("ነጥብ").contains("ደግ ቃላት");
		// needs-work points are not announced to anybody, and the classmate's guardian hears nothing
		award(s, s.lead(), category(s, "disruptive"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "kindness"), List.of(s.s2()), 201);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'POINT_AWARDED' and user_id = ?", Integer.class, s.s2()))
			.isEqualTo(1));
		assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'POINT_AWARDED' and user_id = ?", Integer.class,
				s.guardian().userId())).isEqualTo(1);
	}

	@Test
	void aSchoolAdministratorAwardsSchoolWidePointsInTheSchoolContext() throws Exception {
		Setup s = setup("School wide school");
		UUID kindness = category(s, "kindness");
		JsonNode result = call(HttpMethod.POST, s.api() + "/points/awards", s.admin(),
				Map.of("categoryId", kindness, "studentIds", List.of(s.s1(), s.s2())), 201);
		assertThat(result.get("transactions").get(0).get("context").asString()).isEqualTo("SCHOOL");
		assertThat(this.jdbc.queryForList("select distinct awarder_role from point_transactions where school_id = ?",
				String.class, s.schoolId())).containsExactly("SCHOOL_ADMIN");
		// teachers cannot award school-wide, and the students must belong to the school
		call(HttpMethod.POST, s.api() + "/points/awards", s.lead(), Map.of("categoryId", kindness, "studentIds", List.of(s.s1())),
				403);
		UUID foreigner = this.personas.newStudent(this.personas.newSchool("Foreign school"), "Foreigner");
		assertThat(errorCode(call(HttpMethod.POST, s.api() + "/points/awards", s.admin(),
				Map.of("categoryId", kindness, "studentIds", List.of(foreigner)), 409))).isEqualTo("points.student_not_in_school");
	}

	@Test
	void theFeatureFlagTurnsPointsOffForASchool() throws Exception {
		Setup s = setup("Feature school");
		UUID kindness = category(s, "kindness");
		call(HttpMethod.PUT, "/api/v1/platform/feature-overrides", this.personas.platformAdmin(),
				Map.of("featureKey", "points", "scope", Map.of("type", "SCHOOL", "schoolId", s.schoolId()), "enabled", false), 200);
		assertThat(errorCode(award(s, s.lead(), kindness, List.of(s.s1()), 403))).isEqualTo("points.feature_disabled");
		call(HttpMethod.DELETE, "/api/v1/platform/feature-overrides?featureKey=points&scopeType=SCHOOL&schoolId=" + s.schoolId(),
				this.personas.platformAdmin(), null, 204);
		award(s, s.lead(), kindness, List.of(s.s1()), 201);
	}

}
