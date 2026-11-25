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

/** Point categories: the school's copies of the defaults, its own, a class's own, and deactivation instead of deletion. */
@IntegrationTest
class PointCategoriesApiTest extends PointsTestSupport {

	@Test
	void aSchoolGetsItsOwnCopyOfTheLocalizedDefaultsOnFirstUse() throws Exception {
		Setup s = setup("Defaults school");
		assertThat(this.jdbc.queryForObject("select count(*) from point_categories where school_id = ?", Integer.class, s.schoolId()))
			.isZero();
		JsonNode categories = get(s.api() + "/point-categories?classId=" + s.classId(), s.lead());
		List<String> codes = categories.findValuesAsString("templateCode");
		assertThat(codes).contains("helping", "participation", "teamwork", "persistence", "on_task", "kindness", "reading",
				"great_thinking", "disruptive", "unprepared", "off_task", "unkind_words")
			.doesNotContain("home_homework");
		for (JsonNode category : categories) {
			assertThat(category.get("names").get("en").asString()).isNotBlank();
			assertThat(category.get("names").get("am").asString()).isNotBlank();
			assertThat(category.get("name").asString()).isEqualTo(category.get("names").get("en").asString());
			boolean negative = category.get("polarity").asString().equals("NEEDS_WORK");
			assertThat(category.get("defaultPoints").asInt()).isBetween(1, 10);
			assertThat(negative).isEqualTo(List.of("disruptive", "unprepared", "off_task", "unkind_words")
				.contains(category.get("templateCode").asString()));
		}
		// the same call twice makes no second copy; the platform templates stay untouched
		get(s.api() + "/point-categories", s.admin());
		assertThat(this.jdbc.queryForObject("select count(*) from point_categories where school_id = ? and context = 'CLASS'",
				Integer.class, s.schoolId())).isEqualTo(12);
		assertThat(this.jdbc.queryForObject("select count(*) from point_categories where school_id is null and context = 'HOME'",
				Integer.class)).isEqualTo(4);
		// names follow the caller's language
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get(s.api() + "/point-categories?classId=" + s.classId()).with(s.lead().bearer()).header("Accept-Language", "am");
		String body = this.mvc.perform(request).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).contains("\"name\":\"ደግ ቃላት\"");
	}

	@Test
	void whoMayReadTheList() throws Exception {
		Setup s = setup("Read categories school");
		get(s.api() + "/point-categories", s.lead());
		get(s.api() + "/point-categories", s.admin());
		for (Persona p : List.of(s.student1(), s.student2())) {
			assertThat(errorCode(call(HttpMethod.GET, s.api() + "/point-categories", p, null, 403))).isEqualTo("points.access_denied");
			assertThat(errorCode(call(HttpMethod.GET, s.api() + "/point-categories?classId=" + s.classId(), p, null, 403)))
				.isEqualTo("class.access_denied");
		}
		call(HttpMethod.GET, s.api() + "/point-categories", s.guardian(), null, 404);
		call(HttpMethod.GET, s.api() + "/point-categories?classId=" + s.classId(), s.otherTeacher(), null, 404);
		call(HttpMethod.GET, s.api() + "/point-categories", setup("Read elsewhere").admin(), null, 404);
		// teachers' lists hold CLASS categories only; managers can ask for the HOME ones
		assertThat(get(s.api() + "/point-categories", s.lead()).findValuesAsString("context")).containsOnly("CLASS");
		assertThat(get(s.api() + "/point-categories?context=HOME", s.admin()).findValuesAsString("templateCode"))
			.containsExactlyInAnyOrder("home_homework", "home_reading", "home_helping", "home_manners");
	}

	@Test
	void administratorsManageSchoolCategoriesAndDeactivateInsteadOfDeleting() throws Exception {
		Setup s = setup("Manage categories school");
		String url = s.api() + "/point-categories";
		get(url, s.admin());
		call(HttpMethod.POST, url, s.lead(), Map.of("names", Map.of("en", "Nope")), 403);
		JsonNode created = call(HttpMethod.POST, url, s.admin(),
				Map.of("names", Map.of("en", "Neat work", "am", "ንጹህ ሥራ"), "icon", "sparkles", "color", "#12AB34",
						"defaultPoints", 2),
				201);
		UUID id = uuid(created, "id");
		assertThat(created.get("polarity").asString()).isEqualTo("POSITIVE");
		assertThat(created.get("context").asString()).isEqualTo("CLASS");
		assertThat(absent(created, "classId")).isTrue();
		assertThat(get(url + "?classId=" + s.classId(), s.lead()).findValuesAsString("name")).contains("Neat work");

		JsonNode renamed = call(HttpMethod.PATCH, url + "/" + id, s.admin(),
				Map.of("names", Map.of("en", "Very neat work"), "defaultPoints", 3, "sortOrder", 1), 200);
		assertThat(renamed.get("name").asString()).isEqualTo("Very neat work");
		assertThat(renamed.get("defaultPoints").asInt()).isEqualTo(3);
		call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("active", false), 403);
		call(HttpMethod.PATCH, url + "/" + id, s.admin(), Map.of("defaultPoints", 11), 400);

		// used in the ledger, then deactivated: gone from the palette, still there for editors and for history
		award(s, s.lead(), id, List.of(s.s1()), 201);
		call(HttpMethod.PATCH, url + "/" + id, s.admin(), Map.of("active", false), 200);
		assertThat(get(url + "?classId=" + s.classId(), s.lead()).findValuesAsString("name")).doesNotContain("Very neat work");
		// an assistant is no editor: includeInactive is ignored for them, the lead teacher's request is honoured
		assertThat(get(url + "?classId=" + s.classId() + "&includeInactive=true", s.assistant()).findValuesAsString("name"))
			.doesNotContain("Very neat work")
			.isNotEmpty();
		assertThat(get(url + "?classId=" + s.classId() + "&includeInactive=true", s.lead()).findValuesAsString("name"))
			.contains("Very neat work");
		assertThat(get(url + "?includeInactive=true", s.admin()).findValuesAsString("name")).contains("Very neat work");
		assertThat(this.jdbc.queryForObject("select count(*) from point_categories where id = ?", Integer.class, id)).isOne();
		// the timeline still names it
		assertThat(get(s.api() + "/students/" + s.s1() + "/points/transactions", s.student1()).get("items").get(0)
			.get("categoryName").asString()).isEqualTo("Very neat work");
	}

	@Test
	void homeCategoriesArePositiveOnlyAndNeverOfferedToTeachers() throws Exception {
		Setup s = setup("Home categories school");
		String url = s.api() + "/point-categories";
		get(url, s.admin());
		call(HttpMethod.POST, url, s.admin(), Map.of("names", Map.of("en", "Bad home"), "context", "HOME", "polarity", "NEEDS_WORK"), 400);
		JsonNode home = call(HttpMethod.POST, url, s.admin(), Map.of("names", Map.of("en", "Bedtime story"), "context", "HOME"), 201);
		assertThat(home.get("context").asString()).isEqualTo("HOME");
		assertThat(get(s.api() + "/students/" + s.s1() + "/home-point-categories", s.guardian()).findValuesAsString("name"))
			.contains("Bedtime story");
		assertThat(get(url + "?classId=" + s.classId(), s.lead()).findValuesAsString("name")).doesNotContain("Bedtime story");
		// the database agrees: a HOME category cannot be a needs-work one
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.jdbc.update("""
				update point_categories set polarity = 'NEEDS_WORK' where id = ?""", uuid(home, "id")))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void classesAddTheirOwnCategoriesThroughLeadAndCoTeachers() throws Exception {
		Setup s = setup("Class categories school");
		UUID coTeacher = this.personas.newTeacher(s.schoolId(), "co-teacher");
		this.personas.addTeacherToClass(s.schoolId(), s.classId(), coTeacher, "CO_TEACHER", null);
		Persona co = this.personas.personaFor("co-teacher", coTeacher);
		String url = s.classApi() + "/point-categories";

		JsonNode own = call(HttpMethod.POST, url, s.lead(),
				Map.of("names", Map.of("en", "Quiet reading"), "polarity", "POSITIVE", "defaultPoints", 2), 201);
		assertThat(uuid(own, "classId")).isEqualTo(s.classId());
		JsonNode byCo = call(HttpMethod.POST, url, co, Map.of("names", Map.of("en", "Tidy corner"), "polarity", "NEEDS_WORK"), 201);
		assertThat(byCo.get("polarity").asString()).isEqualTo("NEEDS_WORK");
		// assistants, students, guardians, other classes' teachers and other schools may not
		assertThat(errorCode(call(HttpMethod.POST, url, s.assistant(), Map.of("names", Map.of("en", "X")), 403))).isEqualTo("points.access_denied");
		call(HttpMethod.POST, url, s.student1(), Map.of("names", Map.of("en", "X")), 403);
		call(HttpMethod.POST, url, s.guardian(), Map.of("names", Map.of("en", "X")), 403);
		call(HttpMethod.POST, url, s.otherTeacher(), Map.of("names", Map.of("en", "X")), 404);
		call(HttpMethod.POST, url, setup("Class cat elsewhere").admin(), Map.of("names", Map.of("en", "X")), 404);
		call(HttpMethod.POST, url, s.lead(), Map.of("names", Map.of("en", "X"), "context", "HOME"), 400);
		call(HttpMethod.POST, url, s.lead(), Map.of("names", Map.of()), 400);
		// the class's own list holds them, other classes' lists do not, assistants see them for awarding
		assertThat(get(s.api() + "/point-categories?classId=" + s.classId(), s.assistant()).findValuesAsString("name"))
			.contains("Quiet reading", "Tidy corner");
		assertThat(get(s.api() + "/point-categories?classId=" + s.otherClassId(), s.otherTeacher()).findValuesAsString("name"))
			.doesNotContain("Quiet reading");
		// edit and deactivate through the class endpoint; the school endpoint does not touch class categories
		UUID id = uuid(own, "id");
		JsonNode updated = call(HttpMethod.PATCH, url + "/" + id, co, Map.of("names", Map.of("en", "Silent reading")), 200);
		assertThat(updated.get("name").asString()).isEqualTo("Silent reading");
		call(HttpMethod.PATCH, url + "/" + id, s.assistant(), Map.of("active", false), 403);
		call(HttpMethod.PATCH, s.api() + "/point-categories/" + id, s.admin(), Map.of("active", false), 404);
		call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("active", false), 200);
		assertThat(get(s.api() + "/point-categories?classId=" + s.classId(), s.assistant()).findValuesAsString("name"))
			.doesNotContain("Silent reading");
		assertThat(get(s.api() + "/point-categories?classId=" + s.classId() + "&includeInactive=true", s.lead())
			.findValuesAsString("name")).contains("Silent reading");
	}

}
