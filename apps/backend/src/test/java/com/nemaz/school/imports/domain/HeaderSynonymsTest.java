package com.nemaz.school.imports.domain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderSynonymsTest {

	@Test
	void machineKeysMapToThemselvesInAnyCaseOrSpacing() {
		Map<String, Integer> m = HeaderSynonyms.suggest(ImportType.STUDENTS,
				List.of("Given_Name", "FATHER NAME", "grandfather-name", "Date Of Birth", "gender", "Student No", "Class"));
		assertThat(m).containsEntry("given_name", 0)
			.containsEntry("father_name", 1)
			.containsEntry("grandfather_name", 2)
			.containsEntry("date_of_birth", 3)
			.containsEntry("gender", 4)
			.containsEntry("student_number", 5)
			.containsEntry("class", 6);
	}

	@Test
	void englishSynonymsOfRealWorldFiles() {
		Map<String, Integer> m = HeaderSynonyms.suggest(ImportType.STUDENTS,
				List.of("First Name", "Middle Name", "Last Name", "DOB", "Sex", "Admission No", "Section", "Parent Phone",
						"Parent Name"));
		assertThat(m).containsEntry("given_name", 0)
			.containsEntry("father_name", 1)
			.containsEntry("grandfather_name", 2)
			.containsEntry("date_of_birth", 3)
			.containsEntry("gender", 4)
			.containsEntry("student_number", 5)
			.containsEntry("class", 6)
			.containsEntry("guardian1_phone", 7)
			.containsEntry("guardian1_name", 8);
	}

	@Test
	void amharicHeadersAndHomophonesMatch() {
		Map<String, Integer> m = HeaderSynonyms.suggest(ImportType.STUDENTS,
				List.of("የመጀመሪያ ስም", "የአባት ስም", "የአያት ስም", "የትውልድ ቀን", "ጾታ", "ክፍል", "የተማሪ ቁጥር", "ስልክ"));
		assertThat(m).containsEntry("given_name", 0)
			.containsEntry("father_name", 1)
			.containsEntry("grandfather_name", 2)
			.containsEntry("date_of_birth", 3)
			.containsEntry("gender", 4)
			.containsEntry("class", 5)
			.containsEntry("student_number", 6);
		// ፆታ and ጾታ are the same word for matching
		assertThat(HeaderSynonyms.suggest(ImportType.STUDENTS, List.of("ፆታ"))).containsEntry("gender", 0);
	}

	@Test
	void eachColumnAndFieldIsUsedOnceAndTheKeyBeatsASynonym() {
		Map<String, Integer> m = HeaderSynonyms.suggest(ImportType.ENROLLMENTS, List.of("class_name", "student_number", "class"));
		assertThat(m).containsEntry("class_name", 0).containsEntry("student_number", 1).doesNotContainValue(2);
		// the same synonym twice maps the first one only
		Map<String, Integer> twice = HeaderSynonyms.suggest(ImportType.STAFF, List.of("Phone", "Mobile", "Email"));
		assertThat(twice).containsEntry("phone", 0).containsEntry("email", 2).doesNotContainValue(1);
	}

	@Test
	void everyTypeKnowsItsRequiredFields() {
		assertThat(ImportType.STUDENTS.requiredFields()).containsExactly("given_name", "father_name", "date_of_birth");
		assertThat(ImportType.STAFF.requiredFields()).contains("given_name", "father_name", "staff_type", "phone|email");
		assertThat(ImportType.ENROLLMENTS.requiredFields()).containsExactlyInAnyOrder("class_name", "student_number|external_id");
		assertThat(ImportType.STUDENTS.missing(java.util.Set.of("given_name", "father_name"))).containsExactly("date_of_birth");
		assertThat(ImportType.STAFF.missing(java.util.Set.of("given_name", "father_name", "staff_type", "email"))).isEmpty();
	}

}
