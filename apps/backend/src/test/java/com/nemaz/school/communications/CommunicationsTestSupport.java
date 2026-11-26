package com.nemaz.school.communications;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.communications.application.AnnouncementDelivery;
import com.nemaz.school.communications.application.AnnouncementMaintenance;
import com.nemaz.school.notifications.application.DeliveryRetryJob;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.RecordingPushSender;
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
 * A school with classes, grade levels, campuses, teachers, students and every kind of guardian link, and the calls of the
 * announcements API. Delivery runs asynchronously after commit (like in production), so tests wait for
 * {@code delivery_completed_at}.
 */
abstract class CommunicationsTestSupport extends AuthTestSupport {

	@Autowired
	protected SettingsService settings;

	@Autowired
	protected AnnouncementMaintenance maintenance;

	@Autowired
	protected AnnouncementDelivery delivery;

	@Autowired
	protected RecordingPushSender pushes;

	@Autowired
	protected DeliveryRetryJob retryJob;

	@AfterEach
	void resetPushes() {
		this.pushes.reset();
	}

	/**
	 * School "label": grade level G, campuses C1 and C2; class 1A (G, C1) with lead, co, subject and assistant teachers and
	 * students a1, a2, a3 (a3's guardian has notifications switched off), a former student f1; class 1B (G, C1) with its
	 * lead and student b1; class 2A (no grade, C2) with its lead and student x1. Guardians: gA1 (a1), gSib (a1 and a2: a
	 * parent of two children in the class), gA3 (a3, can_receive_notifications off), gPending, gRestricted and gRevoked (all
	 * of a1), gB1 (b1), gX (x1). Everybody has a verified phone (guardians and teachers).
	 */
	protected record Comms(UUID schoolId, Persona admin, UUID grade, UUID campus1, UUID campus2, UUID class1A, UUID class1B,
			UUID class2A, Persona lead1A, Persona co1A, Persona subject1A, Persona assistant1A, Persona lead1B,
			Persona lead2A, UUID a1, UUID a2, UUID a3, UUID f1, UUID b1, UUID x1, Persona student1, Persona gA1,
			Persona gSib, Persona gA3, Persona gPending, Persona gRestricted, Persona gRevoked, Persona gB1, Persona gX) {

		String api() {
			return "/api/v1/schools/" + this.schoolId + "/announcements";
		}

		/** Every person who may hold guardian notifications of the class 1A audience. */
		Set<UUID> guardiansOf1A() {
			return Set.of(this.gA1.userId(), this.gSib.userId());
		}

	}

	protected Comms comms(String label) {
		UUID school = this.personas.newSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID grade = Ids.newId();
		this.jdbc.update("insert into grade_levels (id, school_id, name, code, sequence) values (?, ?, 'Grade 1', '1', 1)", grade,
				school);
		UUID campus1 = campus(school, "C1");
		UUID campus2 = campus(school, "C2");
		UUID class1A = this.personas.newClass(school, "1A");
		UUID class1B = this.personas.newClass(school, "1B");
		UUID class2A = this.personas.newClass(school, "2A");
		this.jdbc.update("update classes set grade_level_id = ?, campus_id = ? where id in (?, ?)", grade, campus1, class1A,
				class1B);
		this.jdbc.update("update classes set campus_id = ? where id = ?", campus2, class2A);
		UUID lead = this.personas.newTeacher(school, label + "-lead1A");
		UUID co = this.personas.newTeacher(school, label + "-co1A");
		UUID subject = this.personas.newTeacher(school, label + "-subject1A");
		UUID assistant = this.personas.newTeacher(school, label + "-assistant1A");
		UUID subjectId = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Maths', 'M')", subjectId, school);
		this.personas.addTeacherToClass(school, class1A, lead, "LEAD", null);
		this.personas.addTeacherToClass(school, class1A, co, "CO_TEACHER", null);
		this.personas.addTeacherToClass(school, class1A, subject, "SUBJECT_TEACHER", subjectId);
		this.personas.addTeacherToClass(school, class1A, assistant, "ASSISTANT", null);
		UUID lead1B = this.personas.newTeacher(school, label + "-lead1B");
		this.personas.addTeacherToClass(school, class1B, lead1B, "LEAD", null);
		UUID lead2A = this.personas.newTeacher(school, label + "-lead2A");
		this.personas.addTeacherToClass(school, class2A, lead2A, "LEAD", null);
		UUID a1 = student(school, class1A, "Abel");
		UUID a2 = student(school, class1A, "Bethlehem");
		UUID a3 = student(school, class1A, "Chala");
		UUID f1 = this.personas.newStudent(school, "Former");
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", Ids.newId(), class1A, school, f1);
		UUID b1 = student(school, class1B, "Dawit");
		UUID x1 = student(school, class2A, "Eden");
		Persona gA1 = guardian(label + "-gA1", a1, "ACTIVE", false);
		Persona gSib = guardian(label + "-gSib", a1, "ACTIVE", false);
		this.personas.linkGuardian(a2, gSib.userId(), "ACTIVE", false);
		Persona gA3 = guardian(label + "-gA3", a3, "ACTIVE", false);
		this.jdbc.update("update student_guardians set can_receive_notifications = false where guardian_id = ?", gA3.userId());
		Persona gPending = guardian(label + "-gPending", a1, "PENDING", false);
		Persona gRestricted = guardian(label + "-gRestricted", a1, "ACTIVE", true);
		Persona gRevoked = guardian(label + "-gRevoked", a1, "REVOKED", false);
		Persona gB1 = guardian(label + "-gB1", b1, "ACTIVE", false);
		Persona gX = guardian(label + "-gX", x1, "ACTIVE", false);
		return new Comms(school, admin, grade, campus1, campus2, class1A, class1B, class2A,
				this.personas.personaFor(label + "-lead1A", lead), this.personas.personaFor(label + "-co1A", co),
				this.personas.personaFor(label + "-subject1A", subject),
				this.personas.personaFor(label + "-assistant1A", assistant), this.personas.personaFor(label + "-lead1B", lead1B),
				this.personas.personaFor(label + "-lead2A", lead2A), a1, a2, a3, f1, b1, x1,
				this.personas.personaFor(label + "-abel", a1), gA1, gSib, gA3, gPending, gRestricted, gRevoked, gB1, gX);
	}

	private UUID campus(UUID school, String code) {
		UUID id = Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, code) values (?, ?, ?, ?)", id, school, "Campus " + code,
				code);
		return id;
	}

	private UUID student(UUID school, UUID classId, String name) {
		UUID id = this.personas.newStudent(school, name);
		this.personas.addStudentToClass(school, classId, id);
		return id;
	}

	private Persona guardian(String label, UUID student, String status, boolean restricted) {
		Persona persona = this.personas.newUser(label);
		this.personas.linkGuardian(student, persona.userId(), status, restricted);
		return persona;
	}

	// --- settings --------------------------------------------------------------------------

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value),
				this.personas.platformAdmin().userId());
	}

	// --- audiences and bodies --------------------------------------------------------------

	protected static Map<String, Object> classAudience(UUID classId, String... roles) {
		return Map.of("type", "CLASS", "classId", classId.toString(), "roles", List.of(roles.length == 0 ? new String[] { "GUARDIAN" } : roles));
	}

	protected static Map<String, Object> schoolAudience(String... roles) {
		return Map.of("type", "SCHOOL", "roles", List.of(roles.length == 0 ? new String[] { "GUARDIAN" } : roles));
	}

	protected static Map<String, Object> gradeAudience(UUID grade, String... roles) {
		return Map.of("type", "GRADE_LEVEL", "gradeLevelId", grade.toString(), "roles",
				List.of(roles.length == 0 ? new String[] { "GUARDIAN" } : roles));
	}

	protected static Map<String, Object> campusAudience(UUID campus, String... roles) {
		return Map.of("type", "CAMPUS", "campusId", campus.toString(), "roles",
				List.of(roles.length == 0 ? new String[] { "GUARDIAN" } : roles));
	}

	protected static Map<String, Object> userAudience(UUID... users) {
		return Map.of("type", "USER", "userIds", Set.of(users).stream().map(UUID::toString).toList());
	}

	protected static Map<String, Object> announcement(String title, String body, Map<String, Object>... audiences) {
		Map<String, Object> request = new java.util.LinkedHashMap<>();
		request.put("title", title);
		request.put("body", body);
		request.put("audiences", List.of(audiences));
		return request;
	}

	@SafeVarargs
	protected final Map<String, Object> with(Map<String, Object> request, Object... keyValues) {
		Map<String, Object> copy = new java.util.LinkedHashMap<>(request);
		for (int i = 0; i < keyValues.length; i += 2) {
			copy.put((String) keyValues[i], keyValues[i + 1]);
		}
		return copy;
	}

	/** Composes and publishes (as {@code as}), returns the announcement id. */
	@SafeVarargs
	protected final UUID publishNow(Comms c, Persona as, String title, Map<String, Object>... audiences) throws Exception {
		JsonNode created = call(HttpMethod.POST, c.api(), as,
				with(announcement(title, "Body of " + title, audiences), "mode", "PUBLISH"), 201);
		return UUID.fromString(created.get("id").asString());
	}

	/** Waits until the chunks of the announcement have all been delivered (the listener runs after commit). */
	protected void awaitDelivered(UUID announcementId) {
		await().atMost(Duration.ofSeconds(20))
			.until(() -> Boolean.TRUE.equals(this.jdbc.queryForObject(
					"select delivery_completed_at is not null from announcements where id = ?", Boolean.class, announcementId)));
	}

	protected Set<UUID> recipients(UUID announcementId) {
		return new LinkedHashSet<>(this.jdbc.queryForList("select user_id from announcement_recipients where announcement_id = ?",
				UUID.class, announcementId));
	}

	protected Set<UUID> ids(Persona... personas) {
		Set<UUID> set = new LinkedHashSet<>();
		for (Persona p : personas) {
			set.add(p.userId());
		}
		return set;
	}

	protected List<Map<String, Object>> inboxOf(UUID userId, String type) {
		return this.jdbc.queryForList("select * from notifications where user_id = ? and type_code = ? order by created_at",
				userId, type);
	}

	protected String phoneOf(Persona persona) {
		return this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, persona.userId());
	}

	/** Registers an active Expo push device of the user; returns its token. */
	protected String device(Persona persona) {
		String token = "ExponentPushToken[" + Ids.newId().toString().replace("-", "") + "]";
		this.jdbc.update("""
				insert into push_devices (user_id, platform, provider, push_token, last_seen_at)
				values (?, 'ANDROID', 'EXPO', ?, now())""", persona.userId(), token);
		return token;
	}

	/** Quiet hours around the current time of the Addis Ababa clock (the default timezone) for {@code persona}. */
	protected void quietNow(Persona persona) {
		this.jdbc.update("""
				insert into user_notification_settings (user_id, quiet_hours_start, quiet_hours_end)
				values (?, (now() at time zone 'Africa/Addis_Ababa' - interval '30 minutes')::time,
				        (now() at time zone 'Africa/Addis_Ababa' + interval '3 hours')::time)
				on conflict (user_id) do update set quiet_hours_start = excluded.quiet_hours_start,
				  quiet_hours_end = excluded.quiet_hours_end""", persona.userId());
	}

	protected void quietHoursOff(Persona persona) {
		this.jdbc.update("delete from user_notification_settings where user_id = ?", persona.userId());
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

	/** True when the field is missing or null (responses leave null fields out). */
	protected static boolean absent(JsonNode node, String field) {
		return node.path(field).isMissingNode() || node.path(field).isNull();
	}

	protected String code(JsonNode problem) {
		return problem.get("code").asString();
	}

	protected List<String> idsOf(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.get("items").forEach((item) -> ids.add(item.get("id").asString()));
		return ids;
	}

}
