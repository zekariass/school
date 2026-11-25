package com.nemaz.school.attendance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** A student's history, the daily dashboard and the summary reports with their CSV export. */
@IntegrationTest
class AttendanceViewsApiTest extends AttendanceTestSupport {

	private static final String NOTE = "teacher-note-private";

	/** s1 is absent yesterday (with a note) and present today in class 1A; the other class's teacher marks s1 late today there. */
	private Setup withHistory(String label) throws Exception {
		Setup s = setup(label);
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		take(s, s.lead(), rollBody(today().minusDays(1), true, mark(s.s1(), "ABSENT", null, NOTE, null)), 200);
		take(s, s.lead(), rollBody(today(), true), 200);
		return s;
	}

	private String history(Setup s, UUID student) {
		return s.api() + "/students/" + student + "/attendance";
	}

	@Test
	void theChildAndTheirGuardiansSeeTheHistoryWithoutTheTeachersNote() throws Exception {
		Setup s = withHistory("History school");
		for (Persona reader : List.of(s.student1(), s.guardian1(), s.guardian2())) {
			JsonNode page = get(history(s, s.s1()), reader);
			assertThat(page.get("items")).hasSize(2);
			assertThat(page.get("items").get(0).get("date").asString()).isEqualTo(today().toString());
			assertThat(page.get("items").get(1).get("status").asString()).isEqualTo("ABSENT");
			assertThat(page.get("items").get(1).has("note") && !page.get("items").get(1).get("note").isNull()).as(reader.name())
				.isFalse();
			assertThat(page.get("items").get(1).get("className").asString()).isEqualTo("1A");
		}
		for (Persona reader : List.of(s.lead(), s.subject(), s.assistant(), s.admin())) {
			JsonNode items = get(history(s, s.s1()), reader).get("items");
			assertThat(items.get(1).get("note").asString()).as(reader.name()).isEqualTo(NOTE);
		}
	}

	@Test
	void everyoneElseIsRefusedAndNeverToldWhoExists() throws Exception {
		Setup s = withHistory("History deny school");
		// the classmate may know the student exists, nobody else outside the circle may
		call(HttpMethod.GET, history(s, s.s1()), this.personas.personaFor("bethlehem", s.s2()), null, 403);
		call(HttpMethod.GET, history(s, s.s1()), s.otherTeacher(), null, 404);
		call(HttpMethod.GET, history(s, s.s1()), s.guardianOfS2(), null, 404);
		call(HttpMethod.GET, history(s, s.s1()), this.personas.newUser("nobody"), null, 404);
		Persona pending = this.personas.newUser("pending");
		this.personas.linkGuardian(s.s1(), pending.userId(), "PENDING", false);
		call(HttpMethod.GET, history(s, s.s1()), pending, null, 404);
		Persona restricted = this.personas.newUser("restricted");
		this.personas.linkGuardian(s.s1(), restricted.userId(), "ACTIVE", true);
		call(HttpMethod.GET, history(s, s.s1()), restricted, null, 404);
		// an active link without can_view_academics sees the child exists but not the attendance
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", s.guardian2().userId());
		call(HttpMethod.GET, history(s, s.s1()), s.guardian2(), null, 403);
		// another school's administrator
		UUID other = this.personas.newSchool("Other school");
		call(HttpMethod.GET, history(s, s.s1()), this.personas.schoolAdmin(other, "other-admin"), null, 404);
		call(HttpMethod.GET, history(s, s.s1()), null, null, 401);
		// an unknown student
		call(HttpMethod.GET, history(s, UUID.randomUUID()), s.admin(), null, 404);
	}

	@Test
	void teachersSeeOnlyTheClassesTheyTeachAndAdministratorsAll() throws Exception {
		Setup s = withHistory("History scope school");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), s.s1());
		backdateMemberships(s.otherClassId());
		take(s.otherClassId(), s.otherTeacher(), s, "LATE");
		assertThat(get(history(s, s.s1()), s.admin()).get("items")).hasSize(3);
		JsonNode mine = get(history(s, s.s1()), s.lead()).get("items");
		assertThat(mine).hasSize(2);
		assertThat(mine).allSatisfy((item) -> assertThat(item.get("className").asString()).isEqualTo("1A"));
		JsonNode theirs = get(history(s, s.s1()), s.otherTeacher()).get("items");
		assertThat(theirs).hasSize(1);
		assertThat(theirs.get(0).get("className").asString()).isEqualTo("2B");
		// the child and the guardian see every class
		assertThat(get(history(s, s.s1()), s.guardian1()).get("items")).hasSize(3);
	}

	private void take(UUID classId, Persona teacher, Setup s, String status) throws Exception {
		call(HttpMethod.PUT, s.api() + "/classes/" + classId + "/attendance", teacher,
				rollBody(today(), false, mark(s.s1(), status)), 200);
	}

	@Test
	void theHistoryIsKeysetPagedNewestFirstAndFilterable() throws Exception {
		Setup s = setup("History pages school");
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		for (int days = 0; days < 5; days++) {
			take(s, s.lead(), rollBody(today().minusDays(days), false, mark(s.s1(), (days == 2) ? "ABSENT" : "PRESENT")), 200);
		}
		JsonNode first = get(history(s, s.s1()) + "?limit=2", s.guardian1());
		assertThat(first.get("items")).hasSize(2);
		String cursor = first.get("nextCursor").asString();
		JsonNode second = get(history(s, s.s1()) + "?limit=2&cursor=" + cursor, s.guardian1());
		assertThat(second.get("items").get(0).get("date").asString()).isEqualTo(today().minusDays(2).toString());
		assertThat(second.get("items").get(0).get("status").asString()).isEqualTo("ABSENT");
		JsonNode third = get(history(s, s.s1()) + "?limit=2&cursor=" + second.get("nextCursor").asString(), s.guardian1());
		assertThat(third.get("items")).hasSize(1);
		assertThat(third.has("nextCursor") && !third.get("nextCursor").isNull()).isFalse();
		JsonNode ranged = get(history(s, s.s1()) + "?from=" + today().minusDays(3) + "&to=" + today().minusDays(1), s.guardian1());
		assertThat(ranged.get("items")).hasSize(3);
		call(HttpMethod.GET, history(s, s.s1()) + "?from=" + today() + "&to=" + today().minusDays(1), s.guardian1(), null, 400);
		call(HttpMethod.GET, history(s, s.s1()) + "?cursor=garbage", s.guardian1(), null, 400);
	}

	@Test
	void theDashboardShowsWhichClassesHaveTakenAttendance() throws Exception {
		Setup s = setup("Dashboard school");
		UUID otherStudent = this.personas.newStudent(s.schoolId(), "Dawit");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), otherStudent);
		backdateMemberships(s.otherClassId());
		this.personas.newClass(s.schoolId(), "3C"); // no students: not counted
		take(s, s.lead(), rollBody(today(), true, mark(s.s1(), "ABSENT"), mark(s.s2(), "LATE", 4, null, null)), 200);

		String url = s.api() + "/attendance/dashboard";
		JsonNode admin = get(url, s.admin());
		assertThat(admin.get("date").asString()).isEqualTo(today().toString());
		JsonNode totals = admin.get("totals");
		assertThat(totals.get("classes").asInt()).isEqualTo(2);
		assertThat(totals.get("classesTaken").asInt()).isEqualTo(1);
		assertThat(totals.get("classesNotTaken").asInt()).isEqualTo(1);
		assertThat(totals.get("students").asInt()).isEqualTo(4);
		assertThat(totals.get("marked").asInt()).isEqualTo(3);
		assertThat(totals.get("unmarked").asInt()).isEqualTo(1);
		assertThat(totals.get("present").asInt()).isEqualTo(1);
		assertThat(totals.get("absent").asInt()).isEqualTo(1);
		assertThat(totals.get("late").asInt()).isEqualTo(1);
		JsonNode classes = admin.get("classes").get("items");
		assertThat(classes).hasSize(3);
		JsonNode first = classes.get(0);
		assertThat(first.get("className").asString()).isEqualTo("1A");
		assertThat(first.get("taken").asBoolean()).isTrue();
		assertThat(first.get("students").asInt()).isEqualTo(3);
		assertThat(first.get("unmarked").asInt()).isZero();
		assertThat(classes.get(1).get("className").asString()).isEqualTo("2B");
		assertThat(classes.get(1).get("taken").asBoolean()).isFalse();
		assertThat(classes.get(1).get("unmarked").asInt()).isEqualTo(1);

		// teachers see the classes they teach, with totals over those only
		JsonNode lead = get(url, s.lead());
		assertThat(lead.get("classes").get("items")).hasSize(1);
		assertThat(lead.get("totals").get("classes").asInt()).isEqualTo(1);
		JsonNode other = get(url, s.otherTeacher());
		assertThat(other.get("classes").get("items")).hasSize(1);
		assertThat(other.get("classes").get("items").get(0).get("taken").asBoolean()).isFalse();

		// keyset paging by class name
		JsonNode page = get(url + "?limit=1", s.admin());
		assertThat(page.get("classes").get("items")).hasSize(1);
		JsonNode next = get(url + "?limit=1&cursor=" + page.get("classes").get("nextCursor").asString(), s.admin());
		assertThat(next.get("classes").get("items").get(0).get("className").asString()).isEqualTo("2B");
		// another day, another slot
		assertThat(get(url + "?date=" + today().minusDays(1), s.admin()).get("totals").get("classesTaken").asInt()).isZero();
		call(HttpMethod.GET, url + "?slot=PM", s.admin(), null, 400);

		// students, guardians, strangers and other schools
		call(HttpMethod.GET, url, s.student1(), null, 403);
		call(HttpMethod.GET, url, s.guardian1(), null, 404);
		call(HttpMethod.GET, url, this.personas.newUser("nobody"), null, 404);
		call(HttpMethod.GET, url, this.personas.schoolAdmin(this.personas.newSchool("Elsewhere"), "elsewhere-admin"), null, 404);
		call(HttpMethod.GET, url, null, null, 401);
	}

	@Test
	void theSummaryReportCountsMarksPerStudentAndClass() throws Exception {
		Setup s = withHistory("Report school");
		take(s, s.lead(), rollBody(today(), false, mark(s.s2(), "LATE", 7, null, 0)), 200);
		String url = s.api() + "/attendance/report";
		JsonNode report = get(url + "?from=" + today().minusDays(5) + "&to=" + today(), s.admin());
		JsonNode items = report.get("items");
		assertThat(items).hasSize(3);
		assertThat(items.get(0).get("studentName").asString()).isEqualTo("Abel");
		assertThat(items.get(0).get("marked").asInt()).isEqualTo(2);
		assertThat(items.get(0).get("present").asInt()).isEqualTo(1);
		assertThat(items.get(0).get("absent").asInt()).isEqualTo(1);
		JsonNode bethlehem = items.get(1);
		assertThat(bethlehem.get("late").asInt()).isEqualTo(1);
		assertThat(bethlehem.get("minutesLate").asInt()).isEqualTo(7);
		// a range that excludes yesterday
		JsonNode todayOnly = get(url + "?from=" + today() + "&to=" + today(), s.admin());
		assertThat(todayOnly.get("items").get(0).get("marked").asInt()).isEqualTo(1);
		// a class, a student, paging
		assertThat(get(url + "?classId=" + s.classId(), s.lead()).get("items")).hasSize(3);
		assertThat(get(url + "?studentId=" + s.s1(), s.admin()).get("items")).hasSize(1);
		JsonNode page = get(url + "?limit=2", s.admin());
		assertThat(page.get("items")).hasSize(2);
		assertThat(get(url + "?limit=2&cursor=" + page.get("nextCursor").asString(), s.admin()).get("items")).hasSize(1);
		call(HttpMethod.GET, url + "?from=" + today() + "&to=" + today().minusDays(1), s.admin(), null, 400);
		call(HttpMethod.GET, url + "?from=" + today().minusDays(500) + "&to=" + today(), s.admin(), null, 400);
		// who may
		call(HttpMethod.GET, url, s.lead(), null, 403);
		call(HttpMethod.GET, url + "?classId=" + s.classId(), s.assistant(), null, 200);
		call(HttpMethod.GET, url + "?classId=" + s.classId(), s.student1(), null, 403);
		call(HttpMethod.GET, url + "?classId=" + s.classId(), s.guardian1(), null, 403);
		call(HttpMethod.GET, url + "?classId=" + s.classId(), s.otherTeacher(), null, 404);
		call(HttpMethod.GET, url, s.student1(), null, 403);
		call(HttpMethod.GET, url, this.personas.newUser("nobody"), null, 404);
	}

	@Test
	void theCsvIsSafeLocalizedAuditedAndRateLimited() throws Exception {
		Setup s = withHistory("Csv school");
		this.jdbc.update("update users set display_name = '=HYPERLINK(\"http://evil\")' where id = ?", s.s2());
		this.jdbc.update("update users set display_name = '+cmd|calc' where id = ?", s.s3());
		String url = s.api() + "/attendance/report.csv?from=" + today().minusDays(3) + "&to=" + today();

		MockHttpServletResponse english = this.mvc.perform(MockMvcRequestBuilders.get(url).with(s.admin().bearer()))
			.andReturn()
			.getResponse();
		assertThat(english.getStatus()).isEqualTo(200);
		assertThat(english.getContentType()).startsWith("text/csv");
		assertThat(english.getHeader("Content-Disposition")).contains("attendance-report.csv");
		assertThat(english.getHeader("Cache-Control")).contains("no-store");
		byte[] bytes = english.getContentAsByteArray();
		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).startsWith("﻿Student,Class,Marks,Present,Absent,Late,Excused,Left early,Minutes late\r\n");
		assertThat(text).contains("\r\nAbel,1A,2,1,1,0,0,0,0\r\n");
		assertThat(text).contains("'=HYPERLINK").contains("'+cmd|calc");
		assertThat(text).doesNotContain("\n=HYPERLINK").doesNotContain(",=HYPERLINK").doesNotContain(NOTE);
		assertThat(text.lines().count()).isEqualTo(4);

		MockHttpServletResponse amharic = this.mvc
			.perform(MockMvcRequestBuilders.get(url).with(s.admin().bearer()).header("Accept-Language", "am"))
			.andReturn()
			.getResponse();
		assertThat(new String(amharic.getContentAsByteArray(), StandardCharsets.UTF_8)).contains("ተማሪ").contains("ክፍል");

		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'attendance.report.export' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(2);
		// a teacher exports their own class only
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url + "&classId=" + s.classId()).with(s.lead().bearer()))
			.andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url).with(s.lead().bearer())).andReturn().getResponse().getStatus())
			.isEqualTo(403);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url).with(s.guardian1().bearer())).andReturn().getResponse().getStatus())
			.isEqualTo(404);

		// at most 10 exports per 10 minutes and user (two were made above)
		Persona exporter = this.personas.schoolAdmin(s.schoolId(), "exporter");
		int limited = 0;
		for (int i = 0; i < 12; i++) {
			int status = this.mvc.perform(MockMvcRequestBuilders.get(url).with(exporter.bearer())).andReturn().getResponse().getStatus();
			if (status == 429) {
				limited++;
			}
			else {
				assertThat(status).isEqualTo(200);
			}
		}
		assertThat(limited).isEqualTo(2);
	}

}
