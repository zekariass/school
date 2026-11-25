package com.nemaz.school.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.support.ImportTestSupport;
import com.nemaz.school.support.Persona;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Duplicates, calendars, workbooks, changed mappings, concurrency and resuming a failed commit. */
@IntegrationTest
class StudentImportScenariosApiTest extends ImportTestSupport {

	@Autowired
	SubscriptionService subscriptions;

	UUID schoolId;

	Persona admin;

	UUID class8A;

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Scenario school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "scenario-admin");
		this.class8A = this.personas.newClass(this.schoolId, "8A");
	}

	private int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	/** An existing student created through the application (name key and all). */
	private UUID student(String given, String father, String grand, String dob, String number, UUID classId) throws Exception {
		Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("givenName", given, "middleName", father, "familyName", grand,
				"dateOfBirth", dob));
		if (number != null) {
			body.put("studentNumber", number);
		}
		if (classId != null) {
			body.put("classId", classId.toString());
		}
		return UUID.fromString(call(HttpMethod.POST, "/api/v1/schools/" + this.schoolId + "/students", this.admin, body, 201)
			.get("id")
			.asString());
	}

	@Test
	void possibleDuplicatesAreDecidedByTheAdministratorAndNeverGuessed() throws Exception {
		UUID almaz = student("Almaz", "Tesfaye", "Bekele", "2012-05-14", null, null);
		UUID inClass = student("Chaltu", "Bekele", "Alemu", "2011-03-02", null, this.class8A);
		student("ሐይሉ", "በቀለ", "ተስፋዬ", "2010-01-01", null, null);
		String csv = "given_name,father_name,grandfather_name,date_of_birth,class\n"
				// same name and birth date but nothing else in common: a possible duplicate
				+ "Almaz,Tesfaye,Bekele,2012-05-14,\n"
				// the same, plus the same class: the same student, matched without asking
				+ "Chaltu,Bekele,Alemu,2011-03-02,8A\n"
				// Ethiopic homophones (ሐ and ሀ) are the same letter: a possible duplicate
				+ "ሀይሉ,በቀለ,ተስፋዬ,2010-01-01,\n"
				// birth date a year off: still offered
				+ "Almaz,Tesfaye,Bekele,2013-05-14,\n"
				// a different person
				+ "Zeru,Mekonnen,Hailu,2012-02-02,\n";
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv), null);
		UUID jobId = id(job);
		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("VALIDATED");
		assertThat(job.get("rows").get("duplicates").asInt()).isEqualTo(3);
		List<JsonNode> all = rows(this.admin, this.schoolId, jobId, null);
		assertThat(all.get(0).get("status").asString()).isEqualTo("DUPLICATE");
		JsonNode candidate = all.get(0).get("duplicateCandidates").get(0);
		assertThat(candidate.get("studentId").asString()).isEqualTo(almaz.toString());
		assertThat(candidate.get("birthDate").asString()).isEqualTo("SAME");
		assertThat(candidate.get("similarity").asDouble()).isGreaterThanOrEqualTo(0.9);
		assertThat(all.get(1).get("status").asString()).isEqualTo("VALID");
		assertThat(all.get(1).get("plannedAction").asString()).isEqualTo("NONE");
		assertThat(all.get(1).get("normalized").get("matchedId").asString()).isEqualTo(inClass.toString());
		assertThat(problemCodes(all.get(1), "warnings")).contains("matched_by_name");
		assertThat(all.get(2).get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(all.get(3).get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(all.get(3).get("duplicateCandidates").get(0).get("birthDate").asString()).isEqualTo("WITHIN_A_YEAR");
		assertThat(all.get(4).get("status").asString()).isEqualTo("VALID");
		assertThat(all.get(4).get("plannedAction").asString()).isEqualTo("CREATE");

		// commit waits for the decisions
		assertThat(commit(this.admin, this.schoolId, jobId, false, 409).get("code").asString()).isEqualTo("import.has_open_duplicates");
		String base = imports(this.schoolId) + "/" + jobId + "/rows/";
		JsonNode linked = call(HttpMethod.POST, base + all.get(0).get("id").asString() + "/resolve", this.admin,
				Map.of("resolution", "LINK", "studentId", almaz.toString()), 200);
		assertThat(linked.get("status").asString()).isEqualTo("VALID");
		assertThat(linked.get("resolution").asString()).isEqualTo("LINK");
		call(HttpMethod.POST, base + all.get(2).get("id").asString() + "/resolve", this.admin, Map.of("resolution", "CREATE_NEW"), 200);
		call(HttpMethod.POST, base + all.get(3).get("id").asString() + "/resolve", this.admin, Map.of("resolution", "SKIP"), 200);
		// LINK needs a student of this school
		call(HttpMethod.POST, base + all.get(2).get("id").asString() + "/resolve", this.admin,
				Map.of("resolution", "LINK", "studentId", Ids.newId().toString()), 404);
		assertThat(job(this.admin, this.schoolId, jobId).get("rows").get("duplicates").asInt()).isZero();

		commit(this.admin, this.schoolId, jobId, false, 202);
		JsonNode done = awaitJob(this.admin, this.schoolId, jobId, "COMPLETED", "FAILED");
		assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("COMPLETED");
		assertThat(done.get("result").get("created").asInt()).as("CREATE_NEW and the different person").isEqualTo(2);
		assertThat(done.get("result").get("skipped").asInt()).isEqualTo(1);
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isEqualTo(3 + 2);
		assertThat(count("select count(*) from class_students where class_id = ? and student_id = ?", this.class8A, inClass)).isEqualTo(1);
	}

	@Test
	void ethiopianDatesAreConvertedWhenTheImportSaysSo() throws Exception {
		// 6 Meskerem 2005 E.C. is 16 September 2012; Pagume 5 of 2004 E.C. is 10 September 2012
		String csv = "given_name,father_name,date_of_birth\nAbebe,Kebede,2005-01-06\nAlmaz,Tesfaye,05/13/2004\nDawit,Alemu,2005-13-02\nEve,Zed,2018-09-01\n";
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv),
				Map.of("dateSystem", "ETHIOPIAN"));
		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("VALIDATED");
		List<JsonNode> all = rows(this.admin, this.schoolId, id(job), null);
		assertThat(all.get(0).get("normalized").get("dateOfBirth").asString()).isEqualTo("2012-09-16");
		assertThat(all.get(1).get("normalized").get("dateOfBirth").asString()).isEqualTo("2012-09-10");
		assertThat(all.get(2).get("status").asString()).as("Pagume 2 of 2005 is not a leap-year day").isEqualTo("VALID");
		// an Ethiopian year typed as 2018 is a one-year-old: the other calendar would make a believable age
		assertThat(problemCodes(all.get(3), "warnings")).contains("age_implausible", "date_maybe_other_calendar");
		// the same file read as Gregorian: month 13 is explained
		JsonNode gregorian = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv), null);
		List<JsonNode> g = rows(this.admin, this.schoolId, id(gregorian), null);
		assertThat(problemCodes(g.get(1), "errors")).as("day 05 of month 13").isEqualTo("date_month13");
		assertThat(problemCodes(g.get(2), "errors")).isEqualTo("date_month13");
		assertThat(g.get(0).get("status").asString()).isEqualTo("VALID");
	}

	@Test
	void aWorkbookWithNumericPhonesAFormulaAMergedCellAndAHiddenSheet() throws Exception {
		byte[] xlsx = workbook((wb) -> {
			Sheet hidden = wb.createSheet("Notes");
			hidden.createRow(0).createCell(0).setCellValue("not the roster");
			Sheet sheet = wb.createSheet("Roster");
			String[] headers = { "First Name", "Father Name", "Date of Birth", "Class", "Guardian Phone", "Student No" };
			Row head = sheet.createRow(0);
			for (int i = 0; i < headers.length; i++) {
				head.createCell(i).setCellValue(headers[i]);
			}
			CellStyle date = wb.createCellStyle();
			date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("m/d/yy"));
			Row r1 = sheet.createRow(1);
			r1.createCell(0).setCellValue("Abebe");
			r1.createCell(1).setCellValue("Kebede");
			var dob = r1.createCell(2);
			dob.setCellValue(java.sql.Date.valueOf("2012-05-14"));
			dob.setCellStyle(date);
			r1.createCell(3).setCellValue("8A");
			r1.createCell(4).setCellValue(911000002d);
			r1.createCell(5).setCellFormula("\"S-\"&TEXT(1,\"000\")");
			Row r2 = sheet.createRow(2);
			r2.createCell(0).setCellValue("Almaz");
			r2.createCell(1).setCellValue("Tesfaye");
			var dob2 = r2.createCell(2);
			dob2.setCellValue(java.sql.Date.valueOf("2013-01-02"));
			dob2.setCellStyle(date);
			Row r3 = sheet.createRow(3);
			r3.createCell(0).setCellValue("Chaltu");
			r3.createCell(1).setCellValue("Bekele");
			r3.createCell(2).setCellValue("2011-03-02");
			sheet.addMergedRegion(new CellRangeAddress(1, 2, 3, 3));
			wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
			wb.setSheetHidden(0, true);
		});
		UUID media = upload(this.admin, this.schoolId, "roster.xlsx", XLSX, xlsx);
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", media, null);
		assertThat(preview.get("detected").get("format").asString()).isEqualTo("XLSX");
		assertThat(preview.get("detected").get("sheet").asString()).isEqualTo("Roster");
		assertThat(preview.get("detected").get("sheetNames").toString()).doesNotContain("Notes");
		assertThat(preview.get("detected").get("formulaCells").asInt()).isEqualTo(1);
		assertThat(preview.get("detected").get("mergedRegions").asInt()).isGreaterThan(0);
		assertThat(preview.get("suggestedMapping").get("given_name").asInt()).isZero();
		assertThat(preview.get("suggestedMapping").get("student_number").asInt()).isEqualTo(5);
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", media, null);
		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("VALIDATED");
		List<JsonNode> all = rows(this.admin, this.schoolId, id(job), null);
		assertThat(all).hasSize(3);
		assertThat(all.get(0).get("status").asString()).as(all.get(0).toString()).isEqualTo("VALID");
		assertThat(all.get(0).get("normalized").get("dateOfBirth").asString()).isEqualTo("2012-05-14");
		assertThat(all.get(0).get("normalized").get("guardians").get(0).get("phone").asString()).as("a numeric phone cell keeps its digits")
			.isEqualTo("+251911000002");
		assertThat(all.get(0).get("normalized").get("studentNumber").asString()).as("the formula's saved value").isEqualTo("S-001");
		assertThat(problemCodes(all.get(0), "warnings")).contains("formula_cell", "merged_cell");
		// the merged cell gave its class to the first row only: the second row has none
		assertThat(all.get(1).get("normalized").has("classId")).isFalse();
		assertThat(all.get(0).get("normalized").get("className").asString()).isEqualTo("8A");
		// a text date in a text cell is read like in a CSV
		assertThat(all.get(2).get("normalized").get("dateOfBirth").asString()).isEqualTo("2011-03-02");
		commit(this.admin, this.schoolId, id(job), false, 202);
		assertThat(awaitJob(this.admin, this.schoolId, id(job), "COMPLETED", "FAILED").get("status").asString()).isEqualTo("COMPLETED");
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isEqualTo(3);
	}

	@Test
	void numericGuardianPhonesBecomeE164() throws Exception {
		byte[] xlsx = workbook((wb) -> {
			Sheet sheet = wb.createSheet("Roster");
			String[] headers = { "given_name", "father_name", "date_of_birth", "guardian1_phone", "guardian1_name" };
			Row head = sheet.createRow(0);
			for (int i = 0; i < headers.length; i++) {
				head.createCell(i).setCellValue(headers[i]);
			}
			Row r = sheet.createRow(1);
			r.createCell(0).setCellValue("Abebe");
			r.createCell(1).setCellValue("Kebede");
			r.createCell(2).setCellValue("2012-05-14");
			r.createCell(3).setCellValue(9.11000003E8);
			r.createCell(4).setCellValue("Kebede");
		});
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", upload(this.admin, this.schoolId, "g.xlsx", XLSX, xlsx), null);
		JsonNode row = rows(this.admin, this.schoolId, id(job), null).getFirst();
		assertThat(row.get("normalized").get("guardians").get(0).get("phone").asString()).isEqualTo("+251911000003");
	}

	@Test
	void aCommitIsRefusedWhenTheMappingChangedAfterTheDryRun() throws Exception {
		String csv = "given_name,father_name,date_of_birth,class\nAbebe,Kebede,2012-05-14,8A\n";
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv), null);
		UUID jobId = id(job);
		// changed behind the API's back: the dry run no longer describes what would be applied
		this.jdbc.update("update import_jobs set column_mapping = column_mapping - 'class' where id = ?", jobId);
		assertThat(commit(this.admin, this.schoolId, jobId, false, 409).get("code").asString()).isEqualTo("import.changed_since_dry_run");
		// changed through the API: back to PREVIEWED, the checked rows are gone, committing is not possible
		JsonNode preview = call(HttpMethod.GET, imports(this.schoolId) + "/" + jobId + "/preview", this.admin, null, 200);
		saveMapping(this.admin, this.schoolId, jobId, mappingOf(preview), null);
		assertThat(job(this.admin, this.schoolId, jobId).get("status").asString()).isEqualTo("PREVIEWED");
		assertThat(commit(this.admin, this.schoolId, jobId, false, 409).get("code").asString()).isEqualTo("import.invalid_state");
		assertThat(count("select count(*) from import_job_rows where import_job_id = ?", jobId)).isZero();
		// an incomplete mapping is refused with what is missing
		JsonNode refused = call(HttpMethod.PUT, imports(this.schoolId) + "/" + jobId + "/mapping", this.admin,
				Map.of("columnMapping", Map.of("given_name", 0)), 400);
		assertThat(refused.get("code").asString()).isEqualTo("import.mapping_incomplete");
		assertThat(refused.get("detail").asString()).contains("father_name");
		call(HttpMethod.PUT, imports(this.schoolId) + "/" + jobId + "/mapping", this.admin,
				Map.of("columnMapping", Map.of("given_name", 0, "father_name", 9, "date_of_birth", 2)), 400);
	}

	@Test
	void onlyOneOfTwoSimultaneousCommitsRuns() throws Exception {
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\nAlmaz,Tesfaye,2013-01-02\n"), null);
		UUID jobId = id(job);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				calls.add(() -> perform(HttpMethod.POST, imports(this.schoolId) + "/" + jobId + "/commit", this.admin, Map.of(), null)
					.andReturn()
					.getResponse()
					.getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		}
		finally {
			pool.shutdownNow();
		}
		awaitJob(this.admin, this.schoolId, jobId, "COMPLETED");
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isEqualTo(2);
	}

	@Test
	void aFailureMidWayKeepsTheCommittedBatchesAndAResumeFinishesTheRest() throws Exception {
		this.subscriptions.assignToSchool(this.schoolId, new AssignSubscription("ENTERPRISE", null, null), null);
		StringBuilder csv = new StringBuilder("given_name,father_name,date_of_birth,student_number\n");
		for (int i = 1; i <= 130; i++) {
			csv.append("Student").append(String.format("%03d", i)).append(",Father,2012-05-14,N-").append(i).append('\n');
		}
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv.toString()), null);
		UUID jobId = id(job);
		assertThat(job.get("rows").get("toCreate").asInt()).isEqualTo(130);
		// after the check, somebody else takes the number of row 120 (data row 120 is file row 121)
		UUID squatter = this.personas.newStudent(this.schoolId, "squatter");
		this.jdbc.update("update school_students set student_number = 'N-120' where student_id = ?", squatter);

		commit(this.admin, this.schoolId, jobId, false, 202);
		JsonNode failed = awaitJob(this.admin, this.schoolId, jobId, "FAILED", "COMPLETED");
		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.get("failure").get("phase").asString()).isEqualTo("COMMIT");
		assertThat(failed.get("failure").get("rowNumber").asInt()).isEqualTo(121);
		assertThat(failed.get("failure").get("code").asString()).isEqualTo("student.number_taken");
		assertThat(failed.get("failure").get("message").asString()).isNotBlank();
		assertThat(count("select count(*) from school_students where school_id = ? and student_number like 'N-%' and student_id <> ?",
				this.schoolId, squatter)).as("the first batch of 100 stays").isEqualTo(100);

		// the conflict is removed and the same call resumes: nothing is created twice
		this.jdbc.update("update school_students set student_number = null where student_id = ?", squatter);
		commit(this.admin, this.schoolId, jobId, false, 202);
		JsonNode done = awaitJob(this.admin, this.schoolId, jobId, "COMPLETED", "FAILED");
		assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("COMPLETED");
		assertThat(done.get("result").get("created").asInt()).isEqualTo(130);
		assertThat(count("select count(*) from school_students where school_id = ? and student_number like 'N-%'", this.schoolId))
			.isEqualTo(130);
		assertThat(count("select count(*) from users u join school_students ss on ss.student_id = u.id where ss.school_id = ? and u.given_name like 'Student%'",
				this.schoolId)).isEqualTo(130);
	}

}
