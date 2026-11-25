package com.nemaz.school.engagement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A school with a class (lead teacher, assistant, two students, a guardian of the first student), another class with its
 * own teacher, and the calls of the points API.
 */
public abstract class PointsTestSupport extends SchoolApiTestSupport {

	@Autowired
	protected SettingsService settings;

	protected record Setup(UUID schoolId, Persona admin, Persona lead, UUID leadId, Persona assistant, UUID classId,
			UUID s1, Persona student1, UUID s2, Persona student2, Persona guardian, UUID otherClassId, Persona otherTeacher) {

		String api() {
			return "/api/v1/schools/" + this.schoolId;
		}

		String classApi() {
			return api() + "/classes/" + this.classId;
		}

	}

	protected Setup setup(String label) {
		UUID school = this.personas.newSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID lead = this.personas.newTeacher(school, label + "-lead");
		UUID assistant = this.personas.newTeacher(school, label + "-assistant");
		UUID classId = this.personas.newClass(school, "1A");
		this.personas.addTeacherToClass(school, classId, lead, "LEAD", null);
		this.personas.addTeacherToClass(school, classId, assistant, "ASSISTANT", null);
		UUID s1 = this.personas.newStudent(school, "Abel");
		UUID s2 = this.personas.newStudent(school, "Bethlehem");
		this.personas.addStudentToClass(school, classId, s1);
		this.personas.addStudentToClass(school, classId, s2);
		Persona guardian = this.personas.newUser(label + "-guardian");
		this.personas.linkGuardian(s1, guardian.userId(), "ACTIVE", false);
		UUID otherClass = this.personas.newClass(school, "2B");
		UUID otherTeacher = this.personas.newTeacher(school, label + "-other");
		this.personas.addTeacherToClass(school, otherClass, otherTeacher, "LEAD", null);
		return new Setup(school, admin, this.personas.personaFor(label + "-lead", lead), lead,
				this.personas.personaFor(label + "-assistant", assistant), classId, s1,
				this.personas.personaFor("abel", s1), s2, this.personas.personaFor("bethlehem", s2), guardian, otherClass,
				this.personas.personaFor(label + "-other", otherTeacher));
	}

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value),
				this.personas.platformAdmin().userId());
	}

	/** The school's own category for a template code (the first call makes the school's copy of the defaults). */
	protected UUID category(Setup s, String templateCode) throws Exception {
		get(s.api() + "/point-categories", s.admin());
		return this.jdbc.queryForObject("select id from point_categories where school_id = ? and template_code = ?",
				UUID.class, s.schoolId(), templateCode);
	}

	protected JsonNode award(Setup s, Persona as, UUID categoryId, List<UUID> students, int status) throws Exception {
		return call(HttpMethod.POST, s.classApi() + "/points/awards", as,
				Map.of("categoryId", categoryId, "studentIds", students), status);
	}

	protected JsonNode awardWithOp(Setup s, Persona as, UUID categoryId, List<UUID> students, UUID op, int status)
			throws Exception {
		return call(HttpMethod.POST, s.classApi() + "/points/awards", as,
				Map.of("categoryId", categoryId, "studentIds", students, "clientOpId", op), status);
	}

	protected JsonNode awardHome(Setup s, Persona as, UUID student, UUID categoryId, int status) throws Exception {
		return call(HttpMethod.POST, s.api() + "/students/" + student + "/home-points", as,
				Map.of("categoryId", categoryId), status);
	}

	/** Null fields are left out of the JSON: absent or null. */
	protected static boolean absent(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull();
	}

	protected UUID transactionId(JsonNode awardResult, int index) {
		return uuid(awardResult.get("transactions").get(index), "id");
	}

	/** Waits for the projection (an event listener) to show the class total of the student. */
	protected void awaitClassTotal(UUID classId, UUID studentId, int expected) {
		await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select positive_total + needs_work_total from point_balances where class_id = ? and student_id = ? and context = 'CLASS'",
				Integer.class, classId, studentId)).containsExactly(expected));
	}

	protected int ledgerRows(String where, Object... args) {
		return this.jdbc.queryForObject("select count(*) from point_transactions where " + where, Integer.class, args);
	}

}
