package com.nemaz.school.academic;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/** Academic years (one current), periods (bounds, overlap, sequence), grade levels and subjects. */
@IntegrationTest
class AcademicStructureApiTest extends SchoolApiTestSupport {

	private UUID schoolId;

	private Persona admin;

	private void newSchool() {
		this.schoolId = this.personas.newSchool("Academic school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "academic-admin");
	}

	private JsonNode createYear(String name, String from, String to, boolean current) throws Exception {
		return post(school(this.schoolId) + "/academic-years", this.admin,
				Map.of("name", name, "startsOn", from, "endsOn", to, "makeCurrent", current), 201);
	}

	@Test
	void exactlyOneYearIsCurrentAndMakingAnotherCurrentMovesTheFlag() throws Exception {
		newSchool();
		UUID first = uuid(createYear("2025", "2025-09-01", "2026-07-31", false), "id");
		UUID second = uuid(createYear("2026", "2026-09-01", "2027-07-31", false), "id");
		// the school was created with a current year already (fixture); make "first" current
		post(school(this.schoolId) + "/academic-years/" + first + "/make-current", this.admin, null, 200);
		post(school(this.schoolId) + "/academic-years/" + second + "/make-current", this.admin, null, 200);
		assertThat(this.jdbc.queryForList("select id from academic_years where school_id = ? and is_current", UUID.class,
				this.schoolId)).containsExactly(second);
		JsonNode years = get(school(this.schoolId) + "/academic-years", this.admin);
		assertThat(years).hasSize(3);
		long current = 0;
		for (JsonNode year : years) {
			current += year.get("current").asBoolean() ? 1 : 0;
		}
		assertThat(current).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select status from academic_years where id = ?", String.class, second))
			.isEqualTo("ACTIVE");
		// audited
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'academic_year.make_current' and school_id = ?",
				Integer.class, this.schoolId)).isGreaterThanOrEqualTo(2);
	}

	@Test
	void theCurrentYearCannotBeClosedOrDeletedAndAClosedYearCannotBeCurrent() throws Exception {
		newSchool();
		UUID current = this.personas.currentYear(this.schoolId);
		call(HttpMethod.PATCH, school(this.schoolId) + "/academic-years/" + current, this.admin, Map.of("status", "CLOSED"),
				409);
		call(HttpMethod.DELETE, school(this.schoolId) + "/academic-years/" + current, this.admin, null, 409);
		UUID other = uuid(createYear("old", "2020-09-01", "2021-07-31", false), "id");
		call(HttpMethod.PATCH, school(this.schoolId) + "/academic-years/" + other, this.admin, Map.of("status", "CLOSED"),
				200);
		JsonNode problem = post(school(this.schoolId) + "/academic-years/" + other + "/make-current", this.admin, null, 409);
		assertThat(errorCode(problem)).isEqualTo("academic.year_closed");
		call(HttpMethod.DELETE, school(this.schoolId) + "/academic-years/" + other, this.admin, null, 204);
	}

	@Test
	void yearDatesMustBeOrderedAndCannotCutOffPeriods() throws Exception {
		newSchool();
		post(school(this.schoolId) + "/academic-years", this.admin,
				Map.of("name", "bad", "startsOn", "2027-01-01", "endsOn", "2026-01-01"), 400);
		UUID year = uuid(createYear("2027", "2027-09-01", "2028-07-31", false), "id");
		String periods = school(this.schoolId) + "/academic-years/" + year + "/periods";
		post(periods, this.admin, Map.of("name", "T1", "periodType", "TERM", "startsOn", "2027-09-01", "endsOn", "2027-12-31"),
				201);
		call(HttpMethod.PATCH, school(this.schoolId) + "/academic-years/" + year, this.admin,
				Map.of("startsOn", "2027-10-01"), 409);
		call(HttpMethod.PATCH, school(this.schoolId) + "/academic-years/" + year, this.admin,
				Map.of("endsOn", "2028-08-31"), 200);
	}

	@Test
	void periodsStayInsideTheYearNeverOverlapAndKeepAUniqueSequence() throws Exception {
		newSchool();
		UUID year = uuid(createYear("2028", "2028-09-01", "2029-07-31", false), "id");
		String periods = school(this.schoolId) + "/academic-years/" + year + "/periods";
		JsonNode t1 = post(periods, this.admin,
				Map.of("name", "Term 1", "periodType", "TERM", "startsOn", "2028-09-01", "endsOn", "2028-12-20"), 201);
		assertThat(t1.get("sequence").asInt()).isEqualTo(1);
		// overlap by a day
		assertThat(errorCode(post(periods, this.admin,
				Map.of("name", "Term 2", "periodType", "TERM", "startsOn", "2028-12-20", "endsOn", "2029-03-01"), 409)))
			.isEqualTo("academic.period_overlap");
		// outside the year
		assertThat(errorCode(post(periods, this.admin,
				Map.of("name", "Term 2", "periodType", "TERM", "startsOn", "2029-07-01", "endsOn", "2029-08-15"), 409)))
			.isEqualTo("academic.period_outside_year");
		// reversed
		post(periods, this.admin,
				Map.of("name", "Term 2", "periodType", "TERM", "startsOn", "2029-03-01", "endsOn", "2029-02-01"), 400);
		JsonNode t2 = post(periods, this.admin,
				Map.of("name", "Term 2", "periodType", "TERM", "startsOn", "2028-12-21", "endsOn", "2029-03-01"), 201);
		assertThat(t2.get("sequence").asInt()).isEqualTo(2);
		// explicit duplicate sequence
		assertThat(errorCode(post(periods, this.admin, Map.of("name", "Term 3", "periodType", "TERM", "sequence", 2,
				"startsOn", "2029-03-02", "endsOn", "2029-06-01"), 409))).isEqualTo("academic.period_sequence_taken");
		// moving a period into its neighbour overlaps
		call(HttpMethod.PATCH, periods + "/" + t2.get("id").asString(), this.admin, Map.of("startsOn", "2028-12-01"), 409);
		call(HttpMethod.PATCH, periods + "/" + t2.get("id").asString(), this.admin, Map.of("endsOn", "2029-04-01"), 200);
		assertThat(get(periods, this.admin)).hasSize(2);
		call(HttpMethod.DELETE, periods + "/" + t1.get("id").asString(), this.admin, null, 204);
		assertThat(get(periods, this.admin)).hasSize(1);
	}

	@Test
	void theDatabaseItselfRefusesOverlappingPeriods() {
		newSchool();
		UUID year = this.personas.currentYear(this.schoolId);
		this.jdbc.update("""
				insert into academic_periods (academic_year_id, school_id, name, period_type, sequence, starts_on, ends_on)
				values (?, ?, 'A', 'TERM', 1, date '2025-09-01', date '2025-12-01')""", year, this.schoolId);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.jdbc.update("""
				insert into academic_periods (academic_year_id, school_id, name, period_type, sequence, starts_on, ends_on)
				values (?, ?, 'B', 'TERM', 2, date '2025-11-01', date '2026-02-01')""", year, this.schoolId))
			.hasMessageContaining("ex_academic_periods_no_overlap");
	}

	@Test
	void gradeLevelsAndSubjectsHaveUniqueKeysAndCannotBeDeletedWhileUsed() throws Exception {
		newSchool();
		String grades = school(this.schoolId) + "/grade-levels";
		JsonNode g1 = post(grades, this.admin, Map.of("name", "Grade 1"), 201);
		assertThat(g1.get("sequence").asInt()).isEqualTo(1);
		JsonNode g2 = post(grades, this.admin, Map.of("name", "Grade 2", "code", "G2"), 201);
		assertThat(g2.get("sequence").asInt()).isEqualTo(2);
		assertThat(errorCode(post(grades, this.admin, Map.of("name", "Dup", "sequence", 2), 409)))
			.isEqualTo("academic.grade_sequence_taken");
		String subjects = school(this.schoolId) + "/subjects";
		JsonNode math = post(subjects, this.admin, Map.of("name", "Math", "code", "MATH", "color", "#112233"), 201);
		assertThat(errorCode(post(subjects, this.admin, Map.of("name", "Maths", "code", "math"), 409)))
			.isEqualTo("academic.subject_code_taken");
		post(subjects, this.admin, Map.of("name", "Bad color", "color", "red"), 400);

		UUID gradeId = uuid(g1, "id");
		UUID subjectId = uuid(math, "id");
		post(school(this.schoolId) + "/classes", this.admin,
				Map.of("name", "1A", "gradeLevelId", gradeId, "subjectIds", java.util.List.of(subjectId)), 201);
		assertThat(errorCode(call(HttpMethod.DELETE, grades + "/" + gradeId, this.admin, null, 409)))
			.isEqualTo("academic.grade_in_use");
		assertThat(errorCode(call(HttpMethod.DELETE, subjects + "/" + subjectId, this.admin, null, 409)))
			.isEqualTo("academic.subject_in_use");
		// deactivating works
		assertThat(call(HttpMethod.PATCH, grades + "/" + gradeId, this.admin, Map.of("active", false), 200).get("active")
			.asBoolean()).isFalse();
		call(HttpMethod.DELETE, grades + "/" + uuid(g2, "id"), this.admin, null, 204);
	}

	@Test
	void everyMutationIsAuditedAndCreatesAreIdempotent() throws Exception {
		newSchool();
		String url = school(this.schoolId) + "/subjects";
		String key = UUID.randomUUID().toString();
		String body = this.json.writeValueAsString(Map.of("name", "Once"));
		for (int i = 0; i < 2; i++) {
			this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(this.admin.bearer())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from subjects where school_id = ? and name = 'Once'",
				Integer.class, this.schoolId)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'subject.create' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(1);
	}

	@Test
	void anotherSchoolsAdministratorAndOutsidersGet404AndMembersWithoutPermissionGet403() throws Exception {
		newSchool();
		UUID otherSchool = this.personas.newSchool("Other academic school");
		Persona otherAdmin = this.personas.schoolAdmin(otherSchool, "other-admin");
		UUID year = this.personas.currentYear(this.schoolId);
		String base = school(this.schoolId) + "/academic-years";
		call(HttpMethod.GET, base, otherAdmin, null, 404);
		call(HttpMethod.GET, base + "/" + year, otherAdmin, null, 404);
		post(base, otherAdmin, Map.of("name", "x", "startsOn", "2030-01-01", "endsOn", "2030-12-31"), 404);
		call(HttpMethod.GET, base, this.personas.plainUser(), null, 404);
		// a year of school A addressed through school B's URL is not found either
		call(HttpMethod.GET, school(otherSchool) + "/academic-years/" + year, otherAdmin, null, 404);
		// an unknown school is 404 even for a platform administrator (whose grant covers every id)
		UUID unknown = UUID.randomUUID();
		call(HttpMethod.GET, school(unknown) + "/academic-years", this.personas.platformAdmin(), null, 404);
		post(school(unknown) + "/academic-years", this.personas.platformAdmin(),
				Map.of("name", "x", "startsOn", "2030-01-01", "endsOn", "2030-12-31"), 404);
		// a teacher of the school may read but not write
		Persona teacher = this.personas.personaFor("teacher", this.personas.newTeacher(this.schoolId, "teacher"));
		call(HttpMethod.GET, base, teacher, null, 200);
		post(base, teacher, Map.of("name", "x", "startsOn", "2030-01-01", "endsOn", "2030-12-31"), 403);
	}

}
