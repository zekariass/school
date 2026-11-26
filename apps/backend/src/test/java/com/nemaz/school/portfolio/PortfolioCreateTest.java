package com.nemaz.school.portfolio;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P1: who creates what, with which files and fields. */
@IntegrationTest
class PortfolioCreateTest extends PortfolioTestSupport {

	@Test
	void studentCreatesDraftThenSubmits() throws Exception {
		Pf p = pf("create1");
		JsonNode created = create(p.student1(), p, textBody("My story", "Once upon a time", "reflection", "I learned to write"), 201);
		assertThat(created.get("status").asString()).isEqualTo("DRAFT");
		assertThat(created.get("visibility").asString()).isEqualTo("GUARDIANS");
		assertThat(created.get("createdByRole").asString()).isEqualTo("STUDENT");
		assertThat(created.get("classId").asString()).isEqualTo(p.classId().toString());
		assertThat(created.get("capabilities").get("canSubmit").asBoolean()).isTrue();
		UUID id = id(created);
		JsonNode submitted = post(p.item(id) + "/submit", p.student1(), null, 200);
		assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(submitted.get("submittedAt").isNull()).isFalse();
	}

	@Test
	void submitInOneCall() throws Exception {
		Pf p = pf("create2");
		JsonNode created = create(p.student1(), p, textBody("Quick", "text", "mode", "SUBMIT"), 201);
		assertThat(created.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ?::uuid and action = 'SUBMITTED'",
				Integer.class, created.get("id").asString())).isEqualTo(1);
	}

	@Test
	void studentCreateSwitchedOffRefuses() throws Exception {
		Pf p = pf("create3");
		setting(p.schoolId(), "portfolio.student_create.enabled", false);
		assertThat(code(create(p.student1(), p, textBody("No", "no"), 403))).isEqualTo("portfolio.student_create_disabled");
		setting(p.schoolId(), "portfolio.student_create.enabled", true);
		create(p.student1(), p, textBody("Yes", "yes"), 201);
	}

	@Test
	void featureFlagOffAnswers403() throws Exception {
		Pf p = pf("create4");
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('portfolio', 'SCHOOL', ?, false)",
				p.schoolId());
		this.clear();
		assertThat(code(create(p.student1(), p, textBody("No", "no"), 403))).isEqualTo("portfolio.feature_disabled");
		assertThat(code(call(HttpMethod.GET, p.api() + "/portfolio/capabilities", p.lead(), null, 403))).isEqualTo("portfolio.feature_disabled");
	}

	private void clear() {
		this.caches.getCache("school-configuration").clear();
	}

	@org.springframework.beans.factory.annotation.Autowired
	org.springframework.cache.CacheManager caches;

	@Test
	void onlyActiveStudentsOfTheSchoolMayCreate() throws Exception {
		Pf p = pf("create5");
		// a teacher and a guardian of the school: members/guardians who are not students; the admin sees the school but is not a student
		assertThat(code(create(p.lead(), p, textBody("T", "t"), 403))).isEqualTo("portfolio.student_only");
		assertThat(code(create(p.admin(), p, textBody("A", "a"), 403))).isEqualTo("portfolio.student_only");
		assertThat(statusOf(HttpMethod.POST, p.items(), p.guardian1(), textBody("G", "g"))).isEqualTo(403);
		// a student of another school and a withdrawn student do not even see the school
		assertThat(code(create(p.studentB(), p, textBody("B", "b"), 404))).isEqualTo("school.not_found");
		assertThat(code(create(p.withdrawnStudent(), p, textBody("W", "w"), 404))).isEqualTo("school.not_found");
		assertThat(statusOf(HttpMethod.POST, p.items(), null, textBody("X", "x"))).isEqualTo(401);
	}

	@Test
	void audioAndVideoAreRefusedWithTheDeferralError() throws Exception {
		Pf p = pf("create6");
		for (String type : List.of("AUDIO", "VIDEO")) {
			assertThat(code(create(p.student1(), p, body("itemType", type, "title", "x"), 422))).isEqualTo("portfolio.item_type_unsupported");
		}
		// the media pipeline stays strict: no video or audio file can even be uploaded for a portfolio
		requestUpload(p.student1(), "PORTFOLIO", "v.mp4", "video/mp4", 100, school(p.schoolId())).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
		requestUpload(p.student1(), "PORTFOLIO", "a.mp3", "audio/mpeg", 100, school(p.schoolId())).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
		JsonNode caps = get(p.api() + "/portfolio/capabilities", p.student1());
		assertThat(caps.get("unsupportedItemTypes").toString()).contains("AUDIO", "VIDEO");
		assertThat(caps.get("studentMayCreate").asBoolean()).isTrue();
	}

	@Test
	void itemTypeAndMediaMustMatch() throws Exception {
		Pf p = pf("create7");
		UUID photo = photo(p.student1(), p.schoolId());
		UUID doc = pdf(p.student1(), p.schoolId());
		// a text item cannot carry files, a photo item cannot carry a PDF, a document item needs a PDF
		assertThat(code(create(p.student1(), p, body("itemType", "TEXT", "title", "t", "textContent", "x", "mediaIds", List.of(photo)), 400)))
			.isEqualTo("portfolio.media_not_allowed");
		assertThat(code(create(p.student1(), p, photoBody("p", List.of(doc)), 400))).isEqualTo("portfolio.media_kind_not_allowed");
		assertThat(code(create(p.student1(), p, body("itemType", "DOCUMENT", "title", "d", "mediaIds", List.of(photo)), 400)))
			.isEqualTo("portfolio.media_kind_not_allowed");
		create(p.student1(), p, body("itemType", "DOCUMENT", "title", "d", "mediaIds", List.of(doc)), 201);
		// text content belongs to text items only
		assertThat(code(create(p.student1(), p, body("itemType", "PHOTO", "title", "p", "textContent", "no"), 400)))
			.isEqualTo("portfolio.text_content_not_allowed");
		// SCAN accepts images and PDFs; a DRAWING is an image
		UUID scan = photo(p.student1(), p.schoolId());
		create(p.student1(), p, body("itemType", "SCAN", "title", "s", "mediaIds", List.of(scan)), 201);
		UUID drawing = photo(p.student1(), p.schoolId());
		create(p.student1(), p, body("itemType", "DRAWING", "title", "d", "mediaIds", List.of(drawing)), 201);
		// submitting needs the content: text for TEXT, a file for the others
		assertThat(code(create(p.student1(), p, body("itemType", "TEXT", "title", "t", "mode", "SUBMIT"), 400)))
			.isEqualTo("portfolio.text_content_required");
		assertThat(code(create(p.student1(), p, body("itemType", "PHOTO", "title", "t", "mode", "SUBMIT"), 400)))
			.isEqualTo("portfolio.media_required");
	}

	@Test
	void textIsCleanedAndBounded() throws Exception {
		Pf p = pf("create8");
		JsonNode item = create(p.student1(), p, textBody("  Ti‮tle​\u0007  ", "Line\r\nTwo\u0000‏", "reflection", "  ‮what I learned  "), 201);
		assertThat(item.get("title").asString()).isEqualTo("Title");
		assertThat(item.get("textContent").asString()).isEqualTo("Line\nTwo");
		assertThat(item.get("reflection").asString()).isEqualTo("what I learned");
		assertThat(code(create(p.student1(), p, textBody("   ", "x"), 400))).isEqualTo("portfolio.title_required");
		assertThat(code(create(p.student1(), p, textBody("t".repeat(121), "x"), 400))).isEqualTo("portfolio.title_too_long");
		assertThat(code(create(p.student1(), p, textBody("t", "x", "description", "d".repeat(1001)), 400))).isEqualTo("portfolio.description_too_long");
		assertThat(code(create(p.student1(), p, textBody("t", "x", "reflection", "r".repeat(1001)), 400))).isEqualTo("portfolio.reflection_too_long");
		assertThat(code(create(p.student1(), p, textBody("t", "x".repeat(5001)), 400))).isEqualTo("portfolio.text_content_too_long");
		// markup is just characters
		assertThat(create(p.student1(), p, textBody("<b>bold</b>", "<script>alert(1)</script>"), 201).get("title").asString()).isEqualTo("<b>bold</b>");
	}

	@Test
	void classMustBeOneTheStudentIsActiveIn() throws Exception {
		Pf p = pf("create9");
		assertThat(code(create(p.student1(), p, textBody("t", "x", "classId", p.otherClassId()), 400))).isEqualTo("portfolio.class_invalid");
		assertThat(code(create(p.student1(), p, textBody("t", "x", "classId", newId()), 400))).isEqualTo("portfolio.class_invalid");
		assertThat(create(p.student1(), p, textBody("t", "x", "classId", p.classId()), 201).get("classId").asString()).isEqualTo(p.classId().toString());
		// a former member of the class cannot name it
		assertThat(code(create(p.formerClassStudent(), p, textBody("t", "x", "classId", p.classId()), 400))).isEqualTo("portfolio.class_invalid");
	}

	@Test
	void classDefaultsWhenTheStudentHasExactlyOne() throws Exception {
		Pf p = pf("create10");
		assertThat(create(p.student1(), p, textBody("one", "x"), 201).get("classId").asString()).isEqualTo(p.classId().toString());
		// no class: the former member has none, and an item without a class is reviewed by school-level reviewers only
		JsonNode noClass = create(p.formerClassStudent(), p, textBody("none", "x", "mode", "SUBMIT"), 201);
		assertThat(noClass.get("classId")).isNull();
		UUID id = id(noClass);
		assertThat(statusOf(HttpMethod.POST, p.item(id) + "/review", p.lead(), body("decision", "APPROVE"))).isEqualTo(404);
		post(p.item(id) + "/review", p.admin(), body("decision", "APPROVE"), 200);
		// two classes: no default either
		this.personas.addStudentToClass(p.schoolId(), p.otherClassId(), p.s1());
		assertThat(create(p.student1(), p, textBody("two", "x"), 201).get("classId")).isNull();
	}

	@Test
	void subjectMustBelongToTheSchool() throws Exception {
		Pf p = pf("create11");
		UUID foreign = newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Foreign', 'F')", foreign, p.schoolB());
		assertThat(code(create(p.student1(), p, textBody("t", "x", "subjectId", foreign), 400))).isEqualTo("portfolio.subject_invalid");
		assertThat(code(create(p.student1(), p, textBody("t", "x", "subjectId", newId()), 400))).isEqualTo("portfolio.subject_invalid");
		assertThat(create(p.student1(), p, textBody("t", "x", "subjectId", p.mathsId()), 201).get("subjectId").asString())
			.isEqualTo(p.mathsId().toString());
	}

	@Test
	void mediaMustBeReadyOwnPortfolioUpload() throws Exception {
		Pf p = pf("create12");
		UUID others = photo(p.student2(), p.schoolId());
		assertThat(statusOf(HttpMethod.POST, p.items(), p.student1(), photoBody("p", List.of(others)))).isEqualTo(404);
		UUID unknown = newId();
		assertThat(statusOf(HttpMethod.POST, p.items(), p.student1(), photoBody("p", List.of(unknown)))).isEqualTo(404);
		// an upload of another purpose or another school's context cannot be attached
		UUID avatarLike = UUID.fromString(uploadReady(p.student1(), "AVATAR", "a.jpg", "image/jpeg",
				com.nemaz.school.support.MediaFixtures.jpeg(32, 32), null).get("id").asString());
		assertThat(code(create(p.student1(), p, photoBody("p", List.of(avatarLike)), 400))).isEqualTo("media.purpose_mismatch");
		// not READY: an upload that was requested but never completed
		JsonNode ticket = newUpload(p.student1(), "PORTFOLIO", "x.jpg", "image/jpeg", com.nemaz.school.support.MediaFixtures.jpeg(32, 32), school(p.schoolId()));
		assertThat(code(create(p.student1(), p, photoBody("p", List.of(UUID.fromString(ticket.get("mediaId").asString()))), 409))).isEqualTo("media.not_ready");
		// too many files
		List<UUID> many = new java.util.ArrayList<>();
		for (int i = 0; i < 11; i++) {
			many.add(newId());
		}
		assertThat(code(create(p.student1(), p, photoBody("p", many), 400))).isEqualTo("portfolio.too_many_media");
	}

	@Test
	void oneFileBelongsToOneItem() throws Exception {
		Pf p = pf("create13");
		UUID media = photo(p.student1(), p.schoolId());
		create(p.student1(), p, photoBody("first", List.of(media)), 201);
		assertThat(code(create(p.student1(), p, photoBody("second", List.of(media)), 409))).isEqualTo("portfolio.media_already_attached");
		assertThat(code(create(p.student1(), p, photoBody("dup", List.of(media, media)), 400))).isEqualTo("portfolio.media_already_attached");
	}

	@Test
	void createIsIdempotent() throws Exception {
		Pf p = pf("create14");
		String key = UUID.randomUUID().toString();
		var request = MockMvcRequestBuilders.post(p.items())
			.with(p.student1().bearer())
			.header("Idempotency-Key", key)
			.contentType("application/json")
			.content(this.json.writeValueAsString(textBody("once", "x")));
		var first = this.mvc.perform(request).andReturn().getResponse();
		var second = this.mvc.perform(request).andReturn().getResponse();
		assertThat(first.getStatus()).isEqualTo(201);
		assertThat(second.getStatus()).isEqualTo(201);
		assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
		assertThat(this.json.readTree(second.getContentAsString())).isEqualTo(this.json.readTree(first.getContentAsString()));
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_items where student_id = ?", Integer.class, p.s1())).isEqualTo(1);
	}

	@Test
	void portfolioIsCreatedLazilyOncePerStudentAndSchool() throws Exception {
		Pf p = pf("create15");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolios where student_id = ?", Integer.class, p.s1())).isZero();
		get(p.api() + "/students/" + p.s1() + "/portfolio", p.student1());
		assertThat(this.jdbc.queryForObject("select count(*) from portfolios where student_id = ?", Integer.class, p.s1())).isZero();
		create(p.student1(), p, textBody("a", "x"), 201);
		create(p.student1(), p, textBody("b", "x"), 201);
		assertThat(this.jdbc.queryForObject("select count(*) from portfolios where student_id = ?", Integer.class, p.s1())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_items where student_id = ?", Integer.class, p.s1())).isEqualTo(2);
	}

	@Test
	void visibilityDefaultsToGuardians() throws Exception {
		Pf p = pf("create16");
		assertThat(create(p.student1(), p, textBody("d", "x"), 201).get("visibility").asString()).isEqualTo("GUARDIANS");
		JsonNode wide = create(p.student1(), p, textBody("w", "x", "visibility", "SCHOOL"), 201);
		// stored as asked, shown at the school's cap (GUARDIANS by default)
		assertThat(wide.get("visibility").asString()).isEqualTo("SCHOOL");
		assertThat(wide.get("effectiveVisibility").asString()).isEqualTo("GUARDIANS");
	}

	@Test
	void workDateCannotBeInTheFuture() throws Exception {
		Pf p = pf("create17");
		assertThat(code(create(p.student1(), p, textBody("f", "x", "workDate", "2099-01-01"), 400))).isEqualTo("portfolio.work_date_invalid");
		assertThat(create(p.student1(), p, textBody("p", "x", "workDate", "2020-05-01"), 201).get("workDate").asString()).isEqualTo("2020-05-01");
		assertThat(create(p.student1(), p, textBody("today", "x"), 201).get("workDate").isNull()).isFalse();
	}

	@Test
	void createIsRateLimited() throws Exception {
		Pf p = pf("create18");
		// the limit is 100 a day per user: a user who already used it gets 429 (the counter is the shared limiter's)
		int last = 0;
		for (int i = 0; i < 101 && last != 429; i++) {
			last = statusOf(HttpMethod.POST, p.items(), p.student1(), textBody("n" + i, "x"));
		}
		assertThat(last).isEqualTo(429);
		Map<String, Object> ok = textBody("other", "x");
		assertThat(statusOf(HttpMethod.POST, p.items(), p.student2(), ok)).isEqualTo(201);
	}

}
