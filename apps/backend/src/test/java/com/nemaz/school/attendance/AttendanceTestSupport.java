package com.nemaz.school.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * A school with a class (lead, subject teacher and assistant, three students, a guardian of the first and of the second),
 * another class with its own teacher, and the calls of the attendance API. The school sits in Addis Ababa (UTC+3, no
 * daylight saving); memberships are backdated so that any date of the last year is a day on which the students were in
 * the class.
 */
public abstract class AttendanceTestSupport extends SchoolApiTestSupport {

	protected static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

	@Autowired
	protected SettingsService settings;

	protected record Setup(UUID schoolId, Persona admin, Persona lead, UUID leadId, Persona subject, UUID subjectId,
			Persona assistant, UUID assistantId, UUID classId, UUID s1, Persona student1, UUID s2, UUID s3,
			Persona guardian1, Persona guardian2, Persona guardianOfS2, UUID otherClassId, Persona otherTeacher) {

		public String api() {
			return "/api/v1/schools/" + this.schoolId;
		}

		public String rollUrl() {
			return api() + "/classes/" + this.classId + "/attendance";
		}

	}

	protected Setup setup(String label) {
		UUID school = this.personas.newSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID lead = this.personas.newTeacher(school, label + "-lead");
		UUID subjectTeacher = this.personas.newTeacher(school, label + "-subject");
		UUID assistant = this.personas.newTeacher(school, label + "-assistant");
		UUID subjectId = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Maths', 'M')", subjectId, school);
		UUID classId = this.personas.newClass(school, "1A");
		this.personas.addTeacherToClass(school, classId, lead, "LEAD", null);
		this.personas.addTeacherToClass(school, classId, subjectTeacher, "SUBJECT_TEACHER", subjectId);
		this.personas.addTeacherToClass(school, classId, assistant, "ASSISTANT", null);
		UUID s1 = this.personas.newStudent(school, "Abel");
		UUID s2 = this.personas.newStudent(school, "Bethlehem");
		UUID s3 = this.personas.newStudent(school, "Chala");
		for (UUID s : List.of(s1, s2, s3)) {
			this.personas.addStudentToClass(school, classId, s);
		}
		backdateMemberships(classId);
		Persona guardian1 = this.personas.newUser(label + "-guardian1");
		this.personas.linkGuardian(s1, guardian1.userId(), "ACTIVE", false);
		Persona guardian2 = this.personas.newUser(label + "-guardian1b");
		this.personas.linkGuardian(s1, guardian2.userId(), "ACTIVE", false);
		Persona guardianOfS2 = this.personas.newUser(label + "-guardian2");
		this.personas.linkGuardian(s2, guardianOfS2.userId(), "ACTIVE", false);
		UUID otherClass = this.personas.newClass(school, "2B");
		UUID otherTeacher = this.personas.newTeacher(school, label + "-other");
		this.personas.addTeacherToClass(school, otherClass, otherTeacher, "LEAD", null);
		return new Setup(school, admin, this.personas.personaFor(label + "-lead", lead), lead,
				this.personas.personaFor(label + "-subject", subjectTeacher), subjectTeacher,
				this.personas.personaFor(label + "-assistant", assistant), assistant, classId, s1,
				this.personas.personaFor("abel", s1), s2, s3, guardian1, guardian2, guardianOfS2, otherClass,
				this.personas.personaFor(label + "-other", otherTeacher));
	}

	/** Memberships made by the fixtures start now; make them start long ago so that old dates are valid roll days. */
	protected void backdateMemberships(UUID classId) {
		this.jdbc.update("update class_students set joined_at = timestamptz '2025-01-01 00:00:00+00' where class_id = ?", classId);
	}

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value),
				this.personas.platformAdmin().userId());
	}

	protected LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), ADDIS);
	}

	// --- requests ---------------------------------------------------------------------------

	protected static Map<String, Object> mark(UUID student, String status) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("studentId", student);
		m.put("status", status);
		return m;
	}

	protected static Map<String, Object> mark(UUID student, String status, Integer minutesLate, String note,
			Integer expectedVersion) {
		Map<String, Object> m = mark(student, status);
		if (minutesLate != null) {
			m.put("minutesLate", minutesLate);
		}
		if (note != null) {
			m.put("note", note);
		}
		if (expectedVersion != null) {
			m.put("expectedVersion", expectedVersion);
		}
		return m;
	}

	protected static Map<String, Object> rollBody(LocalDate date, boolean markAllPresent, Map<String, Object>... marks) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("date", date.toString());
		if (markAllPresent) {
			body.put("markAllPresent", true);
		}
		body.put("records", new ArrayList<>(List.of(marks)));
		return body;
	}

	protected JsonNode take(Setup s, Persona as, Map<String, Object> body, int status) throws Exception {
		return call(HttpMethod.PUT, s.rollUrl(), as, body, status);
	}

	protected JsonNode roll(Setup s, Persona as, LocalDate date) throws Exception {
		return get(s.rollUrl() + "?date=" + date, as);
	}

	/** The student's entry of a roll view. */
	protected static JsonNode student(JsonNode roll, UUID studentId) {
		for (JsonNode student : roll.get("students")) {
			if (student.get("studentId").asString().equals(studentId.toString())) {
				return student;
			}
		}
		throw new AssertionError("student " + studentId + " is not on the roll");
	}

	protected static JsonNode outcome(JsonNode result, UUID studentId) {
		for (JsonNode outcome : result.get("outcomes")) {
			if (outcome.get("studentId").asString().equals(studentId.toString())) {
				return outcome;
			}
		}
		throw new AssertionError("no outcome for " + studentId);
	}

	protected static boolean absent(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull();
	}

	protected void moveClockTo(String instant) {
		this.clock.moveTo(Instant.parse(instant));
	}

	protected int countRecords(UUID classId) {
		return this.jdbc.queryForObject("select count(*) from attendance_records where class_id = ?", Integer.class, classId);
	}

}
