package com.nemaz.school.attendance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-period rolls (M7d): slot and subject validated against the school's settings and the class's subjects, rosters of
 * an elective's group, one roll per period that never touches another, the dashboard and the per-period report with
 * its CSV.
 */
@IntegrationTest
class PeriodAttendanceApiTest extends AttendanceTestSupport {

	/** The class 1A teaches Maths (and a second subject) and the school uses DAY, P1 and P2. */
	private Setup periodSetup(String label) {
		Setup s = setup(label);
		setting(s.schoolId(), "attendance.slots", List.of("DAY", "P1", "P2"));
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		this.jdbc.update("insert into class_subjects (class_id, subject_id, school_id) values (?, ?, ?)", s.classId(), maths(s),
				s.schoolId());
		return s;
	}

	/** The class's Maths subject (the fixtures' subjectId is the subject teacher's user id). */
	private UUID maths(Setup s) {
		return this.jdbc.queryForObject("select id from subjects where school_id = ? and code = 'M'", UUID.class, s.schoolId());
	}

	private UUID addSubject(Setup s, String name, boolean inClass) {
		UUID id = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, ?, ?)", id, s.schoolId(), name,
				name.substring(0, 2).toUpperCase());
		if (inClass) {
			this.jdbc.update("insert into class_subjects (class_id, subject_id, school_id) values (?, ?, ?)", s.classId(), id,
					s.schoolId());
		}
		return id;
	}

	@SafeVarargs
	private static Map<String, Object> period(String slot, UUID subjectId, boolean all, Map<String, Object>... marks) {
		Map<String, Object> body = rollBody(java.time.LocalDate.now(ADDIS), all, marks);
		body.put("slot", slot);
		if (subjectId != null) {
			body.put("subjectId", subjectId);
		}
		return body;
	}

	private JsonNode periodRoll(Setup s, Persona as, String slot) throws Exception {
		return get(s.rollUrl() + "?date=" + today() + "&slot=" + slot, as);
	}

	@Test
	void theSlotAndTheSubjectAreCheckedAgainstTheSchoolAndTheClass() throws Exception {
		Setup s = periodSetup("Period validation school");
		UUID foreign = addSubject(s, "Physics", false);
		// a slot the school did not enable
		assertThat(errorCode(take(s, s.lead(), withDate(period("P3", null, true)), 400))).isEqualTo("attendance.slot_not_enabled");
		// a subject that is not one of the class's (it exists in the school), one that does not exist at all
		assertThat(errorCode(take(s, s.lead(), withDate(period("P1", foreign, true)), 400))).isEqualTo("attendance.invalid_subject");
		assertThat(errorCode(take(s, s.lead(), withDate(period("P1", UUID.randomUUID(), true)), 400)))
			.isEqualTo("attendance.invalid_subject");
		// another school's subject
		Setup other = periodSetup("Period other school");
		assertThat(errorCode(take(s, s.lead(), withDate(period("P1", maths(other), true)), 400)))
			.isEqualTo("attendance.invalid_subject");
		// a deactivated subject
		UUID retired = addSubject(s, "Latin", true);
		this.jdbc.update("update subjects set is_active = false where id = ?", retired);
		assertThat(errorCode(take(s, s.lead(), withDate(period("P1", retired, true)), 400))).isEqualTo("attendance.invalid_subject");
		// a subject belongs to a period, not to the whole day
		assertThat(errorCode(take(s, s.lead(), withDate(period("DAY", maths(s), true)), 400)))
			.isEqualTo("attendance.subject_requires_period");
		assertThat(countRecords(s.classId())).isZero();

		JsonNode ok = take(s, s.lead(), withDate(period("P1", maths(s), true)), 200);
		assertThat(ok.get("slot").asString()).isEqualTo("P1");
		JsonNode view = periodRoll(s, s.lead(), "P1");
		assertThat(view.get("subjectId").asString()).isEqualTo(maths(s).toString());
		assertThat(view.get("subjectName").asString()).isEqualTo("Maths");
		assertThat(view.get("slots")).hasSize(3);
		// a period roll without a subject is allowed too, and has none
		take(s, s.lead(), withDate(period("P2", null, true)), 200);
		assertThat(absent(periodRoll(s, s.lead(), "P2"), "subjectId")).isTrue();
		call(HttpMethod.GET, s.rollUrl() + "?date=" + today() + "&slot=P3", s.lead(), null, 400);
	}

	/** The period helpers use the school's today; AttendanceTestSupport's own date is the same zone. */
	private Map<String, Object> withDate(Map<String, Object> body) {
		body.put("date", today().toString());
		return body;
	}

	@Test
	void aPeriodRollBelongsToItsSubjectAndOneWriteNeverMovesIt() throws Exception {
		Setup s = periodSetup("Period subject school");
		UUID second = addSubject(s, "Biology", true);
		take(s, s.lead(), withDate(period("P1", maths(s), false, mark(s.s1(), "PRESENT"))), 200);
		// the same period for another subject would overwrite the roll's meaning: refused, nothing written
		assertThat(errorCode(take(s, s.lead(), withDate(period("P1", second, false, mark(s.s2(), "ABSENT"))), 409)))
			.isEqualTo("attendance.subject_mismatch");
		assertThat(countRecords(s.classId())).isEqualTo(1);
		// a later write that names no subject (a client that does not know it) keeps the roll's own
		take(s, s.lead(), withDate(period("P1", null, false, mark(s.s2(), "LATE"))), 200);
		assertThat(periodRoll(s, s.lead(), "P1").get("subjectName").asString()).isEqualTo("Maths");
		// the second subject takes the other period
		take(s, s.lead(), withDate(period("P2", second, true)), 200);
		assertThat(periodRoll(s, s.lead(), "P2").get("subjectName").asString()).isEqualTo("Biology");
	}

	@Test
	void theRollOfOnePeriodNeverOverwritesAnother() throws Exception {
		Setup s = periodSetup("Period isolation school");
		take(s, s.lead(), withDate(period("P1", maths(s), false, mark(s.s1(), "ABSENT", null, "p1 note", null))), 200);
		take(s, s.lead(), withDate(period("P2", null, false, mark(s.s1(), "PRESENT"))), 200);
		take(s, s.lead(), withDate(period("DAY", null, false, mark(s.s1(), "LATE", 5, null, null))), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from attendance_sessions where class_id = ?", Integer.class, s.classId()))
			.isEqualTo(3);
		assertThat(countRecords(s.classId())).isEqualTo(3);

		// correcting P2 leaves P1 and DAY exactly as they were (status, note, version)
		JsonNode fix = take(s, s.lead(), withDate(period("P2", null, false, mark(s.s1(), "ABSENT", null, null, 0))), 200);
		assertThat(outcome(fix, s.s1()).get("outcome").asString()).isEqualTo("APPLIED");
		JsonNode p1 = student(periodRoll(s, s.lead(), "P1"), s.s1()).get("record");
		assertThat(p1.get("status").asString()).isEqualTo("ABSENT");
		assertThat(p1.get("note").asString()).isEqualTo("p1 note");
		assertThat(p1.get("version").asInt()).isZero();
		JsonNode day = student(periodRoll(s, s.lead(), "DAY"), s.s1()).get("record");
		assertThat(day.get("status").asString()).isEqualTo("LATE");
		assertThat(day.get("version").asInt()).isZero();
		assertThat(student(periodRoll(s, s.lead(), "P2"), s.s1()).get("record").get("version").asInt()).isEqualTo(1);

		// a stale expectedVersion on one period is a conflict of that period only
		JsonNode stale = take(s, s.lead(), withDate(period("P1", null, false, mark(s.s1(), "PRESENT", null, null, 7))), 409);
		assertThat(outcome(stale, s.s1()).get("outcome").asString()).isEqualTo("CONFLICT");
		assertThat(student(periodRoll(s, s.lead(), "P2"), s.s1()).get("record").get("status").asString()).isEqualTo("ABSENT");
		// the sessions of one day lock together with it, but each keeps its own version
		assertThat(periodRoll(s, s.lead(), "P1").get("sessionVersion").asInt()).isEqualTo(1);
		assertThat(periodRoll(s, s.lead(), "P2").get("sessionVersion").asInt()).isEqualTo(2);
	}

	@Test
	void theRosterOfAnElectiveIsTheMembersOfItsGroup() throws Exception {
		Setup s = periodSetup("Period group school");
		UUID group = Ids.newId();
		this.jdbc.update("insert into student_groups (id, class_id, school_id, name, created_by) values (?, ?, ?, 'Choir', ?)", group,
				s.classId(), s.schoolId(), s.leadId());
		this.jdbc.update("insert into student_group_members (group_id, student_id) values (?, ?), (?, ?)", group, s.s1(), group, s.s2());
		UUID otherGroup = Ids.newId();
		this.jdbc.update("insert into student_groups (id, class_id, school_id, name, created_by) values (?, ?, ?, 'Else', ?)", otherGroup,
				s.otherClassId(), s.schoolId(), s.otherTeacher().userId());

		String url = s.rollUrl() + "?date=" + today() + "&slot=P1&groupId=" + group;
		JsonNode view = get(url, s.lead());
		assertThat(view.get("students")).hasSize(2);
		assertThat(view.get("groupId").asString()).isEqualTo(group.toString());
		assertThat(get(s.rollUrl() + "?date=" + today() + "&slot=P1", s.lead()).get("students")).hasSize(3);
		// a group of another class, or one that does not exist
		call(HttpMethod.GET, s.rollUrl() + "?date=" + today() + "&slot=P1&groupId=" + otherGroup, s.lead(), null, 400);
		call(HttpMethod.GET, s.rollUrl() + "?date=" + today() + "&slot=P1&groupId=" + UUID.randomUUID(), s.lead(), null, 400);

		// marking: a student outside the group is refused, markAllPresent covers the group only
		Map<String, Object> outside = withDate(period("P1", null, false, mark(s.s3(), "PRESENT")));
		outside.put("groupId", group);
		assertThat(errorCode(take(s, s.lead(), outside, 409))).isEqualTo("attendance.student_not_in_group");
		Map<String, Object> all = withDate(period("P1", maths(s), true));
		all.put("groupId", group);
		JsonNode result = take(s, s.lead(), all, 200);
		assertThat(result.get("applied").asInt()).isEqualTo(2);
		assertThat(countRecords(s.classId())).isEqualTo(2);
		// the whole class's view shows the group's marks and an unmarked rest; the group's view hides nobody it should show
		JsonNode whole = periodRoll(s, s.lead(), "P1");
		assertThat(whole.get("counts").get("marked").asInt()).isEqualTo(2);
		assertThat(whole.get("counts").get("unmarked").asInt()).isEqualTo(1);
		// a second group of the same period (the other half of the class) writes into the same roll without touching the first
		UUID rest = Ids.newId();
		this.jdbc.update("insert into student_groups (id, class_id, school_id, name, created_by) values (?, ?, ?, 'Art', ?)", rest,
				s.classId(), s.schoolId(), s.leadId());
		this.jdbc.update("insert into student_group_members (group_id, student_id) values (?, ?)", rest, s.s3());
		Map<String, Object> artists = withDate(period("P1", maths(s), false, mark(s.s3(), "ABSENT")));
		artists.put("groupId", rest);
		take(s, s.lead(), artists, 200);
		JsonNode choirView = get(url, s.lead());
		assertThat(choirView.get("students")).hasSize(2);
		assertThat(choirView.get("counts").get("absent").asInt()).isZero();
		assertThat(countRecords(s.classId())).isEqualTo(3);
	}

	@Test
	void theDashboardShowsPeriodsAndSubjects() throws Exception {
		Setup s = periodSetup("Period dashboard school");
		take(s, s.lead(), withDate(period("P1", maths(s), true, mark(s.s1(), "ABSENT"))), 200);
		String base = s.api() + "/attendance/dashboard?date=" + today();
		JsonNode p1 = get(base + "&slot=P1", s.admin());
		assertThat(p1.get("slot").asString()).isEqualTo("P1");
		JsonNode row = p1.get("classes").get("items").get(0);
		assertThat(row.get("taken").asBoolean()).isTrue();
		assertThat(row.get("subjectName").asString()).isEqualTo("Maths");
		assertThat(row.get("absent").asInt()).isEqualTo(1);
		// P2 was not taken; DAY neither
		assertThat(get(base + "&slot=P2", s.admin()).get("totals").get("classesTaken").asInt()).isZero();
		// the subject filter keeps the classes that have the subject, and only the rolls taken for it
		JsonNode maths = get(base + "&slot=P1&subjectId=" + maths(s), s.admin());
		assertThat(maths.get("subjectId").asString()).isEqualTo(maths(s).toString());
		assertThat(maths.get("classes").get("items")).hasSize(1);
		assertThat(maths.get("totals").get("classesTaken").asInt()).isEqualTo(1);
		UUID biology = addSubject(s, "Biology", true);
		JsonNode bio = get(base + "&slot=P1&subjectId=" + biology, s.admin());
		assertThat(bio.get("classes").get("items").get(0).get("taken").asBoolean()).isFalse();
		assertThat(bio.get("totals").get("classesNotTaken").asInt()).isEqualTo(1);
		// a subject of another school is not a subject here
		call(HttpMethod.GET, base + "&slot=P1&subjectId=" + UUID.randomUUID(), s.admin(), null, 400);
		// a teacher sees theirs
		assertThat(get(base + "&slot=P1", s.lead()).get("classes").get("items")).hasSize(1);
		call(HttpMethod.GET, base, s.student1(), null, 403);
	}

	@Test
	void thePeriodReportCountsPerClassSlotAndSubject() throws Exception {
		Setup s = periodSetup("Period report school");
		UUID biology = addSubject(s, "Biology", true);
		take(s, s.lead(), withDate(period("P1", maths(s), true, mark(s.s1(), "ABSENT"))), 200);
		take(s, s.lead(), withDate(period("P2", biology, true, mark(s.s2(), "LATE", 12, null, null))), 200);
		take(s, s.lead(), withDate(period("DAY", null, true)), 200);
		String url = s.api() + "/attendance/period-report";

		JsonNode all = get(url, s.admin());
		assertThat(all.get("items")).hasSize(3);
		JsonNode p1 = null;
		for (JsonNode item : all.get("items")) {
			if ("P1".equals(item.get("slot").asString())) {
				p1 = item;
			}
		}
		assertThat(p1).isNotNull();
		assertThat(p1.get("className").asString()).isEqualTo("1A");
		assertThat(p1.get("subjectName").asString()).isEqualTo("Maths");
		assertThat(p1.get("rolls").asInt()).isEqualTo(1);
		assertThat(p1.get("students").asInt()).isEqualTo(3);
		assertThat(p1.get("marked").asInt()).isEqualTo(3);
		assertThat(p1.get("absent").asInt()).isEqualTo(1);
		assertThat(p1.get("present").asInt()).isEqualTo(2);
		assertThat(get(url + "?slot=P2", s.admin()).get("items").get(0).get("minutesLate").asInt()).isEqualTo(12);
		assertThat(get(url + "?subjectId=" + maths(s), s.admin()).get("items")).hasSize(1);
		assertThat(absent(get(url + "?slot=DAY", s.admin()).get("items").get(0), "subjectId")).isTrue();
		assertThat(get(url + "?from=" + today().plusDays(1) + "&to=" + today().plusDays(2), s.admin()).get("items")).isEmpty();
		call(HttpMethod.GET, url + "?slot=P9", s.admin(), null, 400);
		call(HttpMethod.GET, url + "?from=" + today() + "&to=" + today().minusDays(1), s.admin(), null, 400);

		// keyset paging walks every group once
		JsonNode first = get(url + "?limit=2", s.admin());
		assertThat(first.get("items")).hasSize(2);
		JsonNode second = get(url + "?limit=2&cursor=" + first.get("nextCursor").asString(), s.admin());
		assertThat(second.get("items")).hasSize(1);
		assertThat(second.has("nextCursor") && !second.get("nextCursor").isNull()).isFalse();
		call(HttpMethod.GET, url + "?cursor=garbage", s.admin(), null, 400);

		// who: a teacher of the class asks for the class, not for the school; the rest is refused
		assertThat(get(url + "?classId=" + s.classId(), s.lead()).get("items")).hasSize(3);
		call(HttpMethod.GET, url, s.lead(), null, 403);
		call(HttpMethod.GET, url + "?classId=" + s.classId(), s.otherTeacher(), null, 404);
		call(HttpMethod.GET, url, s.student1(), null, 403);
		call(HttpMethod.GET, url, s.guardian1(), null, 404);
		call(HttpMethod.GET, url, this.personas.schoolAdmin(this.personas.newSchool("Another school"), "other-period-admin"), null, 404);
		call(HttpMethod.GET, url, null, null, 401);
	}

	@Test
	void thePeriodCsvIsSafeLocalizedAuditedAndRateLimited() throws Exception {
		Setup s = periodSetup("Period csv school");
		this.jdbc.update("update subjects set name = '=HYPERLINK(\"http://evil\")' where id = ?", maths(s));
		take(s, s.lead(), withDate(period("P1", maths(s), true, mark(s.s1(), "ABSENT"))), 200);
		String url = s.api() + "/attendance/period-report.csv";

		MockHttpServletResponse english = this.mvc.perform(MockMvcRequestBuilders.get(url).with(s.admin().bearer())).andReturn().getResponse();
		assertThat(english.getStatus()).isEqualTo(200);
		assertThat(english.getContentType()).startsWith("text/csv");
		assertThat(english.getHeader("Content-Disposition")).contains("attendance-period-report.csv");
		assertThat(english.getHeader("Cache-Control")).contains("no-store");
		byte[] bytes = english.getContentAsByteArray();
		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).startsWith("﻿Class,Slot,Subject,Rolls,Students,Marks,Present,Absent,Late,Excused,Left early,Minutes late\r\n");
		assertThat(text).contains("\r\n1A,P1,\"'=HYPERLINK(\"\"http://evil\"\")\",1,3,3,2,1,0,0,0,0\r\n");
		assertThat(text).doesNotContain(",=HYPERLINK");
		MockHttpServletResponse amharic = this.mvc
			.perform(MockMvcRequestBuilders.get(url).with(s.admin().bearer()).header("Accept-Language", "am"))
			.andReturn()
			.getResponse();
		assertThat(new String(amharic.getContentAsByteArray(), StandardCharsets.UTF_8)).contains("ክፍል").contains("የትምህርት ዓይነት");
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'attendance.period_report.export' and school_id = ?", Integer.class,
				s.schoolId())).isEqualTo(2);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url + "?classId=" + s.classId()).with(s.lead().bearer())).andReturn()
			.getResponse().getStatus()).isEqualTo(200);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url).with(s.lead().bearer())).andReturn().getResponse().getStatus())
			.isEqualTo(403);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(url + "?slot=P9").with(s.admin().bearer())).andReturn().getResponse()
			.getStatus()).isEqualTo(400);

		Persona exporter = this.personas.schoolAdmin(s.schoolId(), "period-exporter");
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
		// a refused export (403, 400 or 429) writes no audit row: only the 12 - 2 + the first 3 successes
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'attendance.period_report.export' and school_id = ?", Integer.class,
				s.schoolId())).isEqualTo(2 + 1 + 10);
	}

}
