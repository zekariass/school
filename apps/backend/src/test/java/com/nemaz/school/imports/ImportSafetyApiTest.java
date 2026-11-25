package com.nemaz.school.imports;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.imports.application.ImportMaintenance;
import com.nemaz.school.support.ImportTestSupport;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas.Name;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limits, isolation, staff safety, the other import types, retention, profiles, templates and redelivery. */
@IntegrationTest
class ImportSafetyApiTest extends ImportTestSupport {

	@Autowired
	ImportMaintenance maintenance;

	@Autowired
	ApplicationEventPublisher publisher;

	@TempDir
	Path dir;

	UUID schoolId;

	Persona admin;

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Safety school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "safety-admin");
	}

	private int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	private JsonNode refused(UUID media, String type, String code) throws Exception {
		JsonNode problem = call(HttpMethod.POST, imports(this.schoolId), this.admin,
				Map.of("importType", type, "mediaId", media.toString()), 400);
		assertThat(problem.get("code").asString()).isEqualTo(code);
		return problem;
	}

	// --- limits ---------------------------------------------------------------------------

	@Test
	void rowColumnCellAndEncodingLimitsAreEnforced() throws Exception {
		StringBuilder rows = new StringBuilder("given_name,father_name,date_of_birth\n");
		for (int i = 0; i < 20_001; i++) {
			rows.append("A,B,2012-05-14\n");
		}
		refused(uploadCsv(this.admin, this.schoolId, rows.toString()), "STUDENTS", "import.too_many_rows");
		// exactly 20,000 rows is accepted
		StringBuilder ok = new StringBuilder("given_name,father_name,date_of_birth\n");
		for (int i = 0; i < 20_000; i++) {
			ok.append("A,B,2012-05-14\n");
		}
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, ok.toString()), null);
		assertThat(preview.get("job").get("progress").get("total").asInt()).isEqualTo(20_000);

		StringBuilder wide = new StringBuilder();
		for (int i = 0; i < 61; i++) {
			wide.append(i == 0 ? "" : ",").append("c").append(i);
		}
		refused(uploadCsv(this.admin, this.schoolId, wide + "\n" + wide + "\n"), "STUDENTS", "import.too_many_columns");
		refused(uploadCsv(this.admin, this.schoolId, "given_name,father_name\n" + "x".repeat(1001) + ",b\n"), "STUDENTS",
				"import.cell_too_long");
		// bytes that are not UTF-8 are read as windows-1252, unless the user insists on UTF-8
		UUID latin = upload(this.admin, this.schoolId, "latin.csv", CSV,
				"given_name,father_name,date_of_birth\nJosé,García,2012-05-14\n".getBytes(java.nio.charset.Charset.forName("windows-1252")));
		assertThat(createImport(this.admin, this.schoolId, "STUDENTS", latin, null).get("detected").get("encoding").asString())
			.isEqualTo("windows-1252");
		call(HttpMethod.POST, imports(this.schoolId), this.admin,
				Map.of("importType", "STUDENTS", "mediaId", latin.toString(), "options", Map.of("encoding", "UTF-8")), 400);
		assertThat(createImport(this.admin, this.schoolId, "STUDENTS", latin, Map.of("encoding", "windows-1252")).get("previewRows")
			.get(0)
			.get("cells")
			.get(0)
			.asString()).isEqualTo("José");
	}

	@Test
	void theUploadRefusesTooLargeOldAndMacroFilesBeforeTheyAreStored() throws Exception {
		requestUpload(this.admin, "IMPORT_FILE", "big.csv", CSV, 10L * 1024 * 1024 + 1, school(this.schoolId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.too_large"));
		requestUpload(this.admin, "IMPORT_FILE", "old.xls", "application/vnd.ms-excel", 1000, school(this.schoolId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"));
		requestUpload(this.admin, "IMPORT_FILE", "macros.xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12", 1000, school(this.schoolId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"));
		requestUpload(this.admin, "IMPORT_FILE", "sneaky.xlsx", CSV, 1000, school(this.schoolId))
			.andExpect(status().isBadRequest());
		// an OLE2 workbook renamed .csv is caught by its content
		byte[] ole2 = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0 };
		JsonNode ticket = newUpload(this.admin, "IMPORT_FILE", "old.csv", "application/vnd.ms-excel", ole2, school(this.schoolId));
		assertThat(putToStorage(ticket.get("upload"), ole2, "application/vnd.ms-excel")).isEqualTo(200);
		complete(this.admin, ticket.get("mediaId").asString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_mismatch"));
		// a UTF-16 file with a byte order mark (Excel "Unicode Text") is text, a binary blob with NULs is not
		byte[] unicodeText = "﻿given_name\tfather_name\tdate_of_birth\r\nAbebe\tKebede\t2012-05-14\r\n".getBytes(StandardCharsets.UTF_16LE);
		UUID utf16 = upload(this.admin, this.schoolId, "unicode.txt.csv", "text/plain", unicodeText);
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", utf16, null);
		assertThat(preview.get("detected").get("encoding").asString()).isEqualTo("UTF-16LE");
		assertThat(preview.get("detected").get("delimiter").asString()).isEqualTo("\t");
		assertThat(preview.get("missingFields")).isEmpty();
		byte[] nulls = new byte[200];
		JsonNode blob = newUpload(this.admin, "IMPORT_FILE", "blob.csv", CSV, nulls, school(this.schoolId));
		putToStorage(blob.get("upload"), nulls, CSV);
		complete(this.admin, blob.get("mediaId").asString()).andExpect(status().isBadRequest());
	}

	@Test
	void workbookBombsAndMacrosAreRefusedByTheImporter() throws Exception {
		byte[] plain = workbook((wb) -> wb.createSheet("Roster").createRow(0).createCell(0).setCellValue("given_name"));
		Path source = this.dir.resolve("plain.xlsx");
		Files.write(source, plain);
		// a declared 120 MB sheet in a few kilobytes
		ByteArrayOutputStream bomb = new ByteArrayOutputStream();
		ByteArrayOutputStream macro = new ByteArrayOutputStream();
		try (ZipFile in = new ZipFile(source.toFile()); ZipOutputStream b = new ZipOutputStream(bomb);
				ZipOutputStream m = new ZipOutputStream(macro)) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				b.putNextEntry(new ZipEntry(e.getName()));
				m.putNextEntry(new ZipEntry(e.getName()));
				if (e.getName().equals("xl/worksheets/sheet1.xml")) {
					byte[] chunk = new byte[1024 * 1024];
					java.util.Arrays.fill(chunk, (byte) ' ');
					b.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>".getBytes());
					for (int i = 0; i < 120; i++) {
						b.write(chunk);
					}
					b.write("</sheetData></worksheet>".getBytes());
					m.write(in.getInputStream(e).readAllBytes());
				}
				else {
					try (InputStream data = in.getInputStream(e)) {
						byte[] all = data.readAllBytes();
						b.write(all);
						m.write(all);
					}
				}
				b.closeEntry();
				m.closeEntry();
			}
			m.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
			m.write(new byte[] { 1, 2, 3 });
			m.closeEntry();
		}
		assertThat(bomb.size()).isLessThan(1024 * 1024);
		refused(upload(this.admin, this.schoolId, "bomb.xlsx", XLSX, bomb.toByteArray()), "STUDENTS", "import.zip_limits");
		refused(upload(this.admin, this.schoolId, "macro.xlsx", XLSX, macro.toByteArray()), "STUDENTS", "import.unsupported_file");
	}

	// --- isolation and permissions --------------------------------------------------------

	@Test
	void anotherSchoolsAdministratorSeesNothingOfTheImport() throws Exception {
		UUID media = uploadCsv(this.admin, this.schoolId, "given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\n");
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", media, null);
		UUID jobId = UUID.fromString(preview.get("job").get("id").asString());
		UUID otherSchool = this.personas.newSchool("Other school");
		Persona other = this.personas.schoolAdmin(otherSchool, "other-admin");
		String base = imports(this.schoolId) + "/" + jobId;
		call(HttpMethod.GET, base, other, null, 404);
		call(HttpMethod.GET, base + "/rows", other, null, 404);
		call(HttpMethod.GET, base + "/error-report", other, null, 404);
		call(HttpMethod.POST, base + "/dry-run", other, null, 404);
		call(HttpMethod.POST, base + "/commit", other, Map.of(), 404);
		call(HttpMethod.DELETE, base, other, null, 404);
		// through their own school's URL the job id does not exist either
		call(HttpMethod.GET, imports(otherSchool) + "/" + jobId, other, null, 404);
		call(HttpMethod.GET, imports(otherSchool), other, null, 200);
		assertThat(call(HttpMethod.GET, imports(otherSchool), other, null, 200).get("items")).isEmpty();
		// a file of school A cannot start an import in school B
		call(HttpMethod.POST, imports(otherSchool), other, Map.of("importType", "STUDENTS", "mediaId", media.toString()), 404);
		// members without the permission are refused with 403, outsiders get 404 on the school
		call(HttpMethod.GET, base, this.personas.get(Name.TEACHER_LEAD_A), null, 404);
		Persona teacher = this.personas.personaFor("teacher", this.personas.newTeacher(this.schoolId, "import-teacher"));
		call(HttpMethod.GET, base, teacher, null, 403);
		call(HttpMethod.GET, imports(this.schoolId) + "/templates/STUDENTS", teacher, null, 403);
		// the files of the import are for importers only
		requestUpload(teacher, "IMPORT_FILE", "x.csv", CSV, 10, school(this.schoolId)).andExpect(status().isForbidden());
		assertThat(requestUpload(other, "IMPORT_FILE", "x.csv", CSV, 10, school(this.schoolId)).andReturn().getResponse().getStatus())
			.isIn(403, 404);
		this.mvc.perform(get("/api/v1/media/" + media + "/url").with(teacher.bearer())).andExpect(status().is4xxClientError());
		this.mvc.perform(get("/api/v1/media/" + media + "/url").with(this.admin.bearer())).andExpect(status().isOk());
	}

	@Test
	void staffImportsNeverGrantAdministrativeRoles() throws Exception {
		String existingPhone = com.nemaz.school.support.Personas.newPhone();
		UUID existing = this.personas.createUser("Existing Person", "ACTIVE");
		this.jdbc.update("update users set phone_e164 = ? where id = ?", existingPhone, existing);
		String national = "0" + existingPhone.substring(4);
		String csv = "given_name,father_name,grandfather_name,phone,email,staff_type,job_title,external_id\n"
				+ "Tigist,Alemu,Girma,0911000010,,TEACHER,Math teacher,T-1\n"
				+ "Berhanu,Kassa,,,berhanu@example.com,ADMINISTRATOR,Registrar,T-2\n"
				+ "Sara,Haile,,0911000011,,SCHOOL_ADMIN,,T-3\n"
				+ "Mulu,Bekele,,0911000012,,CAMPUS_ADMIN,,T-4\n"
				+ "Dup,Phone,,0911000010,,TEACHER,,T-5\n"
				+ "Existing,Person,," + national + ",,SUPPORT_STAFF,Cleaner,T-6\n"
				+ "Bad,Phone,,12,,TEACHER,,\n"
				+ "No,Contact,,,,TEACHER,,\n";
		JsonNode job = validated(this.admin, this.schoolId, "STAFF", uploadCsv(this.admin, this.schoolId, csv), null);
		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("VALIDATED");
		List<JsonNode> all = rows(this.admin, this.schoolId, id(job), null);
		assertThat(all.get(0).get("status").asString()).isEqualTo("VALID");
		assertThat(all.get(1).get("status").asString()).isEqualTo("VALID");
		assertThat(problemCodes(all.get(1), "warnings")).contains("admin_role_not_granted");
		assertThat(problemCodes(all.get(2), "errors")).isEqualTo("staff_type_forbidden");
		assertThat(problemCodes(all.get(3), "errors")).isEqualTo("staff_type_forbidden");
		assertThat(problemCodes(all.get(4), "errors")).isEqualTo("duplicate_in_file");
		assertThat(all.get(5).get("status").asString()).isEqualTo("VALID");
		assertThat(problemCodes(all.get(6), "errors")).isEqualTo("phone_invalid");
		assertThat(problemCodes(all.get(7), "errors")).isEqualTo("contact_required");
		commit(this.admin, this.schoolId, id(job), true, 202);
		JsonNode done = awaitJob(this.admin, this.schoolId, id(job), "COMPLETED", "FAILED");
		assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("COMPLETED");
		assertThat(done.get("result").get("created").asInt()).isEqualTo(3);
		assertThat(count("select count(*) from school_staff where school_id = ?", this.schoolId)).isEqualTo(3);
		UUID registrar = this.jdbc.queryForObject("select id from users where email = 'berhanu@example.com'", UUID.class);
		assertThat(this.jdbc.queryForObject("select staff_type from school_staff where user_id = ?", String.class, registrar))
			.isEqualTo("ADMINISTRATOR");
		assertThat(count("select count(*) from role_assignments where user_id = ?", registrar)).isZero();
		assertThat(count("select count(*) from role_assignments ra join users u on u.id = ra.user_id where u.phone_e164 in ('+251911000010', '+251911000011', '+251911000012')"))
			.isZero();
		assertThat(count("select count(*) from teacher_profiles tp join users u on u.id = tp.user_id where u.phone_e164 = '+251911000010'"))
			.isEqualTo(1);
		assertThat(count("select count(*) from school_staff where user_id = ? and school_id = ?", existing, this.schoolId))
			.as("the existing account is reused, not duplicated").isEqualTo(1);
		assertThat(count("select count(*) from users where phone_e164 = ?", existingPhone)).isEqualTo(1);
		assertThat(this.messages.awaitMessage("+251911000010")).contains("Safety school");
		assertThat(count("select count(*) from external_entity_mappings where school_id = ? and entity_type = 'STAFF'", this.schoolId))
			.isEqualTo(3);

		// the same file again: every staff member is recognised
		JsonNode again = validated(this.admin, this.schoolId, "STAFF", uploadCsv(this.admin, this.schoolId, csv), null);
		assertThat(again.get("rows").get("toCreate").asInt()).isZero();
		assertThat(again.get("rows").get("unchanged").asInt()).isEqualTo(3);
	}

	// --- the other import types, in the recommended order ---------------------------------

	@Test
	void gradesSubjectsStaffClassesStudentsAndEnrollmentsInOrder() throws Exception {
		JsonNode grades = validated(this.admin, this.schoolId, "GRADE_LEVELS", uploadCsv(this.admin, this.schoolId,
				"name,code,sequence\nGrade 7,7,7\nGrade 8,8,8\nGrade 8 again,8,\nGrade 9,9,8\n"), null);
		List<JsonNode> g = rows(this.admin, this.schoolId, id(grades), null);
		assertThat(g.get(2).get("status").asString()).as("the code 8 already belongs to row 3 of the file").isEqualTo("INVALID");
		assertThat(g.get(3).get("status").asString()).isEqualTo("INVALID");
		commit(this.admin, this.schoolId, id(grades), true, 202);
		awaitJob(this.admin, this.schoolId, id(grades), "COMPLETED");
		assertThat(this.jdbc.queryForList("select name from grade_levels where school_id = ? order by sequence", String.class, this.schoolId))
			.containsExactly("Grade 7", "Grade 8");

		JsonNode subjects = validated(this.admin, this.schoolId, "SUBJECTS", uploadCsv(this.admin, this.schoolId,
				"name,code,color\nMathematics,MATH,#1E88E5\nAmharic,AM,1e88e5\nBad color,BC,blue\n"), null);
		assertThat(subjects.get("rows").get("invalid").asInt()).isEqualTo(1);
		commit(this.admin, this.schoolId, id(subjects), true, 202);
		awaitJob(this.admin, this.schoolId, id(subjects), "COMPLETED");
		assertThat(this.jdbc.queryForObject("select color from subjects where school_id = ? and code = 'MATH'", String.class, this.schoolId))
			.isEqualTo("#1e88e5");
		JsonNode subjectsAgain = validated(this.admin, this.schoolId, "SUBJECTS", uploadCsv(this.admin, this.schoolId,
				"name,code,color\nMathematics,MATH,#1E88E5\nAmharic language,AM,\n"), null);
		assertThat(rows(this.admin, this.schoolId, id(subjectsAgain), null).stream().map((r) -> r.get("plannedAction").asString()).toList())
			.containsExactly("NONE", "UPDATE");

		JsonNode staff = validated(this.admin, this.schoolId, "STAFF", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,phone,staff_type\nTigist,Alemu,0911000020,TEACHER\n"), null);
		commit(this.admin, this.schoolId, id(staff), false, 202);
		awaitJob(this.admin, this.schoolId, id(staff), "COMPLETED");

		JsonNode classes = validated(this.admin, this.schoolId, "CLASSES", uploadCsv(this.admin, this.schoolId,
				"class_name,grade_level,class_type,lead_teacher_phone\n8A,Grade 8,HOMEROOM,0911000020\n7B,7,,0911999999\n9C,Grade 9,,\n"),
				null);
		List<JsonNode> c = rows(this.admin, this.schoolId, id(classes), null);
		assertThat(c.get(0).get("status").asString()).as(c.get(0).toString()).isEqualTo("VALID");
		assertThat(problemCodes(c.get(1), "warnings")).isEqualTo("lead_teacher_not_found");
		assertThat(problemCodes(c.get(2), "errors")).isEqualTo("grade_not_found");
		commit(this.admin, this.schoolId, id(classes), true, 202);
		assertThat(awaitJob(this.admin, this.schoolId, id(classes), "COMPLETED", "FAILED").get("status").asString()).isEqualTo("COMPLETED");
		UUID class8A = this.jdbc.queryForObject("select id from classes where school_id = ? and name = '8A'", UUID.class, this.schoolId);
		assertThat(count("select count(*) from classes where school_id = ?", this.schoolId)).isEqualTo(2);
		assertThat(count("select count(*) from class_teachers ct join users u on u.id = ct.teacher_id where ct.class_id = ? and ct.role = 'LEAD' and u.phone_e164 = '+251911000020'",
				class8A)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select gl.name from classes c join grade_levels gl on gl.id = c.grade_level_id where c.id = ?",
				String.class, class8A)).isEqualTo("Grade 8");
		JsonNode classesAgain = validated(this.admin, this.schoolId, "CLASSES", uploadCsv(this.admin, this.schoolId,
				"class_name,grade_level,lead_teacher_phone\n8A,Grade 8,0911000020\n"), null);
		assertThat(rows(this.admin, this.schoolId, id(classesAgain), null).getFirst().get("plannedAction").asString()).isEqualTo("NONE");

		// a student without a class first, then an enrollments file puts her into the class named in the file
		JsonNode students = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,date_of_birth,student_number\nAlmaz,Tesfaye,2012-05-14,S-1\nChaltu,Bekele,2011-03-02,S-2\n"), null);
		commit(this.admin, this.schoolId, id(students), false, 202);
		awaitJob(this.admin, this.schoolId, id(students), "COMPLETED");
		JsonNode enrollments = validated(this.admin, this.schoolId, "ENROLLMENTS", uploadCsv(this.admin, this.schoolId,
				"student_number,class_name\nS-1,8A\nS-2,8A\nS-9,8A\nS-1,nope\nS-1,8A\n"), null);
		List<JsonNode> e = rows(this.admin, this.schoolId, id(enrollments), null);
		assertThat(e.stream().map((r) -> r.get("status").asString()).toList())
			.containsExactly("VALID", "VALID", "INVALID", "INVALID", "INVALID");
		assertThat(problemCodes(e.get(2), "errors")).isEqualTo("student_not_found");
		assertThat(problemCodes(e.get(3), "errors")).isEqualTo("class_not_found");
		assertThat(problemCodes(e.get(4), "errors")).isEqualTo("duplicate_in_file");
		commit(this.admin, this.schoolId, id(enrollments), true, 202);
		awaitJob(this.admin, this.schoolId, id(enrollments), "COMPLETED");
		assertThat(count("select count(*) from class_students where class_id = ? and status = 'ACTIVE'", class8A)).isEqualTo(2);
		// and again: nothing to do
		JsonNode enrollmentsAgain = validated(this.admin, this.schoolId, "ENROLLMENTS", uploadCsv(this.admin, this.schoolId,
				"student_number,class_name\nS-1,8A\nS-2,8A\n"), null);
		assertThat(enrollmentsAgain.get("rows").get("unchanged").asInt()).isEqualTo(2);
	}

	// --- retention, cancel, delete ---------------------------------------------------------

	@Test
	void expiredRowsAndFilesArePurgedButCountsStay() throws Exception {
		UUID media = uploadCsv(this.admin, this.schoolId, "given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\nBad,,x\n");
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", media, null);
		UUID jobId = id(job);
		commit(this.admin, this.schoolId, jobId, true, 202);
		awaitJob(this.admin, this.schoolId, jobId, "COMPLETED");
		JsonNode young = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,date_of_birth\nZeru,Mekonnen,2012-05-14\n"), null);
		assertThat(this.maintenance.purgeExpired()).as("nothing is due yet").isZero();
		// 8 days after the commit
		this.jdbc.update("update import_jobs set completed_at = now() - interval '8 days' where id = ?", jobId);
		// 31 days after creation, never committed
		this.jdbc.update("update import_jobs set created_at = now() - interval '31 days' where id = ?", id(young));
		assertThat(this.maintenance.purgeExpired()).isGreaterThanOrEqualTo(2);
		for (UUID id : List.of(jobId, id(young))) {
			assertThat(this.jdbc.queryForMap("select rows_purged_at is not null as purged, file_purged_at is not null as file from import_jobs where id = ?", id))
				.containsEntry("purged", true)
				.containsEntry("file", true);
			assertThat(count("select count(*) from import_job_rows where import_job_id = ? and (raw_data is not null or normalized_data is not null or errors is not null)", id))
				.isZero();
		}
		assertThat(count("select count(*) from import_job_rows where import_job_id = ?", jobId)).as("the rows stay, emptied").isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, media)).isEqualTo("DELETED");
		JsonNode purged = job(this.admin, this.schoolId, jobId);
		assertThat(purged.get("rowsPurged").asBoolean()).isTrue();
		assertThat(purged.get("rows").get("valid").asInt()).isEqualTo(1);
		assertThat(purged.get("result").get("created").asInt()).isEqualTo(1);
		call(HttpMethod.GET, imports(this.schoolId) + "/" + jobId + "/error-report", this.admin, null, 409);
		assertThat(count("select count(*) from audit_logs where action = 'import.purge' and school_id = ?", this.schoolId)).isEqualTo(2);
		assertThat(this.maintenance.purgeExpired()).as("idempotent").isZero();
	}

	@Test
	void cancelPurgesRowsAndDeleteRemovesEverythingAtOnce() throws Exception {
		UUID media = uploadCsv(this.admin, this.schoolId, "given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\n");
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", media, null);
		UUID jobId = id(job);
		String base = imports(this.schoolId) + "/" + jobId;
		assertThat(call(HttpMethod.POST, base + "/cancel", this.admin, null, 200).get("status").asString()).isEqualTo("CANCELLED");
		assertThat(count("select count(*) from import_job_rows where import_job_id = ? and normalized_data is not null", jobId)).isZero();
		call(HttpMethod.POST, base + "/cancel", this.admin, null, 409);
		call(HttpMethod.POST, base + "/commit", this.admin, Map.of(), 409);
		call(HttpMethod.DELETE, base, this.admin, null, 204);
		call(HttpMethod.GET, base, this.admin, null, 404);
		assertThat(count("select count(*) from import_job_rows where import_job_id = ?", jobId)).isZero();
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, media)).isEqualTo("DELETED");
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isZero();
	}

	// --- mapping profiles ------------------------------------------------------------------

	@Test
	void savedMappingsAreAppliedToTheNextFileWithTheSameHeaders() throws Exception {
		String csv = "Pupil,Dad,Born\nAbebe,Kebede,2012-05-14\n";
		JsonNode first = createImport(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv), null);
		assertThat(first.get("missingFields").toString()).contains("given_name");
		assertThat(first.get("appliedProfile")).isNull();
		UUID jobId = UUID.fromString(first.get("job").get("id").asString());
		JsonNode saved = call(HttpMethod.PUT, imports(this.schoolId) + "/" + jobId + "/mapping", this.admin,
				Map.of("columnMapping", Map.of("given_name", 0, "father_name", 1, "date_of_birth", 2), "saveAsProfile", "School export"),
				200);
		assertThat(saved.get("job").get("status").asString()).isEqualTo("PREVIEWED");
		JsonNode profiles = call(HttpMethod.GET, imports(this.schoolId) + "/mapping-profiles?importType=STUDENTS", this.admin, null, 200);
		assertThat(profiles).hasSize(1);
		assertThat(profiles.get(0).get("columnMapping").get("given_name").asString()).isEqualTo("Pupil");
		call(HttpMethod.POST, imports(this.schoolId) + "/mapping-profiles", this.admin,
				Map.of("name", "school export", "importType", "STUDENTS", "columnMapping", Map.of("given_name", "Pupil", "father_name", "Dad", "date_of_birth", "Born")),
				409);
		JsonNode second = createImport(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv), null);
		assertThat(second.get("appliedProfile").get("name").asString()).isEqualTo("School export");
		assertThat(second.get("missingFields")).isEmpty();
		assertThat(second.get("suggestedMapping").get("given_name").asInt()).isZero();
		// a file with other headers does not get it
		JsonNode other = createImport(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"Pupil,Born\nAbebe,2012-05-14\n"), null);
		assertThat(other.get("appliedProfile")).isNull();
		UUID profileId = UUID.fromString(profiles.get(0).get("id").asString());
		call(HttpMethod.PUT, imports(this.schoolId) + "/mapping-profiles/" + profileId, this.admin,
				Map.of("name", "Renamed", "columnMapping", Map.of("given_name", "Pupil", "father_name", "Dad", "date_of_birth", "Born")), 200);
		call(HttpMethod.POST, imports(this.schoolId) + "/mapping-profiles", this.admin,
				Map.of("name", "Bad", "importType", "STUDENTS", "columnMapping", Map.of("given_name", "Pupil")), 400);
		call(HttpMethod.DELETE, imports(this.schoolId) + "/mapping-profiles/" + profileId, this.admin, null, 204);
		assertThat(call(HttpMethod.GET, imports(this.schoolId) + "/mapping-profiles", this.admin, null, 200)).isEmpty();
		// profiles belong to a school
		UUID otherSchool = this.personas.newSchool("Profile other");
		call(HttpMethod.DELETE, imports(otherSchool) + "/mapping-profiles/" + profileId, this.personas.schoolAdmin(otherSchool, "pa"), null, 404);
	}

	// --- templates -------------------------------------------------------------------------

	@Test
	void templatesHaveStableKeysAnExampleRowAndInstructionsInBothLanguages() throws Exception {
		this.personas.newClass(this.schoolId, "8A");
		for (String type : List.of("STUDENTS", "STAFF", "CLASSES", "SUBJECTS", "GRADE_LEVELS", "ENROLLMENTS")) {
			byte[] csv = this.mvc.perform(get(imports(this.schoolId) + "/templates/" + type + "?format=csv").with(this.admin.bearer()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();
			assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
			String text = new String(csv, StandardCharsets.UTF_8).substring(1);
			assertThat(text).contains("\r\n");
			String[] lines = text.split("\r\n");
			assertThat(lines[0]).startsWith(switch (type) {
				case "STUDENTS", "STAFF" -> "given_name,father_name,grandfather_name";
				case "CLASSES" -> "class_name,grade_level";
				case "ENROLLMENTS" -> "student_number,external_id,class_name";
				default -> "name,code";
			});
			assertThat(lines[1]).as("the example row starts with # (quoted by the CSV writer)").matches("^\"?#.*");
		}
		byte[] xlsx = this.mvc.perform(get(imports(this.schoolId) + "/templates/STUDENTS?format=xlsx").with(this.admin.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
			assertThat(wb.getSheetName(0)).isEqualTo("Data");
			assertThat(wb.getSheetName(1)).isEqualTo("Instructions");
			Sheet data = wb.getSheet("Data");
			assertThat(data.getRow(0).getCell(0).getStringCellValue()).isEqualTo("given_name");
			assertThat(data.getRow(1).getCell(0).getStringCellValue()).startsWith("#");
			assertThat(data.getDataValidations()).as("lists for gender and the school's classes").hasSizeGreaterThanOrEqualTo(2);
			assertThat(wb.isSheetHidden(wb.getSheetIndex("Lists"))).isTrue();
			StringBuilder all = new StringBuilder();
			wb.getSheet("Instructions").forEach((r) -> r.forEach((c) -> all.append(c.getStringCellValue()).append('\n')));
			assertThat(all.toString()).contains("Fill in the Data sheet").contains("ያስፈልጋል").contains("given_name");
		}
		// the untouched template is a valid, empty import: the example row is skipped
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", upload(this.admin, this.schoolId, "template.xlsx", XLSX, xlsx), null);
		assertThat(job.get("status").asString()).isEqualTo("VALIDATED");
		assertThat(job.get("rows").get("total").asInt()).isZero();
	}

	// --- redelivery, crashed workers, capacity ---------------------------------------------

	@Test
	void redeliveredEventsAndCrashedWorkersAreHarmless() throws Exception {
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\nAlmaz,Tesfaye,2013-01-02\n"), null);
		UUID jobId = id(job);
		// a second delivery of the dry-run event finds a finished job
		this.tx.executeWithoutResult((s) -> this.publisher.publishEvent(new ImportDryRunRequested(jobId)));
		this.tx.executeWithoutResult((s) -> this.publisher.publishEvent(new ImportCommitRequested(jobId)));
		Thread.sleep(1500);
		assertThat(job(this.admin, this.schoolId, jobId).get("status").asString()).isEqualTo("VALIDATED");
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isZero();
		commit(this.admin, this.schoolId, jobId, false, 202);
		awaitJob(this.admin, this.schoolId, jobId, "COMPLETED");
		this.tx.executeWithoutResult((s) -> this.publisher.publishEvent(new ImportCommitRequested(jobId)));
		Thread.sleep(1500);
		assertThat(count("select count(*) from school_students where school_id = ?", this.schoolId)).isEqualTo(2);
		assertThat(job(this.admin, this.schoolId, jobId).get("result").get("created").asInt()).isEqualTo(2);

		// a worker that died: the job says VALIDATING, nobody holds its lease, the watchdog queues it again
		JsonNode second = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId,
				"given_name,father_name,date_of_birth\nZeru,Mekonnen,2012-05-14\n"), null);
		this.jdbc.update("update import_jobs set status = 'VALIDATING', validated_hash = null, lease_owner = 'dead', lease_until = now() - interval '5 minutes', updated_at = now() - interval '10 minutes' where id = ?",
				id(second));
		assertThat(this.maintenance.resubmitStuck()).isGreaterThanOrEqualTo(1);
		JsonNode recovered = awaitJob(this.admin, this.schoolId, id(second), "VALIDATED", "FAILED");
		assertThat(recovered.get("status").asString()).as(recovered.toString()).isEqualTo("VALIDATED");
		assertThat(recovered.get("rows").get("total").asInt()).isEqualTo(1);
	}

	@Test
	void aSchoolCannotRunMoreThanTwoChecksOrCommitsAtOnce() throws Exception {
		UUID media = uploadCsv(this.admin, this.schoolId, "given_name,father_name,date_of_birth\nAbebe,Kebede,2012-05-14\n");
		JsonNode preview = createImport(this.admin, this.schoolId, "STUDENTS", media, null);
		UUID jobId = UUID.fromString(preview.get("job").get("id").asString());
		saveMapping(this.admin, this.schoolId, jobId, mappingOf(preview), null);
		UUID a = UUID.fromString(createImport(this.admin, this.schoolId, "STUDENTS", media, null).get("job").get("id").asString());
		UUID b = UUID.fromString(createImport(this.admin, this.schoolId, "STUDENTS", media, null).get("job").get("id").asString());
		this.jdbc.update("update import_jobs set status = 'VALIDATING', lease_until = now() + interval '1 hour' where id in (?, ?)", a, b);
		JsonNode busy = call(HttpMethod.POST, imports(this.schoolId) + "/" + jobId + "/dry-run", this.admin, null, 429);
		assertThat(busy.get("code").asString()).isEqualTo("import.too_many_running");
		this.jdbc.update("update import_jobs set status = 'CANCELLED', lease_until = null where id in (?, ?)", a, b);
		call(HttpMethod.POST, imports(this.schoolId) + "/" + jobId + "/dry-run", this.admin, null, 202);
		awaitJob(this.admin, this.schoolId, jobId, "VALIDATED");
	}

	@Test
	void thePlansStudentLimitIsCheckedInTheDryRun() throws Exception {
		StringBuilder csv = new StringBuilder("given_name,father_name,date_of_birth\n");
		for (int i = 1; i <= 103; i++) {
			csv.append("S").append(i).append(",F,2012-05-14\n");
		}
		JsonNode job = validated(this.admin, this.schoolId, "STUDENTS", uploadCsv(this.admin, this.schoolId, csv.toString()), null);
		assertThat(job.get("rows").get("valid").asInt()).as("the free plan allows 100 students").isEqualTo(100);
		assertThat(job.get("rows").get("invalid").asInt()).isEqualTo(3);
		assertThat(problemCodes(rows(this.admin, this.schoolId, id(job), "INVALID").getFirst(), "errors")).isEqualTo("student_limit_reached");
	}

	@Test
	void theImportTypesDescribeTheirColumnsInTheReadersLanguage() throws Exception {
		JsonNode en = call(HttpMethod.GET, imports(this.schoolId) + "/types", this.admin, null, 200);
		assertThat(en).hasSize(6);
		JsonNode students = en.get(4);
		assertThat(students.get("type").asString()).isEqualTo("STUDENTS");
		assertThat(students.get("fields").get(0).get("label").asString()).isEqualTo("First name");
		JsonNode am = call(HttpMethod.GET, imports(this.schoolId) + "/types", this.admin, null, 200, "am");
		assertThat(am.get(4).get("fields").get(0).get("label").asString()).isEqualTo("የመጀመሪያ ስም");
		assertThat(MediaFixtures.text("x")).isNotEmpty();
		await().atMost(java.time.Duration.ofSeconds(1)).until(() -> true);
	}

}
