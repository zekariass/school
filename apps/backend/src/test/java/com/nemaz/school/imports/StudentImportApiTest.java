package com.nemaz.school.imports;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.ImportTestSupport;
import com.nemaz.school.support.Persona;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** A students file end to end: upload, preview, mapping, dry run, error report, commit, and the same file again. */
@IntegrationTest
class StudentImportApiTest extends ImportTestSupport {

	static final String HEADER = "given_name,father_name,grandfather_name,date_of_birth,gender,student_number,class,"
			+ "guardian1_name,guardian1_phone,guardian1_relationship\r\n";

	static final String ROSTER = HEADER
			+ "Abebe,Kebede,Tadesse,2012-05-14,MALE,S-001,8A,Kebede Tadesse,0911000001,FATHER\r\n"
			+ "አልማዝ,ተስፋዬ,በቀለ,14/05/2013,ሴት,S-002,8A,,,\r\n"
			+ "Chaltu,Bekele,,2011-03-02,F,S-003,,,,\r\n"
			+ "Dawit,Alemu,Girma,2012-12-45,MALE,S-004,8A,,,\r\n"
			+ "Abebe,Kebede,Tadesse,2012-05-14,MALE,S-001,8A,,,\r\n"
			+ "\"=cmd|' /C calc'!A0\",Test,,2012-01-01,xyz,S-006,,,,\r\n"
			+ "Eve,Unknown,,2012-01-01,MALE,S-007,9Z,,,\r\n";

	@Autowired
	MessageSource messages;

	@Autowired
	com.nemaz.school.support.DomainEventRecorder events;

	UUID schoolId;

	Persona admin;

	UUID class8A;

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Import school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "import-admin");
		this.class8A = this.personas.newClass(this.schoolId, "8A");
	}

	private int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void aCsvFileIsPreviewedCheckedReportedAndCommitted() throws Exception {
		UUID media = uploadCsv(this.admin, this.schoolId, ROSTER);
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", media, null);

		// preview: what was detected, the header, the first rows, the suggested mapping
		assertThat(preview.get("job").get("status").asString()).isEqualTo("UPLOADED");
		assertThat(preview.get("detected").get("encoding").asString()).isEqualTo("UTF-8");
		assertThat(preview.get("detected").get("delimiter").asString()).isEqualTo(",");
		assertThat(preview.get("headers")).hasSize(10);
		assertThat(preview.get("previewRows")).hasSize(7);
		assertThat(preview.get("previewRows").get(1).get("cells").get(0).asString()).isEqualTo("አልማዝ");
		assertThat(preview.get("suggestedMapping").get("given_name").asInt()).isZero();
		assertThat(preview.get("suggestedMapping").get("guardian1_phone").asInt()).isEqualTo(8);
		assertThat(preview.get("missingFields")).isEmpty();
		assertThat(preview.get("requiredFields").toString()).contains("given_name", "father_name", "date_of_birth");
		assertThat(preview.get("job").get("progress").get("total").asInt()).isEqualTo(7);
		UUID jobId = UUID.fromString(preview.get("job").get("id").asString());

		// mapping, then the dry run in the background
		JsonNode mapped = saveMapping(this.admin, this.schoolId, jobId, mappingOf(preview), null);
		assertThat(mapped.get("job").get("status").asString()).isEqualTo("PREVIEWED");
		call(HttpMethod.POST, imports(this.schoolId) + "/" + jobId + "/dry-run", this.admin, null, 202);
		JsonNode checked = awaitJob(this.admin, this.schoolId, jobId, "VALIDATED", "FAILED");
		assertThat(checked.get("status").asString()).as(checked.toString()).isEqualTo("VALIDATED");
		JsonNode counts = checked.get("rows");
		assertThat(counts.get("total").asInt()).isEqualTo(7);
		assertThat(counts.get("valid").asInt()).isEqualTo(3);
		assertThat(counts.get("invalid").asInt()).isEqualTo(4);
		assertThat(counts.get("toCreate").asInt()).isEqualTo(3);
		assertThat(counts.get("duplicates").asInt()).isZero();
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId))
			.as("a dry run writes nothing to the school").isZero();

		// rows: line numbers, errors in the reader's language, normalized values
		List<JsonNode> invalid = rows(this.admin, this.schoolId, jobId, "INVALID");
		assertThat(invalid).extracting((r) -> r.get("rowNumber").asInt()).containsExactly(5, 6, 7, 8);
		assertThat(problemCodes(invalid.get(0), "errors")).isEqualTo("date_invalid");
		assertThat(invalid.get(0).get("errors").get(0).get("column").asString()).isEqualTo("date_of_birth");
		assertThat(invalid.get(0).get("errors").get(0).get("message").asString()).isNotBlank();
		assertThat(problemCodes(invalid.get(1), "errors")).isEqualTo("duplicate_in_file");
		assertThat(invalid.get(1).get("errors").get(0).get("message").asString()).contains("2");
		assertThat(problemCodes(invalid.get(2), "errors")).isEqualTo("gender_invalid");
		assertThat(problemCodes(invalid.get(3), "errors")).isEqualTo("class_not_found");
		List<JsonNode> valid = rows(this.admin, this.schoolId, jobId, "VALID");
		assertThat(valid).hasSize(3);
		JsonNode almaz = valid.get(1).get("normalized");
		assertThat(almaz.get("givenName").asString()).isEqualTo("አልማዝ");
		assertThat(almaz.get("dateOfBirth").asString()).isEqualTo("2013-05-14");
		assertThat(almaz.get("gender").asString()).isEqualTo("FEMALE");
		assertThat(valid.get(0).get("plannedAction").asString()).isEqualTo("CREATE");
		assertThat(valid.get(0).get("normalized").get("guardians").get(0).get("phone").asString()).isEqualTo("+251911000001");

		// the error report: UTF-8 BOM, CRLF, formula-safe, in the reader's language
		MockHttpServletResponse report = this.mvc
			.perform(get(imports(this.schoolId) + "/" + jobId + "/error-report").with(this.admin.bearer())
				.header("Accept-Language", "am"))
			.andReturn()
			.getResponse();
		assertThat(report.getStatus()).isEqualTo(200);
		assertThat(report.getHeader("Content-Disposition")).startsWith("attachment").contains(".csv");
		assertThat(report.getHeader("Cache-Control")).contains("no-store");
		assertThat(report.getContentType()).startsWith("text/csv");
		byte[] body = report.getContentAsByteArray();
		assertThat(body[0] & 0xFF).isEqualTo(0xEF);
		assertThat(body[1] & 0xFF).isEqualTo(0xBB);
		assertThat(body[2] & 0xFF).isEqualTo(0xBF);
		String text = new String(body, StandardCharsets.UTF_8).substring(1);
		assertThat(text).contains("\r\n").doesNotContain("\r\n\r\n");
		assertThat(text).contains("'=cmd|' /C calc'!A0").as("the formula is neutralised").doesNotContain(",=cmd").doesNotContain("\"=cmd");
		List<CSVRecord> records = CSVFormat.DEFAULT.parse(new StringReader(text)).getRecords();
		assertThat(records.get(0).toList()).endsWith("row_number", "severity", "field", "message");
		assertThat(records.get(0).toList().subList(0, 3)).containsExactly("given_name", "father_name", "grandfather_name");
		CSVRecord dateError = records.stream().filter((r) -> "5".equals(r.get(10))).findFirst().orElseThrow();
		assertThat(dateError.get(11)).isEqualTo("error");
		assertThat(dateError.get(12)).isEqualTo("date_of_birth");
		assertThat(dateError.get(13)).isEqualTo(this.messages.getMessage("import.row.date_invalid", null, Locale.forLanguageTag("am")));
		assertThat(dateError.get(13)).as("Amharic").matches(".*[\\u1200-\\u137F].*");

		// commit is refused while rows are invalid, then goes through with skipInvalidRows
		JsonNode refused = commit(this.admin, this.schoolId, jobId, false, 409);
		assertThat(refused.get("code").asString()).isEqualTo("import.has_invalid_rows");
		JsonNode accepted = commit(this.admin, this.schoolId, jobId, true, 202);
		assertThat(accepted.get("status").asString()).isEqualTo("COMMITTING");
		JsonNode done = awaitJob(this.admin, this.schoolId, jobId, "COMPLETED", "FAILED");
		assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("COMPLETED");
		assertThat(done.get("result").get("created").asInt()).isEqualTo(3);
		assertThat(done.get("result").get("skipped").asInt()).isEqualTo(4);
		assertThat(done.get("progress").get("done").asInt()).isEqualTo(7);

		// the students exist with their data, in their class; Ethiopic names intact
		List<Map<String, Object>> students = this.jdbc.queryForList("""
				select u.given_name, u.middle_name, u.family_name, u.date_of_birth, u.gender, u.account_type, ss.student_number,
				       sp.name_fold
				from school_students ss join users u on u.id = ss.student_id join student_profiles sp on sp.user_id = ss.student_id
				where ss.school_id = ? order by ss.student_number""", this.schoolId);
		assertThat(students).hasSize(3);
		assertThat(students.get(0)).containsEntry("given_name", "Abebe")
			.containsEntry("middle_name", "Kebede")
			.containsEntry("family_name", "Tadesse")
			.containsEntry("gender", "MALE")
			.containsEntry("account_type", "MANAGED")
			.containsEntry("name_fold", "abebe kebede tadesse");
		assertThat(students.get(1)).containsEntry("given_name", "አልማዝ").containsEntry("gender", "FEMALE");
		assertThat(students.get(1).get("date_of_birth").toString()).isEqualTo("2013-05-14");
		assertThat(count("select count(*) from class_students where class_id = ? and status = 'ACTIVE'", this.class8A)).isEqualTo(2);

		// guardian contacts are staged, never accounts or links (R-29)
		assertThat(count("select count(*) from guardian_contact_candidates where school_id = ? and phone_e164 = '+251911000001'"
				+ " and relationship_type = 'FATHER' and status = 'PENDING'", this.schoolId)).isEqualTo(1);
		assertThat(count("select count(*) from users where phone_e164 = '+251911000001'")).isZero();

		// the usual events were published and the commit was audited without personal data
		assertThat(this.events.of(com.nemaz.school.people.StudentEnrolledInSchool.class)).filteredOn((e) -> e.schoolId().equals(this.schoolId))
			.hasSize(3);
		assertThat(this.events.of(com.nemaz.school.academic.ClassMembershipChanged.class)).filteredOn((e) -> e.schoolId().equals(this.schoolId))
			.hasSize(2);
		Map<String, Object> audit = this.jdbc.queryForMap(
				"select metadata::text as m from audit_logs where action = 'import.commit' and school_id = ?", this.schoolId);
		assertThat((String) audit.get("m")).contains("\"created\": 3").doesNotContain("Abebe").doesNotContain("0911");
		assertThat(this.jdbc.queryForList("select metadata::text from audit_logs where school_id = ? and action like 'import.%'",
				String.class, this.schoolId)).noneMatch((m) -> m != null && (m.contains("Abebe") || m.contains("አልማዝ")));
		assertThat(count("select count(*) from notifications where user_id = ? and type_code = 'IMPORT_COMPLETED'",
				this.admin.userId())).isEqualTo(2);

		// a finished job cannot be committed again
		assertThat(commit(this.admin, this.schoolId, jobId, true, 409).get("code").asString()).isEqualTo("import.invalid_state");

		// the same file again changes nothing: every valid row is recognised (student number) and unchanged
		UUID again = uploadCsv(this.admin, this.schoolId, ROSTER);
		JsonNode second = validated(this.admin, this.schoolId, "STUDENTS", again, null);
		assertThat(second.get("status").asString()).isEqualTo("VALIDATED");
		assertThat(second.get("rows").get("toCreate").asInt()).isZero();
		assertThat(second.get("rows").get("unchanged").asInt()).isEqualTo(3);
		List<JsonNode> secondRows = rows(this.admin, this.schoolId, id(second), "VALID");
		assertThat(secondRows).allMatch((r) -> "NONE".equals(r.get("plannedAction").asString()));
		commit(this.admin, this.schoolId, id(second), true, 202);
		JsonNode replay = awaitJob(this.admin, this.schoolId, id(second), "COMPLETED", "FAILED");
		assertThat(replay.get("status").asString()).as(replay.toString()).isEqualTo("COMPLETED");
		assertThat(replay.get("result").get("created").asInt()).isZero();
		assertThat(replay.get("result").get("updated").asInt()).isZero();
		assertThat(replay.get("result").get("unchanged").asInt()).isEqualTo(3);
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isEqualTo(3);
		assertThat(count("select count(*) from guardian_contact_candidates where school_id = ?", this.schoolId)).isEqualTo(1);
		assertThat(count("select count(*) from class_students where class_id = ? and status = 'ACTIVE'", this.class8A)).isEqualTo(2);
	}

}
