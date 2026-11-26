package com.nemaz.school.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarMaintenance;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.AuthTestSupport;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * A school with campuses, a grade, classes, teachers, students and every kind of guardian link, plus the calls of the
 * calendar API. Everything is synchronous (notifications are written in the request's transaction), so tests assert right
 * after the call.
 */
abstract class CalendarTestSupport extends AuthTestSupport {

	/**
	 * "Now" in whole seconds. A raw Instant.now() has nanoseconds on Linux (CI) but microseconds on macOS, and Postgres keeps
	 * microseconds, so a value sent to the API and compared with what it returns can differ by a few hundred nanoseconds.
	 */
	protected static Instant now() {
		return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
	}


	@Autowired
	protected SettingsService settings;

	@Autowired
	protected CalendarMaintenance maintenance;

	/**
	 * School "label" (Africa/Addis_Ababa, calendar on): grade G, campuses C1 and C2; class 1A (G, C1) with lead, co, subject and
	 * assistant teachers and students a1, a2 (+ former student f1); class 1B (G, C1) with its lead and student b1; class 2A
	 * (no grade, C2) with its lead and student x1; a teacher without any class ({@code staff}). Guardians: gA1 (a1), gSib (a1
	 * and a2), gPending, gRestricted and gRevoked (all of a1), gB1 (b1), gX (x1). {@code outsider} has nothing to do with the
	 * school, {@code otherAdmin} runs another school.
	 */
	protected record Cal(UUID schoolId, Persona admin, UUID grade, UUID campus1, UUID campus2, UUID class1A, UUID class1B,
			UUID class2A, UUID leadId, Persona lead1A, Persona co1A, Persona subject1A, Persona assistant1A, Persona lead1B,
			Persona lead2A, Persona staff, UUID a1, UUID a2, UUID f1, UUID b1, UUID x1, Persona studentA1, Persona studentA2,
			Persona studentFormer, Persona studentB1, Persona studentX1, Persona gA1, Persona gSib, Persona gPending,
			Persona gRestricted, Persona gRevoked, Persona gB1, Persona gX, Persona outsider, Persona otherAdmin) {

		String schoolEvents() {
			return "/api/v1/schools/" + this.schoolId + "/events";
		}

	}

	protected Cal cal(String label) {
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
		UUID staff = this.personas.newTeacher(school, label + "-staff");
		// teachers belong to the campus of their class; the teacher without a class is school-wide
		this.jdbc.update("update school_staff set campus_id = ? where user_id in (?, ?, ?, ?, ?) and school_id = ?", campus1, lead, co,
				subject, assistant, lead1B, school);
		this.jdbc.update("update school_staff set campus_id = ? where user_id = ? and school_id = ?", campus2, lead2A, school);
		UUID a1 = student(school, class1A, "Abel");
		UUID a2 = student(school, class1A, "Bethlehem");
		UUID f1 = this.personas.newStudent(school, "Former");
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", Ids.newId(), class1A, school, f1);
		UUID b1 = student(school, class1B, "Dawit");
		UUID x1 = student(school, class2A, "Eden");
		this.jdbc.update("update school_students set campus_id = ? where student_id in (?, ?, ?)", campus1, a1, a2, b1);
		this.jdbc.update("update school_students set campus_id = ? where student_id = ?", campus2, x1);
		Persona gA1 = guardian(label + "-gA1", a1, "ACTIVE", false);
		Persona gSib = guardian(label + "-gSib", a1, "ACTIVE", false);
		this.personas.linkGuardian(a2, gSib.userId(), "ACTIVE", false);
		Persona gPending = guardian(label + "-gPending", a1, "PENDING", false);
		Persona gRestricted = guardian(label + "-gRestricted", a1, "ACTIVE", true);
		Persona gRevoked = guardian(label + "-gRevoked", a1, "REVOKED", false);
		Persona gB1 = guardian(label + "-gB1", b1, "ACTIVE", false);
		Persona gX = guardian(label + "-gX", x1, "ACTIVE", false);
		UUID otherSchool = this.personas.newSchool(label + "-other");
		return new Cal(school, admin, grade, campus1, campus2, class1A, class1B, class2A, lead,
				this.personas.personaFor(label + "-lead1A", lead), this.personas.personaFor(label + "-co1A", co),
				this.personas.personaFor(label + "-subject1A", subject),
				this.personas.personaFor(label + "-assistant1A", assistant), this.personas.personaFor(label + "-lead1B", lead1B),
				this.personas.personaFor(label + "-lead2A", lead2A), this.personas.personaFor(label + "-staff", staff), a1, a2, f1,
				b1, x1, this.personas.personaFor(label + "-abel", a1),
				this.personas.personaFor(label + "-bethlehem", a2), this.personas.personaFor(label + "-former", f1),
				this.personas.personaFor(label + "-dawit", b1), this.personas.personaFor(label + "-eden", x1), gA1, gSib,
				gPending, gRestricted, gRevoked, gB1, gX, this.personas.newUser(label + "-outsider"),
				this.personas.schoolAdmin(otherSchool, label + "-other-admin"));
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

	// --- settings -------------------------------------------------------------------------------

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value), this.personas.platformAdmin().userId());
	}

	// --- event bodies ---------------------------------------------------------------------------

	protected static Instant at(String iso) {
		return Instant.parse(iso);
	}

	/** A timed one-hour event body. */
	protected static Map<String, Object> timed(String title, String scope, Instant start) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		if (scope != null) {
			body.put("scope", scope);
		}
		body.put("startAt", start.toString());
		body.put("endAt", start.plus(Duration.ofHours(1)).toString());
		return body;
	}

	protected static Map<String, Object> allDay(String title, String scope, String startDate, String endDate) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		if (scope != null) {
			body.put("scope", scope);
		}
		body.put("allDay", true);
		body.put("startDate", startDate);
		body.put("endDate", endDate);
		return body;
	}

	@SafeVarargs
	protected static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
		Map<String, Object> copy = new LinkedHashMap<>(body);
		for (int i = 0; i < keyValues.length; i += 2) {
			copy.put((String) keyValues[i], keyValues[i + 1]);
		}
		return copy;
	}

	/** Creates a school-scoped event as {@code as}; returns its id. */
	protected UUID create(Cal c, Persona as, Map<String, Object> body) throws Exception {
		return UUID.fromString(call(HttpMethod.POST, c.schoolEvents(), as, body, 201).get("id").asString());
	}

	/** A school event for the whole school at {@code start} (created by the admin). */
	protected UUID schoolEvent(Cal c, String title, Instant start) throws Exception {
		return create(c, c.admin(), timed(title, "SCHOOL", start));
	}

	protected UUID classEvent(Cal c, UUID classId, String title, Instant start) throws Exception {
		return create(c, c.admin(), with(timed(title, "CLASS", start), "classId", classId.toString()));
	}

	/** A parent meeting about {@code student} created by {@code teacher} one day from now; returns its id. */
	protected UUID meeting(Cal c, Persona teacher, UUID student) throws Exception {
		return create(c, teacher, meetingBody("Meeting", student, now().plus(Duration.ofDays(2))));
	}

	protected static Map<String, Object> meetingBody(String title, UUID student, Instant start) {
		Map<String, Object> body = timed(title, null, start);
		body.put("eventType", "PARENT_MEETING");
		body.put("regardingStudentId", student.toString());
		return body;
	}

	// --- calls ----------------------------------------------------------------------------------

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

	protected JsonNode call(HttpMethod method, String url, Persona as, Object body, int status) throws Exception {
		var response = perform(method, url, as, body).andReturn().getResponse();
		assertThat(response.getStatus()).as(method + " " + url + " -> " + response.getContentAsString()).isEqualTo(status);
		String content = response.getContentAsString();
		return content.isBlank() ? null : this.json.readTree(content);
	}

	protected JsonNode get(String url, Persona as) throws Exception {
		return call(HttpMethod.GET, url, as, null, 200);
	}

	protected int statusOf(HttpMethod method, String url, Persona as, Object body) throws Exception {
		return perform(method, url, as, body).andReturn().getResponse().getStatus();
	}

	protected String code(JsonNode problem) {
		return problem.get("code").asString();
	}

	/** The error code of a failing call. */
	protected String failure(HttpMethod method, String url, Persona as, Object body, int status) throws Exception {
		return code(call(method, url, as, body, status));
	}

	protected static String event(UUID id) {
		return "/api/v1/events/" + id;
	}

	// --- reading the calendar -------------------------------------------------------------------

	protected JsonNode calendar(Persona as, String from, String to, String extra) throws Exception {
		return get("/api/v1/calendar?from=" + from + "&to=" + to + ((extra == null) ? "" : "&" + extra), as);
	}

	protected List<JsonNode> items(JsonNode page) {
		List<JsonNode> items = new ArrayList<>();
		page.get("items").forEach(items::add);
		return items;
	}

	protected List<String> titles(JsonNode page) {
		return items(page).stream().map((i) -> i.get("title").asString()).toList();
	}

	protected List<String> titles(Persona as, String from, String to, String extra) throws Exception {
		return titles(calendar(as, from, to, extra));
	}

	/** Sets the clock to {@code instant} (reset after each test by the base class). */
	protected void clockAt(Instant instant) {
		this.clock.moveTo(instant);
	}

	protected List<Map<String, Object>> inbox(UUID userId, String type) {
		return this.jdbc.queryForList("select * from notifications where user_id = ? and type_code = ? order by created_at", userId,
				type);
	}

	protected int count(UUID userId, String type) {
		return inbox(userId, type).size();
	}

}
