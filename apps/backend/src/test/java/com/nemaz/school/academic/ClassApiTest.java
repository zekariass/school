package com.nemaz.school.academic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

/** Classes and rosters: creation rules, filtered lists, the privacy split, move atomicity, the last-lead rule, events. */
@IntegrationTest
@RecordApplicationEvents
class ClassApiTest extends SchoolApiTestSupport {

	@Autowired
	ApplicationEvents events;

	@Autowired
	CacheManager caches;

	private UUID schoolId;

	private Persona admin;

	private UUID teacherId;

	private Persona teacher;

	private String classes() {
		return school(this.schoolId) + "/classes";
	}

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Class school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "class-admin");
		this.teacherId = this.personas.newTeacher(this.schoolId, "Teacher Tess");
		this.teacher = this.personas.personaFor("teacher", this.teacherId);
	}

	private JsonNode createClass(Persona as, String name) throws Exception {
		return post(classes(), as, Map.of("name", name), 201);
	}

	private UUID studentIn(UUID classId, String name) {
		UUID student = this.personas.newStudent(this.schoolId, name);
		this.personas.addStudentToClass(this.schoolId, classId, student);
		return student;
	}

	@Test
	void anAdministratorCreatesAClassInTheCurrentYearWithSubjectsAndStudents() throws Exception {
		UUID subject = uuid(post(school(this.schoolId) + "/subjects", this.admin, Map.of("name", "Science"), 201), "id");
		UUID grade = uuid(post(school(this.schoolId) + "/grade-levels", this.admin, Map.of("name", "Grade 5"), 201), "id");
		UUID student = this.personas.newStudent(this.schoolId, "Zed");
		JsonNode created = post(classes(), this.admin, Map.of("name", "5A", "gradeLevelId", grade, "classType", "HOMEROOM",
				"icon", "star", "color", "#aabbcc", "subjectIds", List.of(subject), "studentIds", List.of(student)), 201);
		assertThat(created.get("academicYearId").asString())
			.isEqualTo(this.personas.currentYear(this.schoolId).toString());
		assertThat(created.get("gradeLevelName").asString()).isEqualTo("Grade 5");
		assertThat(created.get("studentCount").asInt()).isEqualTo(1);
		assertThat(created.get("subjectIds")).hasSize(1);
		assertThat(created.has("myMembership")).isFalse();
		assertThat(this.events.stream(ClassCreated.class).count()).isEqualTo(1);
		assertThat(this.events.stream(ClassMembershipChanged.class)
			.filter((e) -> e.memberType() == MemberType.STUDENT && e.change() == Change.JOINED)
			.count()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'class.create' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(1);
		// a student who is not enrolled, an unknown grade and a year of another school are refused
		post(classes(), this.admin, Map.of("name", "x", "studentIds", List.of(UUID.randomUUID())), 409);
		post(classes(), this.admin, Map.of("name", "x", "gradeLevelId", UUID.randomUUID()), 404);
		post(classes(), this.admin, Map.of("name", "x", "academicYearId", this.personas.fixture().yearA()), 404);
	}

	@Test
	void aTeacherCreatingAClassBecomesItsLeadWhileTheSettingAllows() throws Exception {
		JsonNode created = createClass(this.teacher, "Teacher's class");
		assertThat(created.get("myMembership").asString()).isEqualTo("TEACHER");
		assertThat(created.get("myTeacherRole").asString()).isEqualTo("LEAD");
		UUID classId = uuid(created, "id");
		assertThat(get(classes() + "/" + classId + "/teachers", this.teacher)).hasSize(1);
		// a student cannot create classes
		Persona student = this.personas.personaFor("s", this.personas.newStudent(this.schoolId, "S"));
		post(classes(), student, Map.of("name", "nope"), 403);
		// switching the setting off stops teachers (administrators are unaffected)
		this.jdbc.update("insert into settings (key, scope_type, school_id, value) values ('classes.teachers_can_create', 'SCHOOL', ?, 'false')",
				this.schoolId);
		this.caches.getCache("school-configuration").clear();
		try {
			post(classes(), this.teacher, Map.of("name", "too late"), 403);
			post(classes(), this.admin, Map.of("name", "still fine"), 201);
		}
		finally {
			this.jdbc.update("delete from settings where school_id = ?", this.schoolId);
			this.caches.getCache("school-configuration").clear();
		}
	}

	@Test
	void classesListIsFilteredToWhatTheCallerMaySeeAndPaged() throws Exception {
		UUID a = uuid(createClass(this.admin, "A class"), "id");
		UUID b = uuid(createClass(this.admin, "B class"), "id");
		UUID c = uuid(createClass(this.admin, "C class"), "id");
		UUID student = studentIn(b, "Pupil");
		this.personas.addTeacherToClass(this.schoolId, c, this.teacherId, "CO_TEACHER", null);
		Persona pupil = this.personas.personaFor("pupil", student);

		assertThat(names(get(classes(), this.admin))).containsExactly("A class", "B class", "C class");
		assertThat(names(get(classes(), pupil))).containsExactly("B class");
		assertThat(names(get(classes(), this.teacher))).containsExactly("C class");
		assertThat(names(get(classes() + "?mine=true", this.admin))).isEmpty();
		assertThat(names(get(classes() + "?q=b", this.admin))).containsExactly("B class");
		assertThat(names(get(classes() + "?status=ARCHIVED", this.admin))).isEmpty();

		JsonNode page1 = get(classes() + "?limit=2", this.admin);
		assertThat(page1.get("items")).hasSize(2);
		assertThat(page1.has("nextCursor")).isTrue();
		JsonNode page2 = get(classes() + "?limit=2&cursor=" + page1.get("nextCursor").asString(), this.admin);
		assertThat(names(page2)).containsExactly("C class");
		assertThat(page2.has("nextCursor")).isFalse();
		get(classes() + "?cursor=garbage", this.admin, 400);
		assertThat(a).isNotEqualTo(c);
	}

	private JsonNode get(String url, Persona as, int status) throws Exception {
		return call(HttpMethod.GET, url, as, null, status);
	}

	private static List<String> names(JsonNode page) {
		return page.get("items").valueStream().map((n) -> n.get("name").asString()).toList();
	}

	@Test
	void aClassOfAnotherSchoolOrOneYouAreNotInIsNotFound() throws Exception {
		UUID mine = uuid(createClass(this.admin, "Mine"), "id");
		UUID student = studentIn(mine, "Insider");
		UUID outsiderStudent = this.personas.newStudent(this.schoolId, "Outsider");
		Persona outsider = this.personas.personaFor("outsider", outsiderStudent);
		get(classes() + "/" + mine, this.personas.personaFor("insider", student), 200);
		get(classes() + "/" + mine, outsider, 404);
		get(classes() + "/" + mine + "/students", outsider, 404);
		// the class addressed through another school's URL
		UUID other = this.personas.newSchool("Other class school");
		Persona otherAdmin = this.personas.schoolAdmin(other, "other-class-admin");
		get(school(other) + "/classes/" + mine, otherAdmin, 404);
		get(classes() + "/" + mine, otherAdmin, 404);
		get(school(other) + "/classes/" + mine, this.admin, 404);
		post(classes() + "/" + mine + "/students", otherAdmin, Map.of("studentIds", List.of(student)), 404);
	}

	@Test
	void moveEndsTheOldMembershipAndStartsTheNewOneAtomically() throws Exception {
		UUID from = uuid(createClass(this.admin, "From"), "id");
		UUID to = uuid(createClass(this.admin, "To"), "id");
		UUID student = studentIn(from, "Mover");
		this.events.clear();
		post(classes() + "/" + from + "/students/" + student + "/move", this.admin, Map.of("toClassId", to), 204);

		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"select class_id, status, left_reason, left_at is not null as ended from class_students where student_id = ? order by joined_at, id",
				student);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("class_id", from)
			.containsEntry("status", "FORMER")
			.containsEntry("left_reason", "TRANSFERRED_CLASS")
			.containsEntry("ended", true);
		assertThat(rows.get(1)).containsEntry("class_id", to).containsEntry("status", "ACTIVE").containsEntry("ended", false);
		assertThat(this.events.stream(ClassMembershipChanged.class).map((e) -> e.change() + ":" + e.reason()).toList())
			.containsExactlyInAnyOrder("LEFT:TRANSFERRED_CLASS", "JOINED:null");

		// moving back works too: history keeps growing
		post(classes() + "/" + to + "/students/" + student + "/move", this.admin, Map.of("toClassId", from), 204);
		assertThat(this.jdbc.queryForObject("select count(*) from class_students where student_id = ?", Integer.class, student))
			.isEqualTo(3);
	}

	@Test
	void aFailedMoveLeavesTheOldMembershipUntouched() throws Exception {
		UUID from = uuid(createClass(this.admin, "From"), "id");
		UUID to = uuid(createClass(this.admin, "To"), "id");
		UUID student = studentIn(from, "Stayer");
		this.personas.addStudentToClass(this.schoolId, to, student);
		post(classes() + "/" + from + "/students/" + student + "/move", this.admin, Map.of("toClassId", to), 409);
		assertThat(this.jdbc.queryForObject("select status from class_students where class_id = ? and student_id = ?",
				String.class, from, student)).isEqualTo("ACTIVE");
		// unknown target, same class, archived target
		post(classes() + "/" + from + "/students/" + student + "/move", this.admin, Map.of("toClassId", UUID.randomUUID()), 404);
		post(classes() + "/" + from + "/students/" + student + "/move", this.admin, Map.of("toClassId", from), 400);
		UUID archived = uuid(createClass(this.admin, "Archived"), "id");
		post(classes() + "/" + archived + "/archive", this.admin, null, 200);
		UUID other = studentIn(from, "Other");
		post(classes() + "/" + from + "/students/" + other + "/move", this.admin, Map.of("toClassId", archived), 409);
		assertThat(this.jdbc.queryForObject("select status from class_students where class_id = ? and student_id = ?",
				String.class, from, other)).isEqualTo("ACTIVE");
	}

	@Test
	void rosterAddAndRemoveKeepHistoryAndRefuseDuplicatesAndStrangers() throws Exception {
		UUID classId = uuid(createClass(this.admin, "Roster"), "id");
		UUID s1 = this.personas.newStudent(this.schoolId, "S1");
		UUID s2 = this.personas.newStudent(this.schoolId, "S2");
		JsonNode roster = post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(s1, s2)), 200);
		assertThat(roster).hasSize(2);
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(s1)), 409);
		// a student of another school, or someone who is not a student, is not enrolled here
		UUID foreign = this.personas.newStudent(this.personas.fixture().schoolB(), "Foreign");
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(foreign)), 409);
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(this.teacherId)), 409);
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of()), 400);

		call(HttpMethod.DELETE, classes() + "/" + classId + "/students/" + s1, this.admin, null, 204);
		call(HttpMethod.DELETE, classes() + "/" + classId + "/students/" + s1, this.admin, null, 404);
		assertThat(this.jdbc.queryForList("select status || ':' || left_reason from class_students where student_id = ?",
				String.class, s1)).containsExactly("REMOVED:REMOVED");
		// promoted students are FORMER
		call(HttpMethod.DELETE, classes() + "/" + classId + "/students/" + s2 + "?reason=PROMOTED", this.admin, null, 204);
		assertThat(this.jdbc.queryForObject("select status from class_students where student_id = ?", String.class, s2))
			.isEqualTo("FORMER");
		call(HttpMethod.DELETE, classes() + "/" + classId + "/students/" + s2 + "?reason=LEFT_SCHOOL", this.admin, null, 400);
		// a removed student can be added again: a second row
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(s1)), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from class_students where student_id = ?", Integer.class, s1))
			.isEqualTo(2);
	}

	@Test
	void anArchivedClassKeepsItsMembersButItsRosterIsFrozen() throws Exception {
		UUID classId = uuid(createClass(this.admin, "Frozen"), "id");
		UUID student = studentIn(classId, "Frozen student");
		JsonNode archived = post(classes() + "/" + classId + "/archive", this.admin, null, 200);
		assertThat(archived.get("status").asString()).isEqualTo("ARCHIVED");
		assertThat(archived.get("studentCount").asInt()).isEqualTo(1);
		UUID other = this.personas.newStudent(this.schoolId, "Late");
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(other)), 409);
		call(HttpMethod.DELETE, classes() + "/" + classId + "/students/" + student, this.admin, null, 409);
		// members still see it; restoring thaws it
		get(classes() + "/" + classId, this.personas.personaFor("frozen", student), 200);
		assertThat(post(classes() + "/" + classId + "/restore", this.admin, null, 200).get("status").asString())
			.isEqualTo("ACTIVE");
		post(classes() + "/" + classId + "/students", this.admin, Map.of("studentIds", List.of(other)), 200);
	}

	@Test
	void theLastLeadCannotBeRemovedAndOnlyLeadsAndManagersChangeTheTeachers() throws Exception {
		UUID classId = uuid(createClass(this.teacher, "Led"), "id");
		String teachers = classes() + "/" + classId + "/teachers";
		UUID second = this.personas.newTeacher(this.schoolId, "Second");
		UUID third = this.personas.newTeacher(this.schoolId, "Third");
		JsonNode afterAdd = post(teachers, this.teacher, Map.of("teacherId", second, "role", "CO_TEACHER"), 200);
		assertThat(afterAdd).hasSize(2);
		UUID leadRow = rowOf(classId, this.teacherId);
		// the only LEAD cannot be removed, by herself or by an administrator
		assertThat(errorCode(call(HttpMethod.DELETE, teachers + "/" + leadRow, this.teacher, null, 409))).isEqualTo("class.last_lead");
		assertThat(errorCode(call(HttpMethod.DELETE, teachers + "/" + leadRow, this.admin, null, 409))).isEqualTo("class.last_lead");

		// a co-teacher can neither add teachers nor remove the lead, but may leave herself
		Persona coTeacher = this.personas.personaFor("second", second);
		post(teachers, coTeacher, Map.of("teacherId", third, "role", "ASSISTANT"), 403);
		call(HttpMethod.DELETE, teachers + "/" + leadRow, coTeacher, null, 403);
		// a second lead makes the first removable
		post(teachers, this.admin, Map.of("teacherId", third, "role", "LEAD"), 200);
		call(HttpMethod.DELETE, teachers + "/" + leadRow, this.teacher, null, 204);
		assertThat(this.jdbc.queryForObject("select status from class_teachers where id = ?", String.class, leadRow))
			.isEqualTo("REMOVED");
		// the removed lead no longer sees the class
		get(classes() + "/" + classId, this.teacher, 404);
		call(HttpMethod.DELETE, teachers + "/" + rowOf(classId, second), coTeacher, null, 204);
		assertThat(this.events.stream(ClassMembershipChanged.class)
			.filter((e) -> e.memberType() == MemberType.TEACHER && e.change() == Change.LEFT)
			.count()).isEqualTo(2);
	}

	private UUID rowOf(UUID classId, UUID teacherId) {
		return this.jdbc.queryForObject(
				"select id from class_teachers where class_id = ? and teacher_id = ? and status = 'ACTIVE'", UUID.class, classId,
				teacherId);
	}

	@Test
	void teacherRolesNeedTheRightSubjectAndRealTeachersOfTheSchool() throws Exception {
		UUID classId = uuid(createClass(this.admin, "Staffing"), "id");
		String teachers = classes() + "/" + classId + "/teachers";
		UUID subject = uuid(post(school(this.schoolId) + "/subjects", this.admin, Map.of("name", "Art"), 201), "id");
		post(teachers, this.admin, Map.of("teacherId", this.teacherId, "role", "SUBJECT_TEACHER"), 400);
		post(teachers, this.admin, Map.of("teacherId", this.teacherId, "role", "CO_TEACHER", "subjectId", subject), 400);
		post(teachers, this.admin, Map.of("teacherId", this.teacherId, "role", "SUBJECT_TEACHER", "subjectId", UUID.randomUUID()), 404);
		JsonNode ok = post(teachers, this.admin, Map.of("teacherId", this.teacherId, "role", "SUBJECT_TEACHER", "subjectId", subject), 200);
		assertThat(ok.get(0).get("subjectName").asString()).isEqualTo("Art");
		// the same role again is a duplicate, another subject role of the same teacher is fine
		assertThat(errorCode(post(teachers, this.admin,
				Map.of("teacherId", this.teacherId, "role", "SUBJECT_TEACHER", "subjectId", subject), 409)))
			.isEqualTo("class.teacher_already_member");
		// somebody who is only a student, an administrator, a teacher of another school
		UUID student = this.personas.newStudent(this.schoolId, "Not a teacher");
		post(teachers, this.admin, Map.of("teacherId", student, "role", "ASSISTANT"), 409);
		post(teachers, this.admin, Map.of("teacherId", this.personas.get(com.nemaz.school.support.Personas.Name.TEACHER_LEAD_A).userId(),
				"role", "ASSISTANT"), 409);
		// a deactivated teacher cannot be added
		UUID leaver = this.personas.newTeacher(this.schoolId, "Leaver");
		this.jdbc.update("update school_staff set status = 'FORMER' where user_id = ?", leaver);
		post(teachers, this.admin, Map.of("teacherId", leaver, "role", "ASSISTANT"), 409);
	}

	@Test
	void savedGroupsBelongToTheClassTeachers() throws Exception {
		UUID classId = uuid(createClass(this.teacher, "Groups"), "id");
		UUID s1 = studentIn(classId, "G1");
		UUID s2 = studentIn(classId, "G2");
		UUID outsider = this.personas.newStudent(this.schoolId, "Not in class");
		String groups = classes() + "/" + classId + "/groups";
		post(groups, this.teacher, Map.of("name", "Bad", "studentIds", List.of(outsider)), 409);
		JsonNode group = post(groups, this.teacher, Map.of("name", "Red team", "studentIds", List.of(s1, s2)), 201);
		assertThat(group.get("studentIds")).hasSize(2);
		assertThat(get(groups, this.teacher)).hasSize(1);
		// students cannot see or change groups
		Persona pupil = this.personas.personaFor("g1", s1);
		get(groups, pupil, 403);
		call(HttpMethod.DELETE, groups + "/" + group.get("id").asString(), pupil, null, 403);
		call(HttpMethod.DELETE, groups + "/" + group.get("id").asString(), this.teacher, null, 204);
		call(HttpMethod.DELETE, groups + "/" + group.get("id").asString(), this.teacher, null, 404);
		assertThat(get(groups, this.teacher)).isEmpty();
	}

	@Test
	void onlyEmptyClassesAreDeleted() throws Exception {
		UUID empty = uuid(createClass(this.teacher, "Empty"), "id");
		call(HttpMethod.DELETE, classes() + "/" + empty, this.teacher, null, 204);
		get(classes() + "/" + empty, this.admin, 404);
		UUID used = uuid(createClass(this.admin, "Used"), "id");
		studentIn(used, "Pupil");
		assertThat(errorCode(call(HttpMethod.DELETE, classes() + "/" + used, this.admin, null, 409))).isEqualTo("class.not_empty");
	}

	@Test
	void studentsSeeClassmatesWithoutPersonalDataAndTeachersSeeEverything() throws Exception {
		UUID classId = uuid(createClass(this.teacher, "Privacy"), "id");
		UUID s1 = studentIn(classId, "Alem Girma");
		UUID s2 = studentIn(classId, "Bruk Tesfaye");
		this.jdbc.update("update users set date_of_birth = date '2016-05-04', gender = 'MALE', username = 'bruk.t' where id = ?", s2);
		this.jdbc.update("update student_profiles set avatar_config = '{\"hat\": \"blue\"}'::jsonb where user_id = ?", s2);
		String roster = classes() + "/" + classId + "/students";

		JsonNode asStudent = get(roster, this.personas.personaFor("alem", s1));
		assertThat(asStudent).hasSize(2);
		for (JsonNode student : asStudent) {
			assertThat(student.get("accessLevel").asString()).isEqualTo("BASIC");
			assertThat(student.has("dateOfBirth")).isFalse();
			assertThat(student.has("gender")).isFalse();
			assertThat(student.has("username")).isFalse();
		}
		assertThat(asStudent.get(1).get("displayName").asString()).isEqualTo("Bruk Tesfaye");
		assertThat(asStudent.get(1).get("avatarConfig").get("hat").asString()).isEqualTo("blue");

		for (Persona full : List.of(this.teacher, this.admin)) {
			JsonNode asTeacher = get(roster, full);
			JsonNode bruk = asTeacher.get(1);
			assertThat(bruk.get("accessLevel").asString()).isEqualTo("FULL");
			assertThat(bruk.get("dateOfBirth").asString()).isEqualTo("2016-05-04");
			assertThat(bruk.get("gender").asString()).isEqualTo("MALE");
			assertThat(bruk.get("username").asString()).isEqualTo("bruk.t");
		}
		// a campus-level class administrator (class.manage without school.students.manage) gets the basic view
		UUID campus = com.nemaz.school.common.id.Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, code) values (?, ?, 'C', 'C')", campus, this.schoolId);
		UUID campusClass = uuid(post(classes(), this.admin, Map.of("name", "Campus class", "campusId", campus), 201), "id");
		UUID campusStudent = studentIn(campusClass, "Campus kid");
		this.jdbc.update("update users set gender = 'FEMALE' where id = ?", campusStudent);
		Persona campusAdmin = this.personas.withRole("campus-admin", "CAMPUS_ADMIN",
				com.nemaz.school.common.security.AccessScope.campus(this.schoolId, campus));
		JsonNode seen = get(classes() + "/" + campusClass + "/students", campusAdmin);
		assertThat(seen.get(0).get("accessLevel").asString()).isEqualTo("BASIC");
		assertThat(seen.get(0).has("gender")).isFalse();
	}

	@Test
	void myClassesListsTheCallersClassesAsStudentAndTeacherWithSchoolAndRole() throws Exception {
		UUID led = uuid(createClass(this.teacher, "Led by me"), "id");
		UUID taught = uuid(createClass(this.admin, "Subject"), "id");
		UUID subject = uuid(post(school(this.schoolId) + "/subjects", this.admin, Map.of("name", "Music"), 201), "id");
		post(classes() + "/" + taught + "/teachers", this.admin,
				Map.of("teacherId", this.teacherId, "role", "SUBJECT_TEACHER", "subjectId", subject), 200);
		UUID student = studentIn(led, "Kid");
		this.personas.addStudentToClass(this.schoolId, taught, student);

		JsonNode mine = get("/api/v1/me/classes", this.teacher);
		assertThat(mine).hasSize(2);
		assertThat(mine.valueStream().map((c) -> c.get("name").asString() + ":" + c.get("teacherRole").asString()).toList())
			.containsExactlyInAnyOrder("Led by me:LEAD", "Subject:SUBJECT_TEACHER");
		assertThat(mine.get(0).get("schoolName").asString()).isEqualTo("Class school");
		assertThat(mine.get(0).get("role").asString()).isEqualTo("TEACHER");

		JsonNode kid = get("/api/v1/me/classes", this.personas.personaFor("kid", student));
		assertThat(kid).hasSize(2);
		assertThat(kid.get(0).get("role").asString()).isEqualTo("STUDENT");
		assertThat(kid.get(0).has("teacherRole")).isFalse();
		// administrators who do not teach have no classes of their own
		assertThat(get("/api/v1/me/classes", this.admin)).isEmpty();
		// archived classes are listed on request only
		post(classes() + "/" + led + "/archive", this.teacher, null, 200);
		assertThat(get("/api/v1/me/classes", this.teacher)).hasSize(1);
		assertThat(get("/api/v1/me/classes?includeArchived=true", this.teacher)).hasSize(2);
	}

}
