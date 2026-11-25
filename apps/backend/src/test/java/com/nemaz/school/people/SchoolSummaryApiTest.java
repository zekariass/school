package com.nemaz.school.people;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard counts: live rows only, school.read only, no leak across schools. */
@IntegrationTest
class SchoolSummaryApiTest extends SchoolApiTestSupport {

	@Test
	void countsLiveRowsOfTheSchoolOnly() throws Exception {
		UUID schoolId = this.personas.newSchool("Summary school");
		Persona admin = this.personas.schoolAdmin(schoolId, "summary-admin");
		this.personas.newTeacher(schoolId, "summary-teacher");
		this.personas.newStudent(schoolId, "summary-student-1");
		this.personas.newStudent(schoolId, "summary-student-2");
		this.personas.newClass(schoolId, "7A");
		UUID other = this.personas.newSchool("Other summary school");
		this.personas.newStudent(other, "summary-other-student");

		JsonNode summary = get(school(schoolId) + "/summary", admin);

		assertThat(summary.get("students").asLong()).isEqualTo(2);
		assertThat(summary.get("teachers").asLong()).isEqualTo(1);
		assertThat(summary.get("classes").asLong()).isEqualTo(1);
		assertThat(summary.get("academicYears").asLong()).isEqualTo(1);
		assertThat(summary.get("hasCurrentYear").asBoolean()).isTrue();
		assertThat(summary.get("gradeLevels").asLong()).isZero();
		assertThat(summary.get("subjects").asLong()).isZero();
	}

	@Test
	void anotherSchoolsAdministratorCannotSeeItAndAMemberWithoutSchoolReadIsForbidden() throws Exception {
		UUID schoolId = this.personas.newSchool("Private summary school");
		Persona outsider = this.personas.schoolAdmin(this.personas.newSchool("Outsider school"), "summary-outsider");
		call(HttpMethod.GET, school(schoolId) + "/summary", outsider, null, 404);
		call(HttpMethod.GET, school(schoolId) + "/summary", null, null, 401);
	}

}
