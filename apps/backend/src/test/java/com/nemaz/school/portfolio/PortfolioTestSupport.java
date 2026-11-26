package com.nemaz.school.portfolio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.MediaTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * A school with classes and the people around them, and the calls of the portfolio API with real uploads (the files go through the
 * presigned PUT to the test object store like a client's).
 */
public abstract class PortfolioTestSupport extends MediaTestSupport {

	@Autowired
	protected SettingsService settings;

	/**
	 * School "label": class 1A with LEAD, CO_TEACHER, SUBJECT_TEACHER (Maths), SUBJECT_TEACHER (Art) and ASSISTANT teachers; students
	 * Abel (s1, the usual author), Bethlehem (s2, a classmate), Chala (s3, a classmate); guardians of s1 (active, restricted, revoked,
	 * pending) and of s2; a former member of 1A (still ACTIVE in the school), a student withdrawn from the school; class 2B with its
	 * teacher, a student and a guardian; the school administrator; another school with its administrator and a student.
	 */
	protected record Pf(UUID schoolId, Persona admin, Persona lead, Persona co, Persona mathsTeacher, Persona artTeacher,
			Persona assistant, UUID classId, UUID mathsId, UUID artId, UUID s1, Persona student1, UUID s2, Persona student2, UUID s3,
			Persona student3, Persona guardian1, Persona guardian2, Persona restricted, Persona revoked, Persona pending,
			Persona formerClassStudent, Persona withdrawnStudent, UUID otherClassId, Persona otherTeacher, UUID o1,
			Persona otherStudent, Persona otherGuardian, UUID schoolB, Persona adminB, Persona studentB, UUID leadId) {

		public String api() {
			return "/api/v1/schools/" + this.schoolId;
		}

		public String items() {
			return api() + "/portfolio/items";
		}

		public String item(Object id) {
			return items() + "/" + id;
		}

		public String classApi() {
			return api() + "/classes/" + this.classId;
		}

	}

	/** A new school on SCHOOL_BASIC: portfolios are part of that plan and up (not of SCHOOL_FREE, which a school without a subscription gets). */
	protected UUID basicSchool(String label) {
		UUID school = this.personas.newSchool(label);
		this.jdbc.update("insert into subscriptions (plan_id, subscriber_type, school_id, status) "
				+ "select id, 'SCHOOL', ?, 'ACTIVE' from subscription_plans where code = 'SCHOOL_BASIC'", school);
		return school;
	}

	protected Pf pf(String label) {
		UUID school = basicSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID lead = this.personas.newTeacher(school, label + "-lead");
		UUID co = this.personas.newTeacher(school, label + "-co");
		UUID maths = this.personas.newTeacher(school, label + "-maths");
		UUID art = this.personas.newTeacher(school, label + "-art");
		UUID assistant = this.personas.newTeacher(school, label + "-assistant");
		UUID classId = this.personas.newClass(school, "1A");
		UUID mathsId = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Maths', 'M')", mathsId, school);
		UUID artId = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Art', 'A')", artId, school);
		this.personas.addTeacherToClass(school, classId, lead, "LEAD", null);
		this.personas.addTeacherToClass(school, classId, co, "CO_TEACHER", null);
		this.personas.addTeacherToClass(school, classId, maths, "SUBJECT_TEACHER", mathsId);
		this.personas.addTeacherToClass(school, classId, art, "SUBJECT_TEACHER", artId);
		this.personas.addTeacherToClass(school, classId, assistant, "ASSISTANT", null);
		UUID s1 = this.personas.newStudent(school, "Abel");
		UUID s2 = this.personas.newStudent(school, "Bethlehem");
		UUID s3 = this.personas.newStudent(school, "Chala");
		for (UUID s : List.of(s1, s2, s3)) {
			this.personas.addStudentToClass(school, classId, s);
		}
		Persona g1 = this.personas.newUser(label + "-guardian1");
		this.personas.linkGuardian(s1, g1.userId(), "ACTIVE", false);
		Persona g2 = this.personas.newUser(label + "-guardian2");
		this.personas.linkGuardian(s2, g2.userId(), "ACTIVE", false);
		Persona restricted = this.personas.newUser(label + "-restricted");
		this.personas.linkGuardian(s1, restricted.userId(), "ACTIVE", true);
		Persona revoked = this.personas.newUser(label + "-revoked");
		this.personas.linkGuardian(s1, revoked.userId(), "REVOKED", false);
		Persona pending = this.personas.newUser(label + "-pending");
		this.personas.linkGuardian(s1, pending.userId(), "PENDING", false);
		UUID former = this.personas.newStudent(school, "Fikir");
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", Ids.newId(), classId, school, former);
		UUID withdrawn = this.personas.newStudent(school, "Wolde");
		this.personas.addStudentToClass(school, classId, withdrawn);
		UUID otherClass = this.personas.newClass(school, "2B");
		UUID otherTeacher = this.personas.newTeacher(school, label + "-other");
		this.personas.addTeacherToClass(school, otherClass, otherTeacher, "LEAD", null);
		UUID o1 = this.personas.newStudent(school, "Dawit");
		this.personas.addStudentToClass(school, otherClass, o1);
		Persona og = this.personas.newUser(label + "-other-guardian");
		this.personas.linkGuardian(o1, og.userId(), "ACTIVE", false);
		UUID schoolB = basicSchool(label + "-B");
		Persona adminB = this.personas.schoolAdmin(schoolB, label + "-adminB");
		UUID bStudent = this.personas.newStudent(schoolB, "Eden");
		UUID classB = this.personas.newClass(schoolB, "9B");
		this.personas.addStudentToClass(schoolB, classB, bStudent);
		// the withdrawn student leaves the school only now, so that the fixture above stayed simple
		this.jdbc.update("update school_students set status = 'WITHDRAWN', left_on = current_date where student_id = ?", withdrawn);
		this.jdbc.update("update class_students set status = 'REMOVED', left_at = now(), left_reason = 'LEFT_SCHOOL' where student_id = ?", withdrawn);
		return new Pf(school, admin, this.personas.personaFor(label + "-lead", lead), this.personas.personaFor(label + "-co", co),
				this.personas.personaFor(label + "-maths", maths), this.personas.personaFor(label + "-art", art),
				this.personas.personaFor(label + "-assistant", assistant), classId, mathsId, artId, s1,
				this.personas.personaFor("abel", s1), s2, this.personas.personaFor("bethlehem", s2), s3,
				this.personas.personaFor("chala", s3), g1, g2, restricted, revoked, pending, this.personas.personaFor("fikir", former),
				this.personas.personaFor("wolde", withdrawn), otherClass, this.personas.personaFor(label + "-other", otherTeacher), o1,
				this.personas.personaFor("dawit", o1), og, schoolB, adminB, this.personas.personaFor("eden", bStudent), lead);
	}

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value), this.personas.platformAdmin().userId());
	}

	protected void cap(Pf p, String visibility) {
		setting(p.schoolId(), "portfolio.max_visibility", visibility);
	}

	// --- consent -----------------------------------------------------------------------------

	protected UUID mediaPolicyVersion() {
		return this.jdbc.queryForObject("""
				select v.id from policy_versions v join policy_documents d on d.id = v.policy_document_id
				where d.policy_type = 'MEDIA_PUBLICATION' and d.country_code = 'ET' and d.school_id is null
				  and v.language_code = 'en' and v.version_label = '2026-11-draft'""", UUID.class);
	}

	protected void decide(Persona guardian, UUID studentId, String decision) throws Exception {
		call(HttpMethod.POST, "/api/v1/me/consents", guardian, Map.of("studentId", studentId, "consentType", "MEDIA_PUBLICATION",
				"policyVersionId", mediaPolicyVersion(), "decision", decision), 201);
	}

	protected void grant(Persona guardian, UUID studentId) throws Exception {
		decide(guardian, studentId, "GRANTED");
	}

	protected void withdraw(Persona guardian, UUID studentId) throws Exception {
		decide(guardian, studentId, "WITHDRAWN");
	}

	// --- uploads -----------------------------------------------------------------------------

	/** A READY photo uploaded as PORTFOLIO media of the school by {@code who}; returns its media id. */
	protected UUID photo(Persona who, UUID schoolId) throws Exception {
		return UUID.fromString(uploadReady(who, "PORTFOLIO", "photo.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), school(schoolId))
			.get("id")
			.asString());
	}

	protected UUID pdf(Persona who, UUID schoolId) throws Exception {
		return UUID.fromString(uploadReady(who, "PORTFOLIO", "work.pdf", "application/pdf", MediaFixtures.pdf(1), school(schoolId))
			.get("id")
			.asString());
	}

	// --- calls -------------------------------------------------------------------------------

	protected ResultActions perform(HttpMethod method, String url, Persona as, Object body) throws Exception {
		MockHttpServletRequestBuilder builder = request(method, url);
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
		}
		if (as != null) {
			builder = builder.with(as.bearer());
		}
		return this.mvc.perform(builder);
	}

	/** Calls and asserts the status; returns the parsed body (null when empty). */
	protected JsonNode call(HttpMethod method, String url, Persona as, Object body, int status) throws Exception {
		var response = perform(method, url, as, body).andReturn().getResponse();
		assertThat(response.getStatus()).as(method + " " + url + " -> " + response.getContentAsString()).isEqualTo(status);
		String content = response.getContentAsString();
		return content.isBlank() ? null : this.json.readTree(content);
	}

	protected JsonNode get(String url, Persona as) throws Exception {
		return call(HttpMethod.GET, url, as, null, 200);
	}

	protected JsonNode post(String url, Persona as, Object body, int status) throws Exception {
		return call(HttpMethod.POST, url, as, body, status);
	}

	protected int statusOf(HttpMethod method, String url, Persona as, Object body) throws Exception {
		return perform(method, url, as, body).andReturn().getResponse().getStatus();
	}

	protected String code(JsonNode problem) {
		return problem.get("code").asString();
	}

	protected static Map<String, Object> body(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

	// --- items -------------------------------------------------------------------------------

	/** The body of a student's PHOTO item with the given files; {@code extra} entries are added. */
	protected Map<String, Object> photoBody(String title, List<UUID> media, Object... extra) {
		Map<String, Object> map = body("itemType", "PHOTO", "title", title, "mediaIds", media);
		for (int i = 0; i < extra.length; i += 2) {
			map.put((String) extra[i], extra[i + 1]);
		}
		return map;
	}

	protected Map<String, Object> textBody(String title, String text, Object... extra) {
		Map<String, Object> map = body("itemType", "TEXT", "title", title, "textContent", text);
		for (int i = 0; i < extra.length; i += 2) {
			map.put((String) extra[i], extra[i + 1]);
		}
		return map;
	}

	protected JsonNode create(Persona student, Pf p, Map<String, Object> body, int status) throws Exception {
		return post(p.items(), student, body, status);
	}

	/** A text item of the student, saved as DRAFT; returns its id. */
	protected UUID draft(Pf p, Persona student, String title) throws Exception {
		return id(create(student, p, textBody(title, "text of " + title), 201));
	}

	protected UUID submitted(Pf p, Persona student, String title) throws Exception {
		return id(create(student, p, textBody(title, "text of " + title, "mode", "SUBMIT"), 201));
	}

	/** A text item that went all the way to APPROVED with the given visibility (the school's cap is the caller's business). */
	protected UUID approved(Pf p, Persona student, String title, String visibility) throws Exception {
		UUID id = id(create(student, p, textBody(title, "text of " + title, "mode", "SUBMIT", "visibility", visibility), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		return id;
	}

	/** A photo item of the student, submitted and approved by the lead (consent must exist when the audience needs it). */
	protected UUID approvedPhoto(Pf p, Persona student, String title, String visibility) throws Exception {
		UUID media = photo(student, p.schoolId());
		UUID id = id(create(student, p, photoBody(title, List.of(media), "mode", "SUBMIT", "visibility", visibility,
				"confirmNoUnconsentedStudents", true), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		return id;
	}

	protected UUID mediaOf(JsonNode item) {
		return UUID.fromString(item.get("media").get(0).get("mediaId").asString());
	}

	protected static UUID id(JsonNode node) {
		return UUID.fromString(node.get("id").asString());
	}

	protected static List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.get("items").forEach((item) -> ids.add(item.get("id").asString()));
		return ids;
	}

	protected int mediaUrlStatus(Persona who, Object mediaId) throws Exception {
		return perform(HttpMethod.GET, "/api/v1/media/" + mediaId + "/url?variant=SMALL", who, null).andReturn().getResponse().getStatus();
	}

	protected String status(UUID itemId) {
		return this.jdbc.queryForObject("select status from portfolio_items where id = ?", String.class, itemId);
	}

	protected void awaitNotifications(UUID user, String type, int expected) {
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(notificationCount(user, type)).isEqualTo(expected));
	}

	protected int notificationCount(UUID user, String type) {
		return this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = ?", Integer.class, user, type);
	}

	protected UUID newId() {
		return Ids.newId();
	}

	/** Inserts an item directly (for matrices): the student's portfolio is created when missing; returns the item id. */
	protected UUID insertItem(Pf p, UUID studentId, String status, String visibility, UUID classId, UUID subjectId) {
		UUID portfolio = this.jdbc.query("select id from portfolios where student_id = ? and school_id = ?",
				(rs, i) -> rs.getObject(1, UUID.class), studentId, p.schoolId()).stream().findFirst().orElse(null);
		if (portfolio == null) {
			portfolio = Ids.newId();
			this.jdbc.update("insert into portfolios (id, student_id, school_id) values (?, ?, ?)", portfolio, studentId, p.schoolId());
		}
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into portfolio_items (id, portfolio_id, school_id, student_id, class_id, subject_id, created_by, item_type, title,
				  text_content, work_date, status, visibility, submitted_at)
				values (?, ?, ?, ?, ?, ?, ?, 'TEXT', ?, 'text', current_date, ?, ?, case when ? = 'DRAFT' then null else now() end)""", id,
				portfolio, p.schoolId(), studentId, classId, subjectId, studentId, "Matrix " + status + " " + visibility, status,
				visibility, status);
		return id;
	}

}
