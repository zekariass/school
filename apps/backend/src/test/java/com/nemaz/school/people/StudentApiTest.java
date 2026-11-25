package com.nemaz.school.people;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Managed students: creation (no phone or email), the privacy split, listing, withdrawal and password sign-in. */
@IntegrationTest
@RecordApplicationEvents
class StudentApiTest extends SchoolApiTestSupport {

	@Autowired
	ApplicationEvents events;

	@Autowired
	CacheManager caches;

	private UUID schoolId;

	private Persona admin;

	private String students() {
		return school(this.schoolId) + "/students";
	}

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Student school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "student-admin");
	}

	@AfterEach
	void resetSettings() {
		this.jdbc.update("delete from settings where school_id = ?", this.schoolId);
		this.caches.getCache("school-configuration").clear();
	}

	private void setSetting(String key, String value) {
		this.jdbc.update("insert into settings (key, scope_type, school_id, value) values (?, 'SCHOOL', ?, ?::jsonb)", key,
				this.schoolId, value);
		this.caches.getCache("school-configuration").clear();
	}

	@Test
	void aManagedStudentHasNoPhoneOrEmailTheSchoolsLanguageAndAnEnrollment() throws Exception {
		JsonNode created = post(students(), this.admin, Map.of("givenName", "Abel", "middleName", "Tadesse", "familyName",
				"Bekele", "dateOfBirth", "2015-03-02", "gender", "MALE", "studentNumber", "S-001", "username", "Abel.B",
				"avatarConfig", Map.of("hat", "red")), 201);
		UUID id = uuid(created, "id");
		assertThat(created.get("accessLevel").asString()).isEqualTo("FULL");
		assertThat(created.get("displayName").asString()).isEqualTo("Abel Tadesse Bekele");
		assertThat(created.get("username").asString()).isEqualTo("abel.b");
		assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(created.get("avatarConfig").get("hat").asString()).isEqualTo("red");
		Map<String, Object> user = this.jdbc.queryForMap(
				"select account_type, email, phone_e164, preferred_language_code, status from users where id = ?", id);
		assertThat(user).containsEntry("account_type", "MANAGED")
			.containsEntry("email", null)
			.containsEntry("phone_e164", null)
			.containsEntry("preferred_language_code", "am")
			.containsEntry("status", "ACTIVE");
		assertThat(this.jdbc.queryForObject("select count(*) from student_profiles where user_id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(this.jdbc.queryForMap("select status, student_number, admission_on is not null as admitted from school_students where student_id = ?",
				id)).containsEntry("status", "ACTIVE").containsEntry("student_number", "S-001").containsEntry("admitted", true);
		assertThat(this.events.stream(StudentEnrolledInSchool.class).count()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'student.create' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(1);
	}

	@Test
	void numbersUsernamesAndDatesAreValidated() throws Exception {
		post(students(), this.admin, Map.of("givenName", "One", "studentNumber", "N-1", "username", "one.user"), 201);
		assertThat(errorCode(post(students(), this.admin, Map.of("givenName", "Two", "studentNumber", "N-1"), 409)))
			.isEqualTo("student.number_taken");
		assertThat(errorCode(post(students(), this.admin, Map.of("givenName", "Two", "username", "ONE.user"), 409)))
			.isEqualTo("user.username_taken");
		assertThat(errorCode(post(students(), this.admin, Map.of("givenName", "Two", "username", "9lives"), 400)))
			.isEqualTo("user.invalid_username");
		post(students(), this.admin, Map.of("givenName", "Two", "dateOfBirth", "2999-01-01"), 400);
		post(students(), this.admin, Map.of("familyName", "NoGivenName"), 400);
		// the failed creations left nothing behind
		assertThat(this.jdbc.queryForObject("select count(*) from school_students where school_id = ?", Integer.class, this.schoolId))
			.isEqualTo(1);
		// the same number may be used in another school
		UUID other = this.personas.newSchool("Other student school");
		post(school(other) + "/students", this.personas.schoolAdmin(other, "other"), Map.of("givenName", "Three", "studentNumber", "N-1"), 201);
	}

	@Test
	void aTeacherCreatesStudentsOnlyStraightIntoOwnClass() throws Exception {
		UUID teacherId = this.personas.newTeacher(this.schoolId, "Teacher");
		Persona teacher = this.personas.personaFor("teacher", teacherId);
		UUID own = this.personas.newClass(this.schoolId, "Own");
		UUID foreignClass = this.personas.newClass(this.schoolId, "Not mine");
		this.personas.addTeacherToClass(this.schoolId, own, teacherId, "LEAD", null);
		JsonNode created = post(students(), teacher, Map.of("givenName", "Inline", "classId", own), 201);
		assertThat(created.get("classes")).hasSize(1);
		assertThat(this.jdbc.queryForObject("select count(*) from class_students where class_id = ? and student_id = ? and status = 'ACTIVE'",
				Integer.class, own, uuid(created, "id"))).isEqualTo(1);
		post(students(), teacher, Map.of("givenName", "Loose"), 403);
		post(students(), teacher, Map.of("givenName", "Elsewhere", "classId", foreignClass), 404);
		post(students(), teacher, Map.of("givenName", "Ghost", "classId", UUID.randomUUID()), 404);
		// a failure after the account was made (archived class) rolls everything back
		this.jdbc.update("update classes set status = 'ARCHIVED', archived_at = now() where id = ?", own);
		int before = this.jdbc.queryForObject("select count(*) from users", Integer.class);
		post(students(), teacher, Map.of("givenName", "Late", "classId", own), 409);
		assertThat(this.jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(before);
	}

	@Test
	void personalDataReachesOnlyManagersTheStudentAndTheirTeachers() throws Exception {
		UUID classId = this.personas.newClass(this.schoolId, "Privacy");
		UUID teacherId = this.personas.newTeacher(this.schoolId, "Teacher");
		this.personas.addTeacherToClass(this.schoolId, classId, teacherId, "LEAD", null);
		UUID target = uuid(post(students(), this.admin, Map.of("givenName", "Target", "dateOfBirth", "2014-01-02", "gender",
				"FEMALE", "username", "target.t", "studentNumber", "T-1", "classId", classId), 201), "id");
		UUID mate = uuid(post(students(), this.admin, Map.of("givenName", "Mate", "classId", classId), 201), "id");
		UUID stranger = this.personas.newStudent(this.schoolId, "Stranger");
		UUID otherTeacher = this.personas.newTeacher(this.schoolId, "Other teacher");
		String url = students() + "/" + target;

		for (Persona full : List.of(this.admin, this.personas.personaFor("t", teacherId), this.personas.personaFor("self", target))) {
			JsonNode view = get(url, full);
			assertThat(view.get("accessLevel").asString()).isEqualTo("FULL");
			assertThat(view.get("dateOfBirth").asString()).isEqualTo("2014-01-02");
			assertThat(view.get("gender").asString()).isEqualTo("FEMALE");
			assertThat(view.get("username").asString()).isEqualTo("target.t");
			assertThat(view.get("studentNumber").asString()).isEqualTo("T-1");
		}
		// the three full reads: two by staff are audited, the student's own is not
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'student.read' and target_id = ?",
				Integer.class, target)).isEqualTo(2);
		JsonNode classmate = get(url, this.personas.personaFor("mate", mate));
		assertThat(classmate.get("accessLevel").asString()).isEqualTo("BASIC");
		assertThat(classmate.get("displayName").asString()).isEqualTo("Target");
		for (String hidden : List.of("dateOfBirth", "gender", "username", "studentNumber", "classes", "status", "enrollmentId",
				"campusId", "givenName", "familyName")) {
			assertThat(classmate.has(hidden)).as(hidden).isFalse();
		}
		call(HttpMethod.GET, url, this.personas.personaFor("stranger", stranger), null, 404);
		call(HttpMethod.GET, url, this.personas.personaFor("other", otherTeacher), null, 404);
		call(HttpMethod.GET, url, this.personas.plainUser(), null, 404);
		// a student of another school
		call(HttpMethod.GET, url, this.personas.get(com.nemaz.school.support.Personas.Name.STUDENT_B1), null, 404);
		// the roster API applies the same split (see ClassApiTest); the list endpoint is not open to students
		call(HttpMethod.GET, students(), this.personas.personaFor("mate", mate), null, 403);
	}

	@Test
	void listFiltersByClassGradeStatusAndNameAndTeachersSeeOnlyTheirOwnStudents() throws Exception {
		UUID grade = uuid(post(school(this.schoolId) + "/grade-levels", this.admin, Map.of("name", "Grade 3"), 201), "id");
		UUID c3a = uuid(post(school(this.schoolId) + "/classes", this.admin, Map.of("name", "3A", "gradeLevelId", grade), 201), "id");
		UUID c4a = uuid(post(school(this.schoolId) + "/classes", this.admin, Map.of("name", "4A"), 201), "id");
		UUID anna = uuid(post(students(), this.admin, Map.of("givenName", "Anna", "classId", c3a), 201), "id");
		UUID ben = uuid(post(students(), this.admin, Map.of("givenName", "Ben", "classId", c4a), 201), "id");
		UUID cleo = uuid(post(students(), this.admin, Map.of("givenName", "Cleo"), 201), "id");
		assertThat(names(get(students(), this.admin))).containsExactly("Anna", "Ben", "Cleo");
		assertThat(names(get(students() + "?classId=" + c3a, this.admin))).containsExactly("Anna");
		assertThat(names(get(students() + "?gradeLevelId=" + grade, this.admin))).containsExactly("Anna");
		assertThat(names(get(students() + "?q=le", this.admin))).containsExactly("Cleo");
		assertThat(names(get(students() + "?classId=" + c4a + "&gradeLevelId=" + grade, this.admin))).isEmpty();
		JsonNode page1 = get(students() + "?limit=2", this.admin);
		assertThat(names(page1)).containsExactly("Anna", "Ben");
		assertThat(names(get(students() + "?limit=2&cursor=" + page1.get("nextCursor").asString(), this.admin))).containsExactly("Cleo");
		post(students() + "/" + cleo + "/withdraw", this.admin, Map.of("reason", "moved away"), 200);
		assertThat(names(get(students(), this.admin))).containsExactly("Anna", "Ben");
		assertThat(names(get(students() + "?status=WITHDRAWN", this.admin))).containsExactly("Cleo");

		UUID teacherId = this.personas.newTeacher(this.schoolId, "Teacher");
		this.personas.addTeacherToClass(this.schoolId, c3a, teacherId, "CO_TEACHER", null);
		Persona teacher = this.personas.personaFor("teacher", teacherId);
		assertThat(names(get(students(), teacher))).containsExactly("Anna");
		call(HttpMethod.GET, students() + "?classId=" + c4a, teacher, null, 403);
		assertThat(ben).isNotEqualTo(anna);
	}

	private static List<String> names(JsonNode page) {
		return page.get("items").valueStream().map((n) -> n.get("displayName").asString()).toList();
	}

	@Test
	void withdrawingEndsMembershipsAndLoginCodesButKeepsHistory() throws Exception {
		UUID classId = this.personas.newClass(this.schoolId, "Leaving");
		UUID teacherId = this.personas.newTeacher(this.schoolId, "Teacher");
		this.personas.addTeacherToClass(this.schoolId, classId, teacherId, "LEAD", null);
		UUID id = uuid(post(students(), this.admin, Map.of("givenName", "Leaver", "classId", classId), 201), "id");
		JsonNode codes = post(school(this.schoolId) + "/classes/" + classId + "/login-codes", this.personas.personaFor("t", teacherId),
				Map.of("type", "TEXT"), 200);
		String code = codes.get(0).get("textCode").asString();
		postJson("/api/v1/auth/login-code", Map.of("code", code)).andExpect(status().isOk());
		this.events.clear();

		JsonNode withdrawn = post(students() + "/" + id + "/withdraw", this.admin, null, 200);
		assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(withdrawn.has("leftOn")).isTrue();
		assertThat(this.jdbc.queryForMap("select status, left_reason from class_students where student_id = ?", id))
			.containsEntry("status", "FORMER")
			.containsEntry("left_reason", "LEFT_SCHOOL");
		assertThat(this.jdbc.queryForObject("select count(*) from student_login_codes where student_id = ? and revoked_at is null",
				Integer.class, id)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where user_id = ? and revoked_at is null",
				Integer.class, id)).isZero();
		assertThat(this.events.stream(StudentWithdrawnFromSchool.class).count()).isEqualTo(1);
		assertThat(this.events.stream(ClassMembershipChanged.class).count()).isEqualTo(1);
		// the code no longer works, with the same answer as for any bad code
		postJson("/api/v1/auth/login-code", Map.of("code", code)).andExpect(status().isBadRequest());
		assertThat(errorCode(post(students() + "/" + id + "/withdraw", this.admin, null, 409))).isEqualTo("student.not_enrolled");
		// the student can be enrolled again as a new enrollment (history stays)
		assertThat(this.jdbc.queryForObject("select count(*) from school_students where student_id = ?", Integer.class, id)).isEqualTo(1);
		get(students() + "/" + id, this.admin);
	}

	@Test
	void updatesAreLimitedAndTheStudentMayOnlyChangeTheirAvatar() throws Exception {
		UUID id = uuid(post(students(), this.admin, Map.of("givenName", "Edit", "studentNumber", "E-1"), 201), "id");
		post(students(), this.admin, Map.of("givenName", "Other", "studentNumber", "E-2"), 201);
		JsonNode updated = call(HttpMethod.PATCH, students() + "/" + id, this.admin,
				Map.of("familyName", "Changed", "gender", "FEMALE", "dateOfBirth", "2013-04-05"), 200);
		assertThat(updated.get("displayName").asString()).isEqualTo("Edit Changed");
		assertThat(errorCode(call(HttpMethod.PATCH, students() + "/" + id, this.admin, Map.of("studentNumber", "E-2"), 409)))
			.isEqualTo("student.number_taken");
		Persona self = this.personas.personaFor("self", id);
		call(HttpMethod.PATCH, students() + "/" + id, self, Map.of("avatarConfig", Map.of("hat", "green")), 200);
		assertThat(get(students() + "/" + id, self).get("avatarConfig").get("hat").asString()).isEqualTo("green");
		call(HttpMethod.PATCH, students() + "/" + id, self, Map.of("gender", "MALE"), 403);
		call(HttpMethod.PATCH, students() + "/" + id, self, Map.of("studentNumber", "X"), 403);
	}

	@Test
	void passwordSignInIsOffByDefaultAndWorksOnlyWhereTheSchoolAllowsIt() throws Exception {
		UUID id = uuid(post(students(), this.admin, Map.of("givenName", "Older", "username", "older.kid"), 201), "id");
		UUID noName = uuid(post(students(), this.admin, Map.of("givenName", "Nameless"), 201), "id");
		// setting off: nothing can be set
		assertThat(errorCode(post(students() + "/" + id + "/password", this.admin, Map.of("password", "a-long-password"), 409)))
			.isEqualTo("student.password_login_disabled");
		setSetting("students.password_login.enabled", "true");
		post(students() + "/" + noName + "/password", this.admin, Map.of("password", "a-long-password"), 409);
		post(students() + "/" + id + "/password", this.admin, Map.of("password", "short"), 400);
		post(students() + "/" + id + "/password", this.admin, Map.of("password", "a-long-password"), 204);
		assertThat(this.jdbc.queryForObject("select must_change from password_credentials where user_id = ?", Boolean.class, id)).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'student.password.set' and target_id = ?",
				Integer.class, id)).isEqualTo(1);
		assertThat(this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ?", String.class, id).toString())
			.doesNotContain("a-long-password");

		postJson("/api/v1/auth/password", Map.of("identifier", "Older.Kid", "password", "a-long-password"))
			.andExpect(status().isOk());
		// the school turns it off again: the same correct password is refused, like any wrong one
		this.jdbc.update("update settings set value = 'false'::jsonb where school_id = ?", this.schoolId);
		this.caches.getCache("school-configuration").clear();
		String disabled = postJson("/api/v1/auth/password", Map.of("identifier", "older.kid", "password", "a-long-password"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String wrong = postJson("/api/v1/auth/password", Map.of("identifier", "older.kid", "password", "wrong-password-here"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(this.json.readTree(disabled).get("code").asString()).isEqualTo("auth.invalid_credentials");
		assertThat(this.json.readTree(wrong).get("code").asString()).isEqualTo("auth.invalid_credentials");
		// a student never reaches a password through someone else's school setting
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'auth.password.sign_in' and metadata->>'reason' = 'password_login_disabled'",
				Integer.class)).isGreaterThanOrEqualTo(1);
	}

}
