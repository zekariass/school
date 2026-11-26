package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.notifications.application.DeliveryRetryJob;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.MediaTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.RecordingPushSender;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * A school with a class and the people around it, and the calls of the messaging API. Every call goes through the real HTTP
 * layer as a persona with a real token, so the live eligibility of the policy engine is part of every assertion.
 */
public abstract class MessagingTestSupport extends MediaTestSupport {

	@Autowired
	protected SettingsService settings;

	@Autowired
	protected RecordingPushSender pushes;

	@Autowired
	protected DeliveryRetryJob retryJob;

	@AfterEach
	void resetPushes() {
		this.pushes.reset();
	}

	/**
	 * School {@code label}: class 1A with its LEAD ({@code lead}), CO_TEACHER, SUBJECT_TEACHER and ASSISTANT, students Abel
	 * ({@code s1}), Bethlehem ({@code s2}) and Chala ({@code s3}); guardians of Abel: {@code g1} (ACTIVE, mother), {@code g1b}
	 * (ACTIVE), {@code restricted} (custody-restricted), {@code revoked} (REVOKED), {@code pending}; a guardian of Bethlehem
	 * ({@code g2}); class 2B with its teacher, student Dawit and his guardian; a non-teaching staff member; the school's
	 * administrator, an administrator of another school and an unrelated adult.
	 */
	protected record Msg(UUID schoolId, Persona admin, Persona lead, Persona co, Persona subject, Persona assistant,
			Persona otherTeacher, Persona staff, UUID classId, UUID otherClassId, UUID s1, Persona student1, UUID s2,
			Persona student2, UUID s3, Persona student3, UUID o1, Persona otherStudent, Persona g1, UUID g1Link, Persona g1b,
			Persona g2, Persona restricted, Persona revoked, Persona pending, Persona otherGuardian, Persona stranger,
			Persona otherAdmin) {

		public String api() {
			return "/api/v1/schools/" + this.schoolId + "/messaging";
		}

	}

	protected Msg school(String label) {
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
		UUID link = this.personas.linkGuardian(s1, g1.userId(), "ACTIVE", false);
		Persona g1b = this.personas.newUser(label + "-guardian1b");
		this.personas.linkGuardian(s1, g1b.userId(), "ACTIVE", false);
		Persona g2 = this.personas.newUser(label + "-guardian2");
		this.personas.linkGuardian(s2, g2.userId(), "ACTIVE", false);
		Persona restricted = this.personas.newUser(label + "-restricted");
		this.personas.linkGuardian(s1, restricted.userId(), "ACTIVE", true);
		Persona revoked = this.personas.newUser(label + "-revoked");
		this.personas.linkGuardian(s1, revoked.userId(), "REVOKED", false);
		Persona pending = this.personas.newUser(label + "-pending");
		this.personas.linkGuardian(s1, pending.userId(), "PENDING", false);
		UUID otherClass = this.personas.newClass(school, "2B");
		UUID otherTeacher = this.personas.newTeacher(school, label + "-other");
		this.personas.addTeacherToClass(school, otherClass, otherTeacher, "LEAD", null);
		UUID o1 = this.personas.newStudent(school, "Dawit");
		this.personas.addStudentToClass(school, otherClass, o1);
		Persona og = this.personas.newUser(label + "-other-guardian");
		this.personas.linkGuardian(o1, og.userId(), "ACTIVE", false);
		UUID staff = this.personas.newStaff(school, label + "-staff", "SUPPORT_STAFF");
		UUID otherSchool = this.personas.newSchool(label + "-elsewhere");
		return new Msg(school, admin, this.personas.personaFor(label + "-lead", lead),
				this.personas.personaFor(label + "-co", co), this.personas.personaFor(label + "-subject", subject),
				this.personas.personaFor(label + "-assistant", assistant),
				this.personas.personaFor(label + "-other", otherTeacher), this.personas.personaFor(label + "-staff", staff),
				classId, otherClass, s1, this.personas.personaFor("abel", s1), s2, this.personas.personaFor("bethlehem", s2),
				s3, this.personas.personaFor("chala", s3), o1, this.personas.personaFor("dawit", o1), g1, link, g1b, g2,
				restricted, revoked, pending, og, this.personas.newUser(label + "-stranger"),
				this.personas.schoolAdmin(otherSchool, label + "-other-admin"));
	}

	protected void setting(UUID schoolId, String key, Object value) {
		if (Personas.needsLead(key, value)) {
			this.personas.ensureSafeguardingLead(schoolId); // a school cannot switch on student features without a lead
		}
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value), this.personas.platformAdmin().userId());
	}

	/** Turns on teacher-student direct messages (the setting that is off by default). */
	protected void allowTeacherStudentDirect(UUID schoolId, String initiator) {
		setting(schoolId, "messaging.student.teacher_direct.enabled", true);
		setting(schoolId, "messaging.student.teacher_direct.initiator", initiator);
	}

	// --- calls -----------------------------------------------------------------------------------------------------

	protected ResultActions perform(HttpMethod method, String url, Persona as, Object body) throws Exception {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, url);
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

	// --- conversations -----------------------------------------------------------------------------------------------

	/** Starts a DIRECT conversation and expects {@code status}; returns the body. */
	protected JsonNode startDirect(Persona caller, Msg m, UUID recipientUserId, UUID studentId, int status) throws Exception {
		return post(m.api() + "/conversations/direct", caller,
				body("recipientUserId", recipientUserId, "studentId", studentId), status);
	}

	protected UUID conversationId(JsonNode body) {
		return UUID.fromString(body.get("summary").get("id").asString());
	}

	/** A guardian-teacher conversation about Abel between {@code g1} and the lead teacher. */
	protected UUID parentTeacherThread(Msg m) throws Exception {
		return conversationId(startDirect(m.g1(), m, m.lead().userId(), m.s1(), 201));
	}

	/** The safeguarded conversation between the lead teacher and Abel (the setting is switched on first). */
	protected UUID teacherStudentThread(Msg m) throws Exception {
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		return conversationId(startDirect(m.lead(), m, m.s1(), null, 201));
	}

	/** The class conversation (students and teachers) of 1A, created by the lead teacher. */
	protected UUID classConversation(Msg m) throws Exception {
		JsonNode created = post(m.api() + "/groups", m.lead(), body("kind", "CLASS_STUDENTS", "classId", m.classId()), 201);
		return UUID.fromString(created.get("id").asString());
	}

	/** The class parent group (guardians and teachers) of 1A, created by the lead teacher. */
	protected UUID parentGroup(Msg m) throws Exception {
		JsonNode created = post(m.api() + "/groups", m.lead(), body("kind", "CLASS_PARENTS", "classId", m.classId()), 201);
		return UUID.fromString(created.get("id").asString());
	}

	protected static String conv(UUID id) {
		return "/api/v1/conversations/" + id;
	}

	// --- messages ----------------------------------------------------------------------------------------------------

	/** Sends a text message with a fresh client id and expects {@code status}. */
	protected JsonNode send(Persona who, UUID conversationId, String text, int status) throws Exception {
		return post(conv(conversationId) + "/messages", who, body("clientMessageId", Ids.newId(), "body", text), status);
	}

	protected UUID send(Persona who, UUID conversationId, String text) throws Exception {
		return UUID.fromString(send(who, conversationId, text, 201).get("message").get("id").asString());
	}

	protected JsonNode messagesOf(Persona who, UUID conversationId) throws Exception {
		return get(conv(conversationId) + "/messages", who);
	}

	protected List<String> bodies(JsonNode page) {
		List<String> bodies = new ArrayList<>();
		page.get("items").forEach((m) -> bodies.add(m.has("body") && !m.get("body").isNull() ? m.get("body").asString() : null));
		return bodies;
	}

	protected JsonNode inbox(Persona who) throws Exception {
		return get("/api/v1/me/conversations", who);
	}

	protected List<UUID> inboxIds(Persona who) throws Exception {
		List<UUID> ids = new ArrayList<>();
		inbox(who).get("items").forEach((c) -> ids.add(UUID.fromString(c.get("id").asString())));
		return ids;
	}

	protected int unread(Persona who, UUID conversationId) throws Exception {
		for (JsonNode c : inbox(who).get("items")) {
			if (c.get("id").asString().equals(conversationId.toString())) {
				return c.get("unreadCount").asInt();
			}
		}
		return -1;
	}

	// --- people ------------------------------------------------------------------------------------------------------

	/** The member id of {@code who} in the conversation, as {@code viewer} sees the members. */
	protected UUID memberIdOf(Persona viewer, UUID conversationId, Persona who) throws Exception {
		JsonNode members = get(conv(conversationId) + "/members", viewer).get("members");
		// members are identified by member id only: find the one that is the person by looking at the table
		UUID memberId = this.jdbc.queryForObject(
				"select id from conversation_members where conversation_id = ? and user_id = ? order by left_at is null desc limit 1",
				UUID.class, conversationId, who.userId());
		assertThat(members.toString()).contains(memberId.toString());
		return memberId;
	}

	/** Live observers of a conversation (user ids), straight from the table. */
	protected List<UUID> observers(UUID conversationId) {
		return this.jdbc.queryForList(
				"select user_id from conversation_members where conversation_id = ? and member_role = 'OBSERVER' and left_at is null order by joined_at, id",
				UUID.class, conversationId);
	}

	protected boolean adminVisible(UUID conversationId) {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select admin_visible from conversations where id = ?", Boolean.class,
				conversationId));
	}

	// --- fixtures that change links and rosters directly (the API paths are tested elsewhere) --------------------------

	protected void restrict(UUID linkId) {
		this.jdbc.update("update student_guardians set custody_restricted = true, restriction_note = 'fixture' where id = ?", linkId);
	}

	protected void unrestrict(UUID linkId) {
		this.jdbc.update("update student_guardians set custody_restricted = false where id = ?", linkId);
	}

	protected void revoke(UUID linkId) {
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where id = ?", linkId);
	}

	protected UUID linkOf(Persona guardian, UUID studentId) {
		return this.jdbc.queryForObject("select id from student_guardians where guardian_id = ? and student_id = ? order by created_at desc limit 1",
				UUID.class, guardian.userId(), studentId);
	}

	protected void leaveClass(UUID classId, UUID studentId) {
		this.jdbc.update("update class_students set status = 'FORMER', left_at = now(), left_reason = 'TRANSFERRED_CLASS' where class_id = ? and student_id = ? and status = 'ACTIVE'",
				classId, studentId);
	}

	protected void withdraw(UUID schoolId, UUID studentId) {
		this.jdbc.update("update school_students set status = 'WITHDRAWN', left_on = current_date where school_id = ? and student_id = ? and status = 'ACTIVE'",
				schoolId, studentId);
	}

	// --- notifications -------------------------------------------------------------------------------------------------

	protected List<Map<String, Object>> notificationsOf(Persona who, String type) {
		return this.jdbc.queryForList("select id, title, body, data::text as data, source_type, source_id, read_at from notifications where user_id = ? and type_code = ? order by created_at",
				who.userId(), type);
	}

	protected void awaitNotification(Persona who, String type) {
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(
				() -> assertThat(notificationsOf(who, type)).isNotEmpty());
	}

	/** Registers an active Expo push device of the persona; returns its token. */
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

	/** Waits until a push of {@code token} was recorded (or not, with {@code count} 0, after a short wait). */
	protected void awaitPushes(String token, int count) {
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(
				() -> assertThat(this.pushes.sentTo(token)).hasSize(count));
	}

	// --- media ----------------------------------------------------------------------------------------------------

	protected static Map<String, Object> conversationContext(UUID conversationId) {
		return Map.of("type", "CONVERSATION", "id", conversationId.toString());
	}

	/** A READY image uploaded as MESSAGE media of the conversation by {@code who}. */
	protected UUID image(Persona who, UUID conversationId) throws Exception {
		return UUID.fromString(uploadReady(who, "MESSAGE", "photo.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48),
				conversationContext(conversationId)).get("id").asString());
	}

}
