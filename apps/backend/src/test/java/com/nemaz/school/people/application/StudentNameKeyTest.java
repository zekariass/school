package com.nemaz.school.people.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.text.PersonNames;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.PeopleImports.StudentCandidate;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** The name key of students (trigram index): kept up to date, filled for old rows, and the duplicate scoring built on it. */
@IntegrationTest
class StudentNameKeyTest extends SchoolApiTestSupport {

	@Autowired
	PeopleImports people;

	@Autowired
	NameFoldBackfill backfill;

	UUID schoolId;

	Persona admin;

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Name key school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "name-key-admin");
	}

	private UUID student(UUID school, Persona as, String given, String middle, String family, String dob) throws Exception {
		JsonNode created = call(HttpMethod.POST, "/api/v1/schools/" + school + "/students", as,
				Map.of("givenName", given, "middleName", middle, "familyName", family, "dateOfBirth", dob), 201);
		return uuid(created, "id");
	}

	private String key(UUID studentId) {
		return this.jdbc.queryForObject("select name_fold from student_profiles where user_id = ?", String.class, studentId);
	}

	@Test
	void theKeyFollowsCreateAndUpdate() throws Exception {
		UUID id = student(this.schoolId, this.admin, "Abebe", "Kebede", "Tadesse", "2012-05-14");
		assertThat(key(id)).isEqualTo("abebe kebede tadesse");
		call(HttpMethod.PATCH, "/api/v1/schools/" + this.schoolId + "/students/" + id, this.admin, Map.of("givenName", "ሐይሉ"), 200);
		assertThat(key(id)).isEqualTo(PersonNames.fold("ሐይሉ", "Kebede", "Tadesse"));
	}

	@Test
	void theBackfillFillsOldRowsAndIsIdempotent() throws Exception {
		UUID id = student(this.schoolId, this.admin, "Almaz", "Tesfaye", "Bekele", "2012-05-14");
		this.jdbc.update("update student_profiles set name_fold = null where user_id = ?", id);
		assertThat(this.backfill.backfill()).isGreaterThanOrEqualTo(1);
		assertThat(key(id)).isEqualTo("almaz tesfaye bekele");
		assertThat(this.backfill.backfill()).isZero();
	}

	@Test
	void candidatesAreScoredByNameAndBirthDateWithinTheSchoolOnly() throws Exception {
		UUID abebe = student(this.schoolId, this.admin, "Abebe", "Kebede", "Tadesse", "2012-05-14");
		UUID hailu = student(this.schoolId, this.admin, "ሐይሉ", "በቀለ", "ተስፋዬ", "2010-01-01");
		LocalDate dob = LocalDate.of(2012, 5, 14);
		List<StudentCandidate> same = this.people.studentCandidates(this.schoolId, PersonNames.fold("ABEBE", "Kebede", "Tadesse"),
				dob, 0.6, 5);
		assertThat(same).extracting(StudentCandidate::studentId).containsExactly(abebe);
		assertThat(same.getFirst().similarity()).isGreaterThanOrEqualTo(0.99);
		// one letter off: similar, but not equal
		List<StudentCandidate> close = this.people.studentCandidates(this.schoolId, PersonNames.fold("Abebe", "Kebedi", "Tadesse"),
				dob, 0.6, 5);
		assertThat(close).hasSize(1);
		assertThat(close.getFirst().similarity()).isBetween(0.6, 0.99);
		// a year off still matches, two years off does not
		assertThat(this.people.studentCandidates(this.schoolId, "abebe kebede tadesse", dob.plusYears(1).minusDays(2), 0.6, 5)).hasSize(1);
		assertThat(this.people.studentCandidates(this.schoolId, "abebe kebede tadesse", dob.plusYears(2), 0.6, 5)).isEmpty();
		// Ethiopic homophones are one letter; Latin and Ethiopic spellings are not matched
		assertThat(this.people.studentCandidates(this.schoolId, PersonNames.fold("ሀይሉ", "በቀለ", "ተስፋዬ"), LocalDate.of(2010, 1, 1), 0.6, 5))
			.extracting(StudentCandidate::studentId)
			.containsExactly(hailu);
		assertThat(this.people.studentCandidates(this.schoolId, PersonNames.fold("Hailu", "Bekele", "Tesfaye"), LocalDate.of(2010, 1, 1),
				0.6, 5)).isEmpty();
		// another school never matches
		UUID other = this.personas.newSchool("Other name key school");
		assertThat(this.people.studentCandidates(other, "abebe kebede tadesse", dob, 0.6, 5)).isEmpty();
		// a withdrawn student is not a candidate
		call(HttpMethod.POST, "/api/v1/schools/" + this.schoolId + "/students/" + abebe + "/withdraw", this.admin, Map.of(), 200);
		assertThat(this.people.studentCandidates(this.schoolId, "abebe kebede tadesse", dob, 0.6, 5)).isEmpty();
	}

}
