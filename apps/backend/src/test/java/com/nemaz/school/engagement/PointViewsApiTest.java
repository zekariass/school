package com.nemaz.school.engagement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** What each reader sees of points: class overview, leaderboard, a student's sections and timelines. */
@IntegrationTest
class PointViewsApiTest extends PointsTestSupport {

	@Test
	void theClassOverviewListsEveryStudentForTheClassTeachersOnly() throws Exception {
		Setup s = setup("Overview school");
		UUID kindness = category(s, "kindness");
		award(s, s.lead(), kindness, List.of(s.s1(), s.s2()), 201);
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "disruptive"), List.of(s.s2()), 201);
		awaitClassTotal(s.classId(), s.s1(), 3);
		awaitClassTotal(s.classId(), s.s2(), 0);

		String url = s.classApi() + "/points";
		for (Persona teacher : List.of(s.lead(), s.assistant(), s.admin())) {
			JsonNode overview = get(url, teacher);
			assertThat(overview.get("students")).hasSize(2);
			JsonNode abel = overview.get("students").get(0);
			assertThat(abel.get("displayName").asString()).isEqualTo("Abel");
			assertThat(abel.get("total").asInt()).isEqualTo(3);
			assertThat(abel.get("accessLevel").asString()).isEqualTo("BASIC");
			assertThat(overview.get("students").get(1).get("needsWorkTotal").asInt()).isEqualTo(-1);
			assertThat(overview.get("settings").get("needsWorkEnabled").asBoolean()).isTrue();
			assertThat(overview.get("settings").get("leaderboardEnabled").asBoolean()).isFalse();
			assertThat(overview.get("settings").get("undoWindowSeconds").asInt()).isEqualTo(120);
			// student payloads keep the BASIC split: no date of birth, gender or username
			assertThat(overview.toString()).doesNotContain("dateOfBirth", "gender", "username");
		}
		// students and guardians see the class but not its points; teachers of other classes and strangers see nothing
		assertThat(errorCode(call(HttpMethod.GET, url, s.student1(), null, 403))).isEqualTo("class.access_denied");
		call(HttpMethod.GET, url, s.guardian(), null, 403);
		assertThat(errorCode(call(HttpMethod.GET, url, s.otherTeacher(), null, 404))).isEqualTo("class.not_found");
		call(HttpMethod.GET, url, setup("Overview elsewhere").admin(), null, 404);
	}

	@Test
	void theLeaderboardIsOffByDefaultAndNeverShownToGuardians() throws Exception {
		Setup s = setup("Leaderboard school");
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "kindness"), List.of(s.s2()), 201);
		award(s, s.lead(), category(s, "disruptive"), List.of(s.s2()), 201);
		awaitClassTotal(s.classId(), s.s1(), 2);
		awaitClassTotal(s.classId(), s.s2(), 0);
		String url = s.classApi() + "/leaderboard";

		// a child never sees other children's points unless the school turned rankings on
		for (Persona p : List.of(s.student1(), s.student2(), s.lead(), s.admin())) {
			assertThat(errorCode(call(HttpMethod.GET, url, p, null, 403))).isEqualTo("points.leaderboard_disabled");
		}
		setting(s.schoolId(), "engagement.leaderboard.enabled", true);
		JsonNode board = get(url, s.student2());
		// only positive totals are ranked: the child with a zero total is not listed
		assertThat(board.get("rows")).hasSize(1);
		assertThat(board.get("rows").get(0).get("displayName").asString()).isEqualTo("Abel");
		assertThat(board.get("rows").get(0).get("rank").asInt()).isEqualTo(1);
		assertThat(board.toString()).doesNotContain("dateOfBirth", "needsWork");
		get(url, s.lead());
		// guardians are not on the roster, strangers and other classes see nothing
		assertThat(errorCode(call(HttpMethod.GET, url, s.guardian(), null, 404))).isEqualTo("class.not_found");
		call(HttpMethod.GET, url, s.otherTeacher(), null, 404);
	}

	@Test
	void ranksTieAndTheLeaderboardStopsAtTen() throws Exception {
		Setup s = setup("Ties school");
		setting(s.schoolId(), "engagement.leaderboard.enabled", true);
		UUID kindness = category(s, "kindness");
		List<UUID> students = new java.util.ArrayList<>(List.of(s.s1(), s.s2()));
		for (int i = 0; i < 10; i++) {
			UUID extra = this.personas.newStudent(s.schoolId(), "Extra" + i);
			this.personas.addStudentToClass(s.schoolId(), s.classId(), extra);
			students.add(extra);
		}
		award(s, s.lead(), kindness, students, 201);
		award(s, s.lead(), kindness, List.of(s.s1()), 201);
		await().untilAsserted(() -> assertThat(get(s.classApi() + "/leaderboard", s.lead()).get("rows")).hasSize(10));
		JsonNode rows = get(s.classApi() + "/leaderboard", s.lead()).get("rows");
		assertThat(rows.get(0).get("rank").asInt()).isEqualTo(1);
		assertThat(rows.get(0).get("total").asInt()).isEqualTo(2);
		assertThat(rows.get(1).get("rank").asInt()).isEqualTo(2);
		assertThat(rows.get(9).get("rank").asInt()).isEqualTo(2);
	}

	@Test
	void aStudentsPointsHaveSeparateSchoolAndHomeSectionsForTheFamilyOnly() throws Exception {
		Setup s = setup("Sections school");
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/home-points", s.guardian(),
				Map.of("categoryId", category(s, "home_reading"), "points", 4, "note", "Read a whole chapter"), 201);
		String url = s.api() + "/students/" + s.s1() + "/points";

		for (Persona family : List.of(s.student1(), s.guardian())) {
			JsonNode points = get(url, family);
			assertThat(points.get("school").get("total").asInt()).isEqualTo(3);
			assertThat(points.get("school").get("categories")).hasSize(2);
			assertThat(points.get("home").get("total").asInt()).isEqualTo(4);
			assertThat(points.get("home").get("categories").get(0).get("name").asString()).isEqualTo("Reading at home");
			assertThat(points.get("accessLevel").asString()).isEqualTo("BASIC");
			JsonNode home = get(url + "/transactions?section=HOME", family);
			assertThat(home.get("items")).hasSize(1);
			assertThat(home.get("items").get(0).get("note").asString()).isEqualTo("Read a whole chapter");
			assertThat(home.get("items").get(0).get("awarderRole").asString()).isEqualTo("GUARDIAN");
			JsonNode school = get(url + "/transactions?section=SCHOOL", family);
			assertThat(school.get("items")).hasSize(2);
			assertThat(school.get("items").findValuesAsString("context")).containsOnly("CLASS");
		}
		// teachers and administrators see the school section, never the home one
		for (Persona staff : List.of(s.lead(), s.assistant(), s.admin())) {
			JsonNode points = get(url, staff);
			assertThat(points.get("school").get("total").asInt()).isEqualTo(3);
			assertThat(absent(points, "home")).isTrue();
			assertThat(errorCode(call(HttpMethod.GET, url + "/transactions?section=HOME", staff, null, 403))).isEqualTo("points.access_denied");
			assertThat(get(url + "/transactions", staff).get("items")).hasSize(2);
		}
	}

	@Test
	void nobodyElseSeesAChildsPoints() throws Exception {
		Setup s = setup("Privacy school");
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		String url = s.api() + "/students/" + s.s1() + "/points";
		// a classmate and a teacher of another class: they can see the child exists or not, never the points
		assertThat(errorCode(call(HttpMethod.GET, url, s.student2(), null, 403))).isEqualTo("points.access_denied");
		assertThat(errorCode(call(HttpMethod.GET, url, s.otherTeacher(), null, 404))).isEqualTo("student.not_found");
		call(HttpMethod.GET, url + "/transactions", s.student2(), null, 403);
		// a guardian of another child, another school's administrator, an adult without any link
		Persona other = this.personas.newUser("someone-elses-parent");
		this.personas.linkGuardian(s.s2(), other.userId(), "ACTIVE", false);
		call(HttpMethod.GET, url, other, null, 404);
		call(HttpMethod.GET, url, setup("Privacy elsewhere").admin(), null, 404);
		call(HttpMethod.GET, url, this.personas.newUser("stranger"), null, 404);
		// links that grant nothing: pending, revoked, custody-restricted, or without can_view_academics
		for (String status : List.of("PENDING", "REVOKED")) {
			Persona p = this.personas.newUser("parent-" + status);
			this.personas.linkGuardian(s.s1(), p.userId(), status, false);
			call(HttpMethod.GET, url, p, null, 404);
		}
		Persona restricted = this.personas.newUser("restricted");
		this.personas.linkGuardian(s.s1(), restricted.userId(), "ACTIVE", true);
		call(HttpMethod.GET, url, restricted, null, 404);
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", s.guardian().userId());
		call(HttpMethod.GET, url, s.guardian(), null, 403);
		call(HttpMethod.GET, url + "/transactions?section=HOME", s.guardian(), null, 403);
		// an unknown student
		call(HttpMethod.GET, s.api() + "/students/" + UUID.randomUUID() + "/points", s.lead(), null, 404);
	}

	@Test
	void teachersSeeOnlyThePointsOfTheClassesTheyTeach() throws Exception {
		Setup s = setup("Two classes school");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), s.s1());
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		call(HttpMethod.POST, s.api() + "/classes/" + s.otherClassId() + "/points/awards", s.otherTeacher(),
				Map.of("categoryId", category(s, "persistence"), "studentIds", List.of(s.s1())), 201);
		String url = s.api() + "/students/" + s.s1() + "/points";
		assertThat(get(url, s.lead()).get("school").get("total").asInt()).isEqualTo(1);
		assertThat(get(url, s.otherTeacher()).get("school").get("total").asInt()).isEqualTo(2);
		assertThat(get(url, s.admin()).get("school").get("total").asInt()).isEqualTo(3);
		assertThat(get(url, s.student1()).get("school").get("total").asInt()).isEqualTo(3);
		assertThat(get(url + "/transactions", s.lead()).get("items")).hasSize(1);
		assertThat(get(url + "/transactions", s.otherTeacher()).get("items")).hasSize(1);
		assertThat(get(url + "/transactions", s.admin()).get("items")).hasSize(2);
	}

	@Test
	void theTimelineIsKeysetPagedNewestFirst() throws Exception {
		Setup s = setup("Timeline school");
		UUID kindness = category(s, "kindness");
		for (int i = 0; i < 5; i++) {
			award(s, s.lead(), kindness, List.of(s.s1()), 201);
		}
		String url = s.api() + "/students/" + s.s1() + "/points/transactions?limit=2";
		JsonNode first = get(url, s.student1());
		assertThat(first.get("items")).hasSize(2);
		JsonNode second = get(url + "&cursor=" + first.get("nextCursor").asString(), s.student1());
		JsonNode third = get(url + "&cursor=" + second.get("nextCursor").asString(), s.student1());
		assertThat(third.get("items")).hasSize(1);
		assertThat(absent(third, "nextCursor")).isTrue();
		List<String> ids = new java.util.ArrayList<>();
		for (JsonNode page : List.of(first, second, third)) {
			ids.addAll(page.get("items").findValuesAsString("id"));
		}
		assertThat(ids).doesNotHaveDuplicates().hasSize(5);
		assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		call(HttpMethod.GET, url + "&cursor=garbage", s.student1(), null, 400);
	}

}
