package com.nemaz.school.portfolio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P7: the lists, galleries, the guardian's combined view, paging and capabilities. */
@IntegrationTest
class PortfolioListsTest extends PortfolioTestSupport {

	@Test
	void studentPortfolioForStudentGuardianAndReviewers() throws Exception {
		Pf p = pf("lst1");
		UUID draft = insertItem(p, p.s1(), "DRAFT", "GUARDIANS", p.classId(), p.mathsId());
		UUID submitted = insertItem(p, p.s1(), "SUBMITTED", "GUARDIANS", p.classId(), p.artId());
		UUID approved = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), p.mathsId());
		UUID featured = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		this.jdbc.update("update portfolio_items set is_featured = true where id = ?", featured);
		String url = p.api() + "/students/" + p.s1() + "/portfolio";
		assertThat(ids(get(url, p.student1()))).containsExactlyInAnyOrder(draft.toString(), submitted.toString(), approved.toString(), featured.toString());
		assertThat(ids(get(url, p.guardian1()))).containsExactlyInAnyOrder(approved.toString(), featured.toString());
		assertThat(ids(get(url, p.lead()))).containsExactlyInAnyOrder(submitted.toString(), approved.toString(), featured.toString());
		// the art teacher reviews the art item only: the guardian-level approved items are not theirs to see
		assertThat(ids(get(url, p.artTeacher()))).containsExactly(submitted.toString());
		// filters
		assertThat(ids(get(url + "?status=APPROVED", p.student1()))).containsExactlyInAnyOrder(approved.toString(), featured.toString());
		assertThat(ids(get(url + "?featured=true", p.student1()))).containsExactly(featured.toString());
		assertThat(ids(get(url + "?subjectId=" + p.mathsId(), p.student1()))).containsExactlyInAnyOrder(draft.toString(), approved.toString());
		assertThat(ids(get(url + "?classId=" + p.classId() + "&itemType=TEXT", p.student1()))).hasSize(4);
		assertThat(ids(get(url + "?itemType=PHOTO", p.student1()))).isEmpty();
		// everybody else: 404, not an empty list
		for (Persona other : List.of(p.student2(), p.restricted(), p.revoked(), p.pending(), p.guardian2(), p.otherTeacher(), p.adminB(), p.studentB(),
				p.formerClassStudent(), p.withdrawnStudent(), p.otherStudent())) {
			assertThat(statusOf(HttpMethod.GET, url, other, null)).as(other.name()).isEqualTo(404);
		}
		assertThat(statusOf(HttpMethod.GET, url, p.admin(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, url, null, null)).isEqualTo(401);
	}

	@Test
	void mineListsEveryStatus() throws Exception {
		Pf p = pf("lst2");
		List<String> mine = new ArrayList<>();
		for (String status : List.of("DRAFT", "SUBMITTED", "CHANGES_REQUESTED", "APPROVED", "REJECTED", "ARCHIVED")) {
			mine.add(insertItem(p, p.s1(), status, "GUARDIANS", p.classId(), null).toString());
		}
		insertItem(p, p.s2(), "APPROVED", "GUARDIANS", p.classId(), null);
		assertThat(ids(get(p.api() + "/portfolio/mine", p.student1()))).containsExactlyInAnyOrderElementsOf(mine);
		assertThat(ids(get(p.api() + "/portfolio/mine?status=REJECTED", p.student1()))).hasSize(1);
		// what a teacher captured shows in the teacher's own list too
		JsonNode captured = post(p.classApi() + "/portfolio/quick-capture", p.lead(), body("studentId", p.s2(), "itemType", "TEXT", "title", "t",
				"textContent", "x"), 201);
		assertThat(ids(get(p.api() + "/portfolio/mine", p.lead()))).containsExactly(captured.get("id").asString());
		assertThat(ids(get(p.api() + "/portfolio/mine", p.student2()))).contains(captured.get("id").asString());
		assertThat(ids(get(p.api() + "/portfolio/mine", p.guardian1()))).isEmpty();
		assertThat(statusOf(HttpMethod.GET, p.api() + "/portfolio/mine", p.adminB(), null)).isEqualTo(404);
	}

	@Test
	void classGalleryShowsClassVisibleApprovedItemsOnly() throws Exception {
		Pf p = pf("lst3");
		cap(p, "SCHOOL");
		UUID guardians = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		UUID classItem = insertItem(p, p.s1(), "APPROVED", "CLASS", p.classId(), null);
		UUID schoolItem = insertItem(p, p.s3(), "APPROVED", "SCHOOL", p.classId(), null);
		insertItem(p, p.s1(), "SUBMITTED", "CLASS", p.classId(), null);
		insertItem(p, p.s2(), "ARCHIVED", "CLASS", p.classId(), null);
		String url = p.classApi() + "/portfolio/gallery";
		for (Persona member : List.of(p.student2(), p.student3(), p.lead(), p.assistant(), p.artTeacher())) {
			assertThat(ids(get(url, member))).as(member.name()).containsExactlyInAnyOrder(classItem.toString(), schoolItem.toString())
				.doesNotContain(guardians.toString());
		}
		// the author's guardian sees their own child's class items only, never another family's
		assertThat(ids(get(url, p.guardian1()))).containsExactly(classItem.toString());
		assertThat(ids(get(url, p.guardian2()))).isEmpty();
		for (Persona outsider : List.of(p.otherStudent(), p.otherTeacher(), p.adminB(), p.formerClassStudent(), p.restricted())) {
			assertThat(statusOf(HttpMethod.GET, url, outsider, null)).as(outsider.name()).isEqualTo(404);
		}
	}

	@Test
	void schoolGalleryShowsSchoolVisibleItemsToSchoolMembersOnly() throws Exception {
		Pf p = pf("lst4");
		cap(p, "SCHOOL");
		UUID schoolItem = insertItem(p, p.s1(), "APPROVED", "SCHOOL", p.classId(), null);
		UUID otherSchoolItem = insertItem(p, p.o1(), "APPROVED", "SCHOOL", p.otherClassId(), null);
		insertItem(p, p.s1(), "APPROVED", "CLASS", p.classId(), null);
		String url = p.api() + "/portfolio/gallery";
		for (Persona member : List.of(p.student2(), p.otherStudent(), p.otherTeacher(), p.formerClassStudent(), p.lead(), p.admin())) {
			assertThat(ids(get(url, member))).as(member.name()).containsExactlyInAnyOrder(schoolItem.toString(), otherSchoolItem.toString());
		}
		// guardians of other children (and of the author) are not school members: the gallery is not theirs
		for (Persona guardian : List.of(p.guardian1(), p.guardian2(), p.otherGuardian())) {
			assertThat(code(call(HttpMethod.GET, url, guardian, null, 403))).as(guardian.name()).isEqualTo("portfolio.access_denied");
		}
		for (Persona outsider : List.of(p.adminB(), p.studentB(), p.withdrawnStudent(), p.restricted())) {
			assertThat(statusOf(HttpMethod.GET, url, outsider, null)).as(outsider.name()).isEqualTo(404);
		}
		// the cap switches the gallery off
		cap(p, "CLASS");
		assertThat(ids(get(url, p.otherStudent()))).isEmpty();
	}

	@Test
	void guardianCombinedViewAcrossChildrenWithFilter() throws Exception {
		Pf p = pf("lst5");
		Persona parent = this.personas.newUser("lst5-parent");
		UUID link1 = this.personas.linkGuardian(p.s1(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(p.s2(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(p.studentB().userId(), parent.userId(), "ACTIVE", false);
		UUID a1 = insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		UUID a2 = insertItem(p, p.s2(), "APPROVED", "CLASS", p.classId(), null);
		insertItem(p, p.s2(), "SUBMITTED", "GUARDIANS", p.classId(), null);
		insertItem(p, p.s1(), "APPROVED", "STUDENT_TEACHER", p.classId(), null);
		insertItem(p, p.s3(), "APPROVED", "GUARDIANS", p.classId(), null);
		UUID inB = insertItemIn(p.schoolB(), p.studentB().userId(), "APPROVED", "GUARDIANS", null, null);
		assertThat(ids(get("/api/v1/me/portfolio?limit=100", parent))).containsExactlyInAnyOrder(a1.toString(), a2.toString(), inB.toString());
		assertThat(ids(get("/api/v1/me/portfolio?studentId=" + p.s1(), parent))).containsExactly(a1.toString());
		assertThat(ids(get("/api/v1/me/portfolio?studentId=" + p.studentB().userId(), parent))).containsExactly(inB.toString());
		// a child who is not yours is a 404, not an empty list
		assertThat(code(call(HttpMethod.GET, "/api/v1/me/portfolio?studentId=" + p.s3(), parent, null, 404))).isEqualTo("student.not_found");
		// a school that switched portfolios off drops out
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('portfolio', 'SCHOOL', ?, false)", p.schoolB());
		this.caches().getCache("school-configuration").clear();
		assertThat(ids(get("/api/v1/me/portfolio?limit=100", parent))).containsExactlyInAnyOrder(a1.toString(), a2.toString());
		// a restricted link contributes nothing, at once
		this.jdbc.update("update student_guardians set custody_restricted = true where id = ?", link1);
		assertThat(ids(get("/api/v1/me/portfolio?limit=100", parent))).containsExactly(a2.toString());
		// people without children get an empty page
		assertThat(ids(get("/api/v1/me/portfolio", p.lead()))).isEmpty();
		assertThat(ids(get("/api/v1/me/portfolio", p.student1()))).isEmpty();
	}

	@org.springframework.beans.factory.annotation.Autowired
	org.springframework.cache.CacheManager cacheManager;

	private org.springframework.cache.CacheManager caches() {
		return this.cacheManager;
	}

	@Test
	void listsAreKeysetPaged() throws Exception {
		Pf p = pf("lst6");
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null).toString());
		}
		// UUIDv7 ids made in the same millisecond are not ordered among themselves: the database order is the order
		List<String> expectedNewestFirst = this.jdbc.queryForList("select id::text from portfolio_items where student_id = ? order by id desc", String.class, p.s1());
		assertThat(expectedNewestFirst).containsExactlyInAnyOrderElementsOf(created);
		for (String url : List.of(p.api() + "/students/" + p.s1() + "/portfolio", p.api() + "/portfolio/mine", "/api/v1/me/portfolio")) {
			Persona who = url.startsWith("/api/v1/me") ? p.guardian1() : p.student1();
			List<String> seen = new ArrayList<>();
			String cursor = null;
			int pages = 0;
			do {
				JsonNode page = get(url + "?limit=2" + ((cursor != null) ? "&cursor=" + cursor : ""), who);
				seen.addAll(ids(page));
				cursor = (page.get("nextCursor") != null) ? page.get("nextCursor").asString() : null;
				pages++;
			}
			while (cursor != null && pages < 10);
			assertThat(pages).as(url).isEqualTo(3);
			assertThat(seen).as(url).containsExactlyElementsOf(expectedNewestFirst);
			assertThat(new HashSet<>(seen)).hasSize(5);
		}
		// limit is clamped, not refused
		assertThat(ids(get(p.api() + "/portfolio/mine?limit=1000", p.student1()))).hasSize(5);
	}

	@Test
	void invalidCursorIs400() throws Exception {
		Pf p = pf("lst7");
		cap(p, "SCHOOL");
		for (String url : List.of(p.api() + "/students/" + p.s1() + "/portfolio", p.api() + "/portfolio/mine", p.api() + "/portfolio/gallery",
				p.classApi() + "/portfolio/gallery", p.classApi() + "/portfolio/review-queue")) {
			Persona who = url.endsWith("review-queue") ? p.lead() : p.student1();
			assertThat(code(call(HttpMethod.GET, url + "?cursor=not-a-cursor", who, null, 400))).as(url).isEqualTo("invalid_cursor");
		}
		assertThat(code(call(HttpMethod.GET, "/api/v1/me/portfolio?cursor=zzz", p.guardian1(), null, 400))).isEqualTo("invalid_cursor");
	}

	@Test
	void capabilitiesDescribeTheCaller() throws Exception {
		Pf p = pf("lst8");
		String url = p.api() + "/portfolio/capabilities";
		JsonNode student = get(url, p.student1());
		assertThat(student.get("studentMayCreate").asBoolean()).isTrue();
		assertThat(student.get("canCapture").asBoolean()).isFalse();
		assertThat(student.get("reviewer").asBoolean()).isFalse();
		assertThat(student.get("maxVisibility").asString()).isEqualTo("GUARDIANS");
		assertThat(student.get("itemTypes").toString()).contains("TEXT", "PHOTO", "DRAWING", "SCAN", "DOCUMENT").doesNotContain("AUDIO");
		JsonNode lead = get(url, p.lead());
		assertThat(lead.get("canCapture").asBoolean()).isTrue();
		assertThat(lead.get("captureClassIds").get(0).asString()).isEqualTo(p.classId().toString());
		assertThat(lead.get("reviewer").asBoolean()).isTrue();
		assertThat(lead.get("studentMayCreate").asBoolean()).isFalse();
		JsonNode assistant = get(url, p.assistant());
		assertThat(assistant.get("canCapture").asBoolean()).isFalse();
		assertThat(assistant.get("reviewer").asBoolean()).isFalse();
		setting(p.schoolId(), "portfolio.assistants_can_capture", true);
		assertThat(get(url, p.assistant()).get("canCapture").asBoolean()).isTrue();
		assertThat(get(url, p.admin()).get("reviewer").asBoolean()).isTrue();
		JsonNode guardian = get(url, p.guardian1());
		assertThat(guardian.get("studentMayCreate").asBoolean()).isFalse();
		assertThat(guardian.get("reviewer").asBoolean()).isFalse();
		setting(p.schoolId(), "portfolio.student_create.enabled", false);
		cap(p, "SCHOOL");
		assertThat(get(url, p.student1()).get("studentMayCreate").asBoolean()).isFalse();
		assertThat(get(url, p.student1()).get("maxVisibility").asString()).isEqualTo("SCHOOL");
		assertThat(statusOf(HttpMethod.GET, url, p.adminB(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, url, p.restricted(), null)).isEqualTo(404);
	}

}
