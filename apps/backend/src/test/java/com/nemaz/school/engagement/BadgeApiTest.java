package com.nemaz.school.engagement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Badge definitions, the rule engine (CLASS and SCHOOL points only), manual awards and idempotent grants. */
@IntegrationTest
class BadgeApiTest extends PointsTestSupport {

	@Autowired
	PointsApi pointsApi;

	private int badgesOf(UUID student) {
		return this.jdbc.queryForObject("select count(*) from student_badges where student_id = ?", Integer.class, student);
	}

	private UUID badgeId(Setup s, String code) throws Exception {
		get(s.api() + "/badges", s.admin());
		return this.jdbc.queryForObject("select id from badges where school_id = ? and code = ?", UUID.class, s.schoolId(), code);
	}

	@Test
	void theDefaultSetIsCopiedForTheSchoolAndLocalized() throws Exception {
		Setup s = setup("Default badges school");
		JsonNode badges = get(s.api() + "/badges", s.lead());
		assertThat(badges.findValuesAsString("code")).contains("first_steps", "century", "helpful_friend", "reading_champion",
				"perfect_attendance", "teachers_pick");
		JsonNode amharic = call(HttpMethod.GET, s.api() + "/badges", s.lead(), null, 200);
		assertThat(amharic.get(0).get("names").propertyNames()).contains("en", "am");
		// students are members, strangers are not
		get(s.api() + "/badges", s.student1());
		call(HttpMethod.GET, s.api() + "/badges", setup("Other badges school").admin(), null, 404);
	}

	@Test
	void pointsTotalBadgesAreGrantedOnceWhenTheThresholdIsReached() throws Exception {
		Setup s = setup("Points badge school");
		UUID persistence = category(s, "persistence");
		for (int i = 0; i < 4; i++) {
			award(s, s.lead(), persistence, List.of(s.s1()), 201);
		}
		assertThat(badgesOf(s.s1())).isZero();
		award(s, s.lead(), persistence, List.of(s.s1()), 201);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList("""
				select b.code from student_badges sb join badges b on b.id = sb.badge_id where sb.student_id = ?""", String.class,
				s.s1())).containsExactly("first_steps"));
		// more points and a redelivered event never grant it again; the classmate has none
		award(s, s.lead(), persistence, List.of(s.s1()), 201);
		this.tx.executeWithoutResult((x) -> assertThat(this.pointsApi.grantBadge(s.schoolId(), s.s1(), "first_steps", Map.of())).isFalse());
		Thread.sleep(800);
		assertThat(badgesOf(s.s1())).isEqualTo(1);
		assertThat(badgesOf(s.s2())).isZero();
		// the student, the guardian and nobody else were told, once
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select user_id from notifications where type_code = 'BADGE_AWARDED' and school_id = ?", UUID.class, s.schoolId()))
			.containsExactlyInAnyOrder(s.s1(), s.guardian().userId()));
		// and it shows on the student's profile
		JsonNode points = get(s.api() + "/students/" + s.s1() + "/points", s.guardian());
		assertThat(points.get("badges").findValuesAsString("code")).containsExactly("first_steps");
	}

	@Test
	void categoryBadgesCountThatCategoryOnly() throws Exception {
		Setup s = setup("Category badge school");
		JsonNode badge = call(HttpMethod.POST, s.api() + "/badges", s.admin(),
				Map.of("code", "kind_heart", "names", Map.of("en", "Kind heart", "am", "ደግ ልብ"), "criteriaType", "CATEGORY_TOTAL",
						"criteria", Map.of("threshold", 2, "templateCode", "kindness")),
				201);
		assertThat(badge.get("criteria").get("threshold").asInt()).isEqualTo(2);
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		Thread.sleep(800);
		assertThat(badgesOf(s.s1())).isZero();
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList("""
				select b.code from student_badges sb join badges b on b.id = sb.badge_id where sb.student_id = ?""", String.class,
				s.s1())).containsExactly("kind_heart"));
	}

	@Test
	void homePointsNeverCountTowardsBadges() throws Exception {
		Setup s = setup("Home badge school");
		UUID homework = category(s, "home_homework");
		for (int i = 0; i < 3; i++) {
			call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/home-points", s.guardian(),
					Map.of("categoryId", homework, "points", 10), 201);
		}
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select positive_total from point_balances where student_id = ? and context = 'HOME'", Integer.class, s.s1()))
			.containsExactly(30));
		Thread.sleep(800);
		assertThat(badgesOf(s.s1())).isZero();
		// needs-work points do not trigger the rules either
		award(s, s.lead(), category(s, "disruptive"), List.of(s.s1()), 201);
		Thread.sleep(500);
		assertThat(badgesOf(s.s1())).isZero();
	}

	@Test
	void theSchoolCanSwitchBadgesOff() throws Exception {
		Setup s = setup("No badges school");
		setting(s.schoolId(), "engagement.badges.enabled", false);
		UUID persistence = category(s, "persistence");
		for (int i = 0; i < 5; i++) {
			award(s, s.lead(), persistence, List.of(s.s1()), 201);
		}
		awaitClassTotal(s.classId(), s.s1(), 10);
		Thread.sleep(500);
		assertThat(badgesOf(s.s1())).isZero();
		assertThat(errorCode(call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/badges", s.lead(),
				Map.of("badgeId", badgeId(s, "teachers_pick")), 409))).isEqualTo("points.badges_disabled");
	}

	@Test
	void teachersHandOutManualBadgesIdempotently() throws Exception {
		Setup s = setup("Manual badge school");
		UUID pick = badgeId(s, "teachers_pick");
		String url = s.api() + "/students/" + s.s1() + "/badges";
		JsonNode first = call(HttpMethod.POST, url, s.lead(), Map.of("badgeId", pick), 200);
		assertThat(first.get("code").asString()).isEqualTo("teachers_pick");
		JsonNode again = call(HttpMethod.POST, url, s.assistant(), Map.of("badgeId", pick), 200);
		assertThat(again.get("awardedAt").asString()).isEqualTo(first.get("awardedAt").asString());
		assertThat(badgesOf(s.s1())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select awarded_by from student_badges where student_id = ?", UUID.class, s.s1()))
			.isEqualTo(s.leadId());
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select user_id from notifications where type_code = 'BADGE_AWARDED' and user_id in (?, ?)", UUID.class, s.s1(),
				s.guardian().userId())).containsExactlyInAnyOrder(s.s1(), s.guardian().userId()));
		// rule badges are not handed out by hand
		assertThat(errorCode(call(HttpMethod.POST, url, s.lead(), Map.of("badgeId", badgeId(s, "century")), 409)))
			.isEqualTo("points.badge_not_manual");
		// the student, their guardian, a teacher of another class and another school's administrator may not
		assertThat(errorCode(call(HttpMethod.POST, url, s.student1(), Map.of("badgeId", pick), 403))).isEqualTo("points.access_denied");
		call(HttpMethod.POST, url, s.guardian(), Map.of("badgeId", pick), 403);
		call(HttpMethod.POST, url, s.otherTeacher(), Map.of("badgeId", pick), 404);
		call(HttpMethod.POST, url, setup("Third badge school").admin(), Map.of("badgeId", pick), 404);
		// an administrator may
		call(HttpMethod.POST, s.api() + "/students/" + s.s2() + "/badges", s.admin(), Map.of("badgeId", pick), 200);
	}

	@Test
	void administratorsDefineEditAndValidateBadges() throws Exception {
		Setup s = setup("Define badges school");
		String url = s.api() + "/badges";
		call(HttpMethod.POST, url, s.lead(), Map.of("code", "x_badge", "names", Map.of("en", "X"), "criteriaType", "MANUAL"), 403);
		call(HttpMethod.POST, url, s.admin(), Map.of("code", "bad", "names", Map.of("en", "Bad"), "criteriaType", "POINTS_TOTAL"), 400);
		call(HttpMethod.POST, url, s.admin(),
				Map.of("code", "bad2", "names", Map.of("en", "Bad"), "criteriaType", "POINTS_TOTAL", "criteria", Map.of("threshold", 0)), 400);
		call(HttpMethod.POST, url, s.admin(), Map.of("code", "bad3", "names", Map.of("en", "Bad"), "criteriaType", "WHATEVER"), 400);
		JsonNode created = call(HttpMethod.POST, url, s.admin(),
				Map.of("code", "streak_30", "names", Map.of("en", "Streak"), "criteriaType", "ATTENDANCE_STREAK",
						"criteria", Map.of("days", 30)),
				201);
		assertThat(errorCode(call(HttpMethod.POST, url, s.admin(),
				Map.of("code", "streak_30", "names", Map.of("en", "Again"), "criteriaType", "MANUAL"), 409)))
			.isEqualTo("points.badge_code_taken");
		UUID id = uuid(created, "id");
		JsonNode updated = call(HttpMethod.PATCH, url + "/" + id, s.admin(),
				Map.of("names", Map.of("en", "Streak 30"), "active", false, "criteria", Map.of("days", 25)), 200);
		assertThat(updated.get("name").asString()).isEqualTo("Streak 30");
		assertThat(updated.get("active").asBoolean()).isFalse();
		assertThat(get(url, s.lead()).findValuesAsString("code")).doesNotContain("streak_30");
		assertThat(get(url + "?includeInactive=true", s.admin()).findValuesAsString("code")).contains("streak_30");
		call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("active", true), 403);
	}

	@Test
	void theAttendanceHookGrantsAStreakBadgeThroughThePointsApi() throws Exception {
		Setup s = setup("Streak hook school");
		get(s.api() + "/badges", s.admin());
		Boolean granted = this.tx.execute((x) -> this.pointsApi.grantBadge(s.schoolId(), s.s1(), "perfect_attendance",
				Map.of("days", 20)));
		assertThat(granted).isTrue();
		Boolean again = this.tx.execute((x) -> this.pointsApi.grantBadge(s.schoolId(), s.s1(), "perfect_attendance", Map.of()));
		assertThat(again).isFalse();
		Boolean unknown = this.tx.execute((x) -> this.pointsApi.grantBadge(s.schoolId(), s.s1(), "no_such_badge", Map.of()));
		assertThat(unknown).isFalse();
		assertThat(badgesOf(s.s1())).isEqualTo(1);
	}

}
