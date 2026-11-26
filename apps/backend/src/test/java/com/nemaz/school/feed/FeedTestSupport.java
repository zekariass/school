package com.nemaz.school.feed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.feed.application.FeedMaintenance;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.MediaTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.RecordingTranslationProvider;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import org.junit.jupiter.api.AfterEach;
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
 * A school with a class and the people around it, and the calls of the feed API with real uploads (the media goes through
 * the presigned PUT to the test object store, like a client's).
 */
public abstract class FeedTestSupport extends MediaTestSupport {

	@Autowired
	protected SettingsService settings;

	@Autowired
	protected FeedMaintenance maintenance;

	@Autowired
	protected RecordingTranslationProvider translator;

	@AfterEach
	void resetTranslator() {
		this.translator.reset();
	}

	/**
	 * School "label" with class 1A: LEAD, CO_TEACHER, SUBJECT_TEACHER and ASSISTANT teachers, students Abel (s1, a student
	 * account persona), Bethlehem (s2) and Chala (s3); guardians of s1 (active, restricted, revoked), of s2; a second class
	 * 2B with its teacher, a student and a guardian; the school administrator.
	 */
	protected record Feed(UUID schoolId, Persona admin, Persona lead, Persona co, Persona subject, Persona assistant,
			UUID classId, UUID s1, Persona student1, UUID s2, Persona student2, UUID s3, Persona guardian1,
			Persona guardian2, Persona restricted, Persona revoked, UUID otherClassId, Persona otherTeacher, UUID o1,
			Persona otherStudent, Persona otherGuardian) {

		public String api() {
			return "/api/v1/schools/" + this.schoolId;
		}

		public String classApi() {
			return api() + "/classes/" + this.classId;
		}

	}

	protected Feed feed(String label) {
		UUID school = this.personas.newSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID lead = this.personas.newTeacher(school, label + "-lead");
		UUID co = this.personas.newTeacher(school, label + "-co");
		UUID subject = this.personas.newTeacher(school, label + "-subject");
		UUID assistant = this.personas.newTeacher(school, label + "-assistant");
		UUID classId = this.personas.newClass(school, "1A");
		this.personas.addTeacherToClass(school, classId, lead, "LEAD", null);
		this.personas.addTeacherToClass(school, classId, co, "CO_TEACHER", null);
		UUID subjectId = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Maths', 'M')", subjectId, school);
		this.personas.addTeacherToClass(school, classId, subject, "SUBJECT_TEACHER", subjectId);
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
		UUID otherClass = this.personas.newClass(school, "2B");
		UUID otherTeacher = this.personas.newTeacher(school, label + "-other");
		this.personas.addTeacherToClass(school, otherClass, otherTeacher, "LEAD", null);
		UUID o1 = this.personas.newStudent(school, "Dawit");
		this.personas.addStudentToClass(school, otherClass, o1);
		Persona og = this.personas.newUser(label + "-other-guardian");
		this.personas.linkGuardian(o1, og.userId(), "ACTIVE", false);
		return new Feed(school, admin, this.personas.personaFor(label + "-lead", lead),
				this.personas.personaFor(label + "-co", co), this.personas.personaFor(label + "-subject", subject),
				this.personas.personaFor(label + "-assistant", assistant), classId, s1,
				this.personas.personaFor("abel", s1), s2, this.personas.personaFor("bethlehem", s2), s3, g1, g2, restricted,
				revoked, otherClass, this.personas.personaFor(label + "-other", otherTeacher), o1,
				this.personas.personaFor("dawit", o1), og);
	}

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value),
				this.personas.platformAdmin().userId());
	}

	// --- consent ---------------------------------------------------------------------------

	protected UUID mediaPolicyVersion() {
		return this.jdbc.queryForObject("""
				select v.id from policy_versions v join policy_documents d on d.id = v.policy_document_id
				where d.policy_type = 'MEDIA_PUBLICATION' and d.country_code = 'ET' and d.school_id is null
				  and v.language_code = 'en' and v.version_label = '2026-11-draft'""", UUID.class);
	}

	protected void decide(Persona guardian, UUID studentId, String decision) throws Exception {
		call(HttpMethod.POST, "/api/v1/me/consents", guardian, Map.of("studentId", studentId, "consentType",
				"MEDIA_PUBLICATION", "policyVersionId", mediaPolicyVersion(), "decision", decision), 201);
	}

	protected void grant(Persona guardian, UUID studentId) throws Exception {
		decide(guardian, studentId, "GRANTED");
	}

	// --- uploads ---------------------------------------------------------------------------

	protected static Map<String, Object> classContext(UUID classId) {
		return Map.of("type", "CLASS", "id", classId.toString());
	}

	/** A READY photo uploaded as POST media of the class by {@code who}; returns its media id. */
	protected UUID photo(Persona who, UUID classId) throws Exception {
		return UUID.fromString(uploadReady(who, "POST", "photo.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48),
				classContext(classId)).get("id").asString());
	}

	protected UUID schoolPhoto(Persona who, UUID schoolId) throws Exception {
		return UUID.fromString(uploadReady(who, "POST", "photo.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48),
				school(schoolId)).get("id").asString());
	}

	protected UUID storyPhoto(Persona who, UUID classId) throws Exception {
		return UUID.fromString(uploadReady(who, "STORY", "story.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48),
				classContext(classId)).get("id").asString());
	}

	protected static List<Map<String, Object>> refs(UUID... mediaIds) {
		List<Map<String, Object>> refs = new ArrayList<>();
		for (UUID id : mediaIds) {
			refs.add(Map.of("mediaId", id.toString()));
		}
		return refs;
	}

	// --- calls -----------------------------------------------------------------------------

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

	/** A class post by {@code who}; {@code extra} entries are added to the body. */
	protected JsonNode classPost(Persona who, Feed f, String text, int status, Map<String, Object> extra) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		if (text != null) {
			body.put("body", text);
		}
		body.putAll(extra);
		return post(f.classApi() + "/posts", who, body, status);
	}

	protected JsonNode classPost(Persona who, Feed f, String text) throws Exception {
		return classPost(who, f, text, 201, Map.of());
	}

	/** A published class post with one photo of the given tagged students (consent must exist). */
	protected JsonNode photoPost(Persona who, Feed f, String text, UUID... tags) throws Exception {
		UUID media = photo(who, f.classId());
		List<String> tagIds = new ArrayList<>();
		for (UUID tag : tags) {
			tagIds.add(tag.toString());
		}
		return classPost(who, f, text, 201, Map.of("media", refs(media), "studentIds", tagIds));
	}

	protected static List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.get("items").forEach((item) -> ids.add(item.get("id").asString()));
		return ids;
	}

	protected JsonNode feedOf(Persona who, String url) throws Exception {
		return get(url, who);
	}

	protected int mediaUrlStatus(Persona who, String mediaId) throws Exception {
		return perform(HttpMethod.GET, "/api/v1/media/" + mediaId + "/url?variant=SMALL", who, null).andReturn()
			.getResponse()
			.getStatus();
	}

	protected void awaitNotifications(UUID user, String type, int expected) {
		await().atMost(Duration.ofSeconds(20))
			.untilAsserted(() -> assertThat(notificationCount(user, type)).isEqualTo(expected));
	}

	protected int notificationCount(UUID user, String type) {
		return this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = ?",
				Integer.class, user, type);
	}

	protected UUID newId() {
		return Ids.newId();
	}

}
