package com.nemaz.school.portfolio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P4: who sees an item in which status and audience, singly and in the lists. */
@IntegrationTest
class PortfolioVisibilityMatrixTest extends PortfolioTestSupport {

	private static final List<String> STATUSES = List.of("DRAFT", "SUBMITTED", "CHANGES_REQUESTED", "APPROVED", "REJECTED", "ARCHIVED");

	private static final List<String> VISIBILITIES = List.of("STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL");

	private static int rank(String visibility) {
		return VISIBILITIES.indexOf(visibility);
	}

	/** The expected answer of the rule book, written out independently of the code. */
	private static boolean expected(String who, String status, String visibility) {
		boolean approved = status.equals("APPROVED");
		int r = rank(visibility);
		return switch (who) {
			case "student" -> true;
			case "lead", "admin" -> !status.equals("DRAFT");
			case "assistant", "classmate" -> approved && r >= rank("CLASS");
			case "guardian" -> approved && r >= rank("GUARDIANS");
			case "otherTeacher", "otherStudent", "formerClassStudent" -> approved && r >= rank("SCHOOL");
			default -> false; // restricted, revoked, pending, other guardians, withdrawn student, other school
		};
	}

	@Test
	void everyStatusVisibilityAndPersonFollowsTheMatrix() throws Exception {
		Pf p = pf("vis1");
		cap(p, "SCHOOL");
		Map<String, Persona> people = new LinkedHashMap<>();
		people.put("student", p.student1());
		people.put("lead", p.lead());
		people.put("admin", p.admin());
		people.put("assistant", p.assistant());
		people.put("classmate", p.student2());
		people.put("guardian", p.guardian1());
		people.put("restricted", p.restricted());
		people.put("revoked", p.revoked());
		people.put("pending", p.pending());
		people.put("guardianOfOther", p.guardian2());
		people.put("otherGuardian", p.otherGuardian());
		people.put("otherTeacher", p.otherTeacher());
		people.put("otherStudent", p.otherStudent());
		people.put("formerClassStudent", p.formerClassStudent());
		people.put("withdrawnStudent", p.withdrawnStudent());
		people.put("adminB", p.adminB());
		people.put("studentB", p.studentB());
		Map<String, UUID> items = new LinkedHashMap<>();
		for (String status : STATUSES) {
			for (String visibility : VISIBILITIES) {
				items.put(status + "/" + visibility, insertItem(p, p.s1(), status, visibility, p.classId(), null));
			}
		}
		// single reads
		for (Map.Entry<String, Persona> person : people.entrySet()) {
			for (Map.Entry<String, UUID> item : items.entrySet()) {
				String[] key = item.getKey().split("/");
				int status = statusOf(HttpMethod.GET, p.item(item.getValue()), person.getValue(), null);
				assertThat(status).as(person.getKey() + " reads " + item.getKey())
					.isEqualTo(expected(person.getKey(), key[0], key[1]) ? 200 : 404);
			}
		}
		// lists: what each list shows is exactly what the rule says, and never more than the single read allows
		Set<String> all = new HashSet<>();
		items.values().forEach((id) -> all.add(id.toString()));
		assertThat(ids(get(p.api() + "/portfolio/mine?limit=100", p.student1()))).containsExactlyInAnyOrderElementsOf(all);
		assertThat(ids(get(p.api() + "/students/" + p.s1() + "/portfolio?limit=100", p.student1()))).containsExactlyInAnyOrderElementsOf(all);
		for (Persona reviewer : List.of(p.lead(), p.admin())) {
			assertThat(ids(get(p.api() + "/students/" + p.s1() + "/portfolio?limit=100", reviewer)))
				.containsExactlyInAnyOrderElementsOf(matching(items, "lead"));
		}
		assertThat(ids(get("/api/v1/me/portfolio?limit=100", p.guardian1()))).containsExactlyInAnyOrderElementsOf(matching(items, "guardian"));
		assertThat(ids(get(p.api() + "/students/" + p.s1() + "/portfolio?limit=100", p.guardian1())))
			.containsExactlyInAnyOrderElementsOf(matching(items, "guardian"));
		for (Persona nobody : List.of(p.restricted(), p.revoked(), p.pending(), p.guardian2(), p.otherGuardian())) {
			assertThat(ids(get("/api/v1/me/portfolio?limit=100", nobody))).as(nobody.name()).isEmpty();
			assertThat(statusOf(HttpMethod.GET, p.api() + "/students/" + p.s1() + "/portfolio", nobody, null)).as(nobody.name()).isEqualTo(404);
		}
		for (Persona classMember : List.of(p.student2(), p.assistant())) {
			assertThat(ids(get(p.classApi() + "/portfolio/gallery?limit=100", classMember))).as(classMember.name())
				.containsExactlyInAnyOrderElementsOf(matching(items, "classmate"));
			assertThat(statusOf(HttpMethod.GET, p.api() + "/students/" + p.s1() + "/portfolio", classMember, null) == 404
					|| classMember == p.assistant()).isTrue();
		}
		for (Persona schoolMember : List.of(p.otherStudent(), p.otherTeacher(), p.formerClassStudent(), p.student2(), p.assistant())) {
			assertThat(ids(get(p.api() + "/portfolio/gallery?limit=100", schoolMember))).as(schoolMember.name())
				.containsExactlyInAnyOrderElementsOf(matching(items, "otherStudent"));
		}
		for (Persona outsider : List.of(p.otherStudent(), p.otherTeacher(), p.formerClassStudent(), p.guardian1())) {
			assertThat(statusOf(HttpMethod.GET, p.classApi() + "/portfolio/gallery", outsider, null)).as(outsider.name())
				.isEqualTo(outsider == p.guardian1() ? 200 : 404);
		}
		// a guardian who sees the class gallery sees only the items of their own child
		assertThat(ids(get(p.classApi() + "/portfolio/gallery?limit=100", p.guardian1()))).containsExactlyInAnyOrderElementsOf(matching(items, "guardian")
			.stream().filter((id) -> rankOf(items, id) >= rank("CLASS")).toList());
		for (Persona strangers : List.of(p.adminB(), p.studentB(), p.withdrawnStudent())) {
			assertThat(statusOf(HttpMethod.GET, p.api() + "/portfolio/gallery", strangers, null)).as(strangers.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, p.api() + "/students/" + p.s1() + "/portfolio", strangers, null)).as(strangers.name()).isEqualTo(404);
		}
	}

	private static int rankOf(Map<String, UUID> items, String id) {
		return items.entrySet().stream().filter((e) -> e.getValue().toString().equals(id)).findFirst().map((e) -> rank(e.getKey().split("/")[1])).orElse(-1);
	}

	private static List<String> matching(Map<String, UUID> items, String who) {
		List<String> ids = new ArrayList<>();
		items.forEach((key, id) -> {
			String[] parts = key.split("/");
			if (expected(who, parts[0], parts[1])) {
				ids.add(id.toString());
			}
		});
		return ids;
	}

	@Test
	void theSchoolCapLowersEffectiveVisibilityAndRaisingItRestoresIt() throws Exception {
		Pf p = pf("vis2");
		UUID id = insertItem(p, p.s1(), "APPROVED", "SCHOOL", p.classId(), null);
		String[][] steps = { { "SCHOOL", "SCHOOL" }, { "CLASS", "CLASS" }, { "GUARDIANS", "GUARDIANS" }, { "STUDENT_TEACHER", "STUDENT_TEACHER" } };
		// who sees it at each cap: other student (school), classmate (class), guardian (guardians), student and lead always
		int[][] sees = { { 200, 200, 200 }, { 404, 200, 200 }, { 404, 404, 200 }, { 404, 404, 404 } };
		for (int i = 0; i < steps.length; i++) {
			cap(p, steps[i][0]);
			JsonNode view = get(p.item(id), p.student1());
			assertThat(view.get("visibility").asString()).isEqualTo("SCHOOL");
			assertThat(view.get("effectiveVisibility").asString()).as(steps[i][0]).isEqualTo(steps[i][1]);
			assertThat(statusOf(HttpMethod.GET, p.item(id), p.otherStudent(), null)).as("other student at " + steps[i][0]).isEqualTo(sees[i][0]);
			assertThat(statusOf(HttpMethod.GET, p.item(id), p.student2(), null)).as("classmate at " + steps[i][0]).isEqualTo(sees[i][1]);
			assertThat(statusOf(HttpMethod.GET, p.item(id), p.guardian1(), null)).as("guardian at " + steps[i][0]).isEqualTo(sees[i][2]);
			assertThat(statusOf(HttpMethod.GET, p.item(id), p.lead(), null)).isEqualTo(200);
		}
		// the stored visibility was never rewritten, so raising the cap restores everything
		assertThat(this.jdbc.queryForObject("select visibility from portfolio_items where id = ?", String.class, id)).isEqualTo("SCHOOL");
		cap(p, "SCHOOL");
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.otherStudent(), null)).isEqualTo(200);
		assertThat(ids(get(p.api() + "/portfolio/gallery", p.otherStudent()))).containsExactly(id.toString());
		cap(p, "GUARDIANS");
		assertThat(ids(get(p.api() + "/portfolio/gallery", p.otherStudent()))).isEmpty();
		assertThat(ids(get(p.classApi() + "/portfolio/gallery", p.student2()))).isEmpty();
		assertThat(ids(get("/api/v1/me/portfolio", p.guardian1()))).containsExactly(id.toString());
		// the default cap of a school that never set one is GUARDIANS
		UUID other = basicSchool("vis2b");
		assertThat(this.settings.getString("portfolio.max_visibility", SettingScopeOf.school(other)).orElse("")).isEqualTo("GUARDIANS");
	}

	/** Reads a school-scoped setting without importing the scope class in every test. */
	private static final class SettingScopeOf {

		static com.nemaz.school.tenancy.SettingScope school(UUID id) {
			return com.nemaz.school.tenancy.SettingScope.school(id);
		}

	}

	@Test
	void guardianAudienceNeedsALiveActiveUnrestrictedLinkWithAcademics() throws Exception {
		Pf p = pf("vis3");
		UUID id = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		Persona g = this.personas.newUser("vis3-g");
		UUID link = this.personas.linkGuardian(p.s1(), g.userId(), "ACTIVE", false);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).isEqualTo(200);
		this.jdbc.update("update student_guardians set custody_restricted = true where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).as("custody-restricted").isEqualTo(404);
		assertThat(ids(get("/api/v1/me/portfolio", g))).isEmpty();
		this.jdbc.update("update student_guardians set custody_restricted = false where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).isEqualTo(200);
		this.jdbc.update("update student_guardians set can_view_academics = false where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).as("without academics").isEqualTo(404);
		this.jdbc.update("update student_guardians set can_view_academics = true where id = ?", link);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).as("revoked").isEqualTo(404);
		this.jdbc.update("update student_guardians set status = 'ACTIVE', revoked_at = null where id = ?", link);
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).isEqualTo(200);
		// the child leaves the school: the guardian's access ends with it
		this.jdbc.update("update school_students set status = 'WITHDRAWN', left_on = current_date where student_id = ?", p.s1());
		assertThat(statusOf(HttpMethod.GET, p.item(id), g, null)).as("child withdrawn").isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.student1(), null)).as("student withdrawn").isEqualTo(404);
		// a reviewing teacher still sees what is on file (safeguarding)
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.lead(), null)).isEqualTo(200);
	}

	@Test
	void formerAndWithdrawnStudentsLoseAccess() throws Exception {
		Pf p = pf("vis4");
		cap(p, "SCHOOL");
		UUID classItem = insertItem(p, p.s1(), "APPROVED", "CLASS", p.classId(), null);
		UUID schoolItem = insertItem(p, p.s1(), "APPROVED", "SCHOOL", p.classId(), null);
		// a former member of the class (still ACTIVE in the school) sees the school's items, not the class's
		assertThat(statusOf(HttpMethod.GET, p.item(classItem), p.formerClassStudent(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(schoolItem), p.formerClassStudent(), null)).isEqualTo(200);
		UUID own = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		UUID formerOwn = insertItem(p, this.jdbc.queryForObject("select student_id from class_students where status = 'FORMER' and class_id = ?",
				UUID.class, p.classId()), "APPROVED", "GUARDIANS", null, null);
		assertThat(statusOf(HttpMethod.GET, p.item(formerOwn), p.formerClassStudent(), null)).isEqualTo(200);
		// a student withdrawn from the school sees nothing at all, not even their own item
		UUID wolde = this.jdbc.queryForObject("select student_id from school_students where status = 'WITHDRAWN' and school_id = ?", UUID.class, p.schoolId());
		UUID woldeItem = insertItem(p, wolde, "APPROVED", "GUARDIANS", null, null);
		assertThat(code(call(HttpMethod.GET, p.item(woldeItem), p.withdrawnStudent(), null, 404))).isEqualTo("school.not_found");
		assertThat(code(call(HttpMethod.GET, p.item(classItem), p.withdrawnStudent(), null, 404))).isEqualTo("school.not_found");
		// the author leaves the class: classmates stop seeing the class item, the guardian keeps the guardian-level view
		this.jdbc.update("update class_students set status = 'FORMER', left_at = now(), left_reason = 'TRANSFERRED_CLASS' where student_id = ? and class_id = ?",
				p.s1(), p.classId());
		assertThat(statusOf(HttpMethod.GET, p.item(classItem), p.student2(), null)).as("classmate after the author left").isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(classItem), p.guardian1(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, p.item(own), p.student1(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, p.item(schoolItem), p.otherStudent(), null)).isEqualTo(200);
	}

	@Test
	void classAudienceSeesBasicStudentDataAndNoGuardianData() throws Exception {
		Pf p = pf("vis5");
		cap(p, "CLASS");
		this.personas.setDateOfBirth(p.s1(), java.time.LocalDate.of(2015, 3, 4));
		UUID id = insertItem(p, p.s1(), "APPROVED", "CLASS", p.classId(), null);
		JsonNode view = get(p.item(id), p.student2());
		assertThat(view.get("student").get("displayName").asString()).isEqualTo("Abel");
		assertThat(keys(view)).doesNotContain("guardian", "guardians", "guardianId", "phone", "email", "dateOfBirth", "gender", "username",
				"relationshipType", "relationship");
		assertThat(view.get("taggedStudents")).isNull();
		assertThat(view.get("reviewNote")).isNull();
		assertThat(view.get("capabilities").get("canReview").asBoolean()).isFalse();
		assertThat(view.get("capabilities").get("canDelete").asBoolean()).isFalse();
		JsonNode gallery = get(p.classApi() + "/portfolio/gallery", p.student2());
		assertThat(keys(gallery)).doesNotContain("guardian", "guardians", "phone", "email", "dateOfBirth", "gender", "username");
	}

	private static Set<String> keys(JsonNode node) {
		Set<String> keys = new HashSet<>();
		collect(node, keys);
		return keys;
	}

	private static void collect(JsonNode node, Set<String> keys) {
		if (node.isObject()) {
			node.propertyNames().forEach((name) -> {
				keys.add(name);
				collect(node.get(name), keys);
			});
		}
		else if (node.isArray()) {
			node.forEach((child) -> collect(child, keys));
		}
	}

	@Test
	void payloadsNeverCarryPrivateStudentFields() throws Exception {
		Pf p = pf("vis6");
		cap(p, "SCHOOL");
		this.personas.setDateOfBirth(p.s1(), java.time.LocalDate.of(2015, 3, 4));
		UUID id = approved(p, p.student1(), "visible", "SCHOOL");
		Set<String> allowed = Set.of("studentId", "displayName", "avatar", "avatarConfig");
		List<JsonNode> reads = new ArrayList<>();
		for (Persona who : List.of(p.student1(), p.lead(), p.admin(), p.guardian1(), p.student2(), p.otherStudent())) {
			reads.add(get(p.item(id), who));
		}
		reads.add(get(p.api() + "/students/" + p.s1() + "/portfolio", p.lead()).get("items").get(0));
		reads.add(get(p.api() + "/portfolio/gallery", p.otherStudent()).get("items").get(0));
		reads.add(get("/api/v1/me/portfolio", p.guardian1()).get("items").get(0));
		for (JsonNode read : reads) {
			Set<String> keys = new HashSet<>();
			read.get("student").propertyNames().forEach(keys::add);
			assertThat(allowed).containsAll(keys);
			assertThat(read.toString()).doesNotContain("2015-03-04");
		}
	}

	@Test
	void oversightReadsAreAudited() throws Exception {
		Pf p = pf("vis7");
		UUID id = insertItem(p, p.s1(), "SUBMITTED", "GUARDIANS", p.classId(), null);
		get(p.item(id), p.student1());
		get(p.item(id), p.lead());
		assertThat(oversightReads(id)).isZero();
		get(p.item(id), p.admin());
		assertThat(oversightReads(id)).isEqualTo(1);
		Persona holder = this.personas.withPlatformPermissions("school.portfolios.review", "school.read");
		get(p.item(id), holder);
		assertThat(oversightReads(id)).isEqualTo(2);
		assertThat(this.jdbc.queryForList("select coalesce(metadata::text, '') from audit_logs where action = 'portfolio.item.read_oversight' and target_id = ?",
				String.class, id)).allSatisfy((m) -> assertThat(m).doesNotContain("Matrix"));
	}

	private int oversightReads(UUID itemId) {
		return this.jdbc.queryForObject("select count(*) from audit_logs where action = 'portfolio.item.read_oversight' and target_id = ?",
				Integer.class, itemId);
	}

}
