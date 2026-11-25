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

/** The toolkit's saved groups and activity templates. */
@IntegrationTest
class ToolkitApiTest extends PointsTestSupport {

	private static Map<String, Object> routine(String title, String visibility) {
		return Map.of("templateType", "ROUTINE", "title", title, "visibility", visibility, "instructions", "Do it calmly",
				"items", List.of(Map.of("text", "Pack bags", "minutes", 5), Map.of("text", "Line up")));
	}

	@Test
	void groupSetsAreSavedAtOnceAndGroupsCanBeEdited() throws Exception {
		Setup s = setup("Groups school");
		String url = s.classApi() + "/groups";
		JsonNode set = call(HttpMethod.POST, s.classApi() + "/group-sets", s.lead(),
				Map.of("groups", List.of(Map.of("name", "Red", "studentIds", List.of(s.s1())),
						Map.of("name", "Blue", "studentIds", List.of(s.s2())))),
				201);
		assertThat(set).hasSize(2);
		assertThat(get(url, s.assistant())).hasSize(2);
		// all or nothing: one group with a stranger fails the whole set
		UUID outsider = this.personas.newStudent(s.schoolId(), "Outsider");
		call(HttpMethod.POST, s.classApi() + "/group-sets", s.lead(),
				Map.of("groups", List.of(Map.of("name", "Green", "studentIds", List.of(s.s1())),
						Map.of("name", "Black", "studentIds", List.of(outsider)))),
				409);
		assertThat(get(url, s.lead())).hasSize(2);

		UUID red = uuid(set.get(0), "id");
		JsonNode updated = call(HttpMethod.PATCH, url + "/" + red, s.assistant(),
				Map.of("name", "Crimson", "studentIds", List.of(s.s1(), s.s2())), 200);
		assertThat(updated.get("name").asString()).isEqualTo("Crimson");
		assertThat(updated.get("studentIds")).hasSize(2);
		JsonNode renamed = call(HttpMethod.PATCH, url + "/" + red, s.lead(), Map.of("name", "Scarlet"), 200);
		assertThat(renamed.get("studentIds")).hasSize(2);
		call(HttpMethod.PATCH, url + "/" + red, s.lead(), Map.of("studentIds", List.of(outsider)), 409);
		call(HttpMethod.PATCH, url + "/" + UUID.randomUUID(), s.lead(), Map.of("name", "X"), 404);
		// students, guardians, other classes' teachers: no
		call(HttpMethod.PATCH, url + "/" + red, s.student1(), Map.of("name", "X"), 403);
		call(HttpMethod.PATCH, url + "/" + red, s.guardian(), Map.of("name", "X"), 403);
		call(HttpMethod.PATCH, url + "/" + red, s.otherTeacher(), Map.of("name", "X"), 404);
		call(HttpMethod.POST, s.classApi() + "/group-sets", s.student1(),
				Map.of("groups", List.of(Map.of("name", "X", "studentIds", List.of(s.s1())))), 403);
	}

	@Test
	void templatesArePrivateClassOrSchoolWide() throws Exception {
		Setup s = setup("Templates school");
		String url = s.api() + "/activity-templates";
		JsonNode mine = call(HttpMethod.POST, url, s.lead(), routine("Morning routine", "PRIVATE"), 201);
		assertThat(mine.get("mine").asBoolean()).isTrue();
		assertThat(mine.get("items")).hasSize(2);
		assertThat(mine.get("items").get(0).get("minutes").asInt()).isEqualTo(5);
		JsonNode classWide = call(HttpMethod.POST, url, s.lead(),
				Map.of("templateType", "CHECKLIST", "title", "Lab safety", "visibility", "CLASS", "classId", s.classId()), 201);
		JsonNode schoolWide = call(HttpMethod.POST, url, s.otherTeacher(), routine("Fire drill", "SCHOOL"), 201);

		List<String> leadSees = get(url, s.lead()).get("items").findValuesAsString("title");
		assertThat(leadSees).containsExactlyInAnyOrder("Morning routine", "Lab safety", "Fire drill");
		assertThat(get(url, s.assistant()).get("items").findValuesAsString("title")).containsExactlyInAnyOrder("Lab safety", "Fire drill");
		assertThat(get(url, s.otherTeacher()).get("items").findValuesAsString("title")).containsExactly("Fire drill");
		assertThat(get(url + "?mine=true", s.lead()).get("items")).hasSize(2);
		assertThat(get(url + "?templateType=CHECKLIST", s.lead()).get("items")).hasSize(1);
		assertThat(get(url, s.admin()).get("items").findValuesAsString("title")).containsExactlyInAnyOrder("Fire drill", "Lab safety");

		// what a caller may not see is 404
		call(HttpMethod.GET, url + "/" + uuid(mine, "id"), s.assistant(), null, 404);
		call(HttpMethod.GET, url + "/" + uuid(classWide, "id"), s.otherTeacher(), null, 404);
		get(url + "/" + uuid(schoolWide, "id"), s.lead());
		// students, guardians and strangers are not teachers
		call(HttpMethod.GET, url, s.student1(), null, 403);
		call(HttpMethod.GET, url, s.guardian(), null, 404);
		call(HttpMethod.GET, url, setup("Templates elsewhere").admin(), null, 404);
		call(HttpMethod.POST, url, s.student1(), routine("Mine", "PRIVATE"), 403);
	}

	@Test
	void templatesAreChangedAndDeletedByTheirOwnerOrAPointsManager() throws Exception {
		Setup s = setup("Edit templates school");
		String url = s.api() + "/activity-templates";
		UUID id = uuid(call(HttpMethod.POST, url, s.lead(), routine("Shared", "SCHOOL"), 201), "id");
		// a colleague sees it but cannot change it
		call(HttpMethod.PATCH, url + "/" + id, s.otherTeacher(), Map.of("title", "Hijacked"), 403);
		call(HttpMethod.DELETE, url + "/" + id, s.otherTeacher(), null, 403);
		JsonNode changed = call(HttpMethod.PATCH, url + "/" + id, s.lead(),
				Map.of("title", "Shared v2", "items", List.of(Map.of("text", "Only step"))), 200);
		assertThat(changed.get("title").asString()).isEqualTo("Shared v2");
		assertThat(changed.get("items")).hasSize(1);
		assertThat(changed.get("instructions").asString()).isEqualTo("Do it calmly");
		assertThat(call(HttpMethod.PATCH, url + "/" + id, s.admin(), Map.of("title", "Admin edit"), 200).get("title").asString())
			.isEqualTo("Admin edit");
		// moving it to a class the owner does not teach is refused, to their own class works
		call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("visibility", "CLASS", "classId", s.otherClassId()), 404);
		JsonNode toClass = call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("visibility", "CLASS", "classId", s.classId()), 200);
		assertThat(toClass.get("visibility").asString()).isEqualTo("CLASS");
		call(HttpMethod.PATCH, url + "/" + id, s.lead(), Map.of("visibility", "CLASS"), 400);
		call(HttpMethod.DELETE, url + "/" + id, s.lead(), null, 204);
		call(HttpMethod.GET, url + "/" + id, s.lead(), null, 404);
	}

	@Test
	void templateInputIsValidated() throws Exception {
		Setup s = setup("Template validation school");
		String url = s.api() + "/activity-templates";
		call(HttpMethod.POST, url, s.lead(), Map.of("templateType", "ROUTINE", "title", " "), 400);
		call(HttpMethod.POST, url, s.lead(), Map.of("templateType", "ROUTINE"), 400);
		call(HttpMethod.POST, url, s.lead(), Map.of("title", "No type"), 400);
		call(HttpMethod.POST, url, s.lead(), Map.of("templateType", "ROUTINE", "title", "X", "visibility", "CLASS"), 400);
		call(HttpMethod.POST, url, s.lead(), Map.of("templateType", "ROUTINE", "title", "X", "classId", s.classId()), 400);
		call(HttpMethod.POST, url, s.lead(),
				Map.of("templateType", "ROUTINE", "title", "X", "subjectId", UUID.randomUUID()), 404);
		call(HttpMethod.POST, url, s.lead(),
				Map.of("templateType", "ROUTINE", "title", "X", "items", List.of(Map.of("text", " "))), 400);
		call(HttpMethod.POST, url, s.lead(),
				Map.of("templateType", "CHECKLIST", "title", "Other class", "visibility", "CLASS", "classId", s.otherClassId()), 404);
		Persona outsider = setup("Template validation elsewhere").lead();
		call(HttpMethod.POST, url, outsider, Map.of("templateType", "ROUTINE", "title", "X"), 404);
	}

}
