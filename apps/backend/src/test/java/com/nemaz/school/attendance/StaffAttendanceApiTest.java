package com.nemaz.school.attendance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Staff attendance (M7d): the setting, marking a day, corrections, who may read what (a teacher never reads a colleague),
 * school-local dates, the summaries and their CSV, and that staff records never leak into class, points or student reports.
 */
@IntegrationTest
class StaffAttendanceApiTest extends AttendanceTestSupport {

	private static final String NOTE = "staff-note-private";

	@Autowired
	private CacheManager cacheManager;

	/** The attendance school with staff attendance on, the staff started long ago, and one more staff member (support). */
	private record StaffSetup(Setup s, UUID supportUser, Persona support) {

		String base() {
			return this.s.api() + "/staff-attendance";
		}

	}

	private StaffSetup staffSetup(String label) {
		Setup s = setup(label);
		setting(s.schoolId(), "attendance.staff.enabled", true);
		UUID support = this.personas.newStaff(s.schoolId(), label + "-support", "SUPPORT_STAFF");
		this.jdbc.update("update school_staff set started_on = null where school_id = ?", s.schoolId());
		return new StaffSetup(s, support, this.personas.personaFor(label + "-support", support));
	}

	private UUID staffId(Setup s, UUID userId) {
		return this.jdbc.queryForObject("select id from school_staff where school_id = ? and user_id = ?", UUID.class, s.schoolId(), userId);
	}

	private static Map<String, Object> staffMark(UUID staffId, String status, Integer minutesLate, String note) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("staffId", staffId);
		m.put("status", status);
		if (minutesLate != null) {
			m.put("minutesLate", minutesLate);
		}
		if (note != null) {
			m.put("note", note);
		}
		return m;
	}

	@SafeVarargs
	private static Map<String, Object> day(boolean rest, Map<String, Object>... marks) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("records", new ArrayList<>(List.of(marks)));
		if (rest) {
			body.put("markRestPresent", true);
		}
		return body;
	}

	private JsonNode mark(StaffSetup t, Persona as, LocalDate date, Map<String, Object> body, int status) throws Exception {
		return call(HttpMethod.PUT, t.base() + "/days/" + date, as, body, status);
	}

	private int staffRows(UUID schoolId) {
		return this.jdbc.queryForObject("select count(*) from staff_attendance_records where school_id = ?", Integer.class, schoolId);
	}

	@Test
	void staffAttendanceIsOffUntilTheSchoolSwitchesItOn() throws Exception {
		Setup s = setup("Staff off school");
		String base = s.api() + "/staff-attendance";
		JsonNode refused = call(HttpMethod.GET, base + "/days/" + today(), s.admin(), null, 403);
		assertThat(errorCode(refused)).isEqualTo("staff_attendance.disabled");
		assertThat(errorCode(call(HttpMethod.GET, base + "/me", s.lead(), null, 403))).isEqualTo("staff_attendance.disabled");
		assertThat(errorCode(call(HttpMethod.GET, base + "/reports/monthly", s.admin(), null, 403))).isEqualTo("staff_attendance.disabled");
		// the permission is checked first: a teacher is told nothing about the setting
		assertThat(errorCode(call(HttpMethod.GET, base + "/days/" + today(), s.lead(), null, 403))).isEqualTo("staff_attendance.access_denied");
		assertThat(staffRows(s.schoolId())).isZero();
		setting(s.schoolId(), "attendance.staff.enabled", true);
		call(HttpMethod.GET, base + "/days/" + today(), s.admin(), null, 200);
		// the feature flag of attendance switches it off as well
		this.jdbc.update("""
				insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('attendance', 'SCHOOL', ?, false)""",
				s.schoolId());
		this.cacheManager.getCache("school-configuration").clear();
		assertThat(errorCode(call(HttpMethod.GET, base + "/days/" + today(), s.admin(), null, 403))).isEqualTo("attendance.feature_disabled");
	}

	@Test
	void aManagerMarksTheDayAndARepeatChangesNothing() throws Exception {
		StaffSetup t = staffSetup("Staff mark school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		UUID other = staffId(s, s.otherTeacher().userId());
		JsonNode result = mark(t, s.admin(), today(), day(true, staffMark(lead, "LATE", 12, "bus broke down"),
				staffMark(other, "ABSENT", null, null)), 200);
		assertThat(result.get("created").asInt()).isEqualTo(5);
		assertThat(result.get("existing").asInt()).isZero();
		assertThat(result.get("date").asString()).isEqualTo(today().toString());
		assertThat(this.jdbc.queryForObject("select count(*) from staff_attendance_records where school_id = ? and method = 'MANUAL' and recorded_by = ?",
				Integer.class, s.schoolId(), s.admin().userId())).isEqualTo(5);
		JsonNode leadRecord = null;
		for (JsonNode outcome : result.get("outcomes")) {
			if (outcome.get("staffId").asString().equals(lead.toString())) {
				leadRecord = outcome.get("record");
			}
		}
		assertThat(leadRecord.get("status").asString()).isEqualTo("LATE");
		assertThat(leadRecord.get("minutesLate").asInt()).isEqualTo(12);
		assertThat(leadRecord.get("note").asString()).isEqualTo("bus broke down");
		assertThat(leadRecord.get("version").asInt()).isZero();

		// the same request again: everything EXISTS, nothing changes, even a different mark
		JsonNode again = mark(t, s.admin(), today(), day(true, staffMark(lead, "ABSENT", null, null)), 200);
		assertThat(again.get("created").asInt()).isZero();
		assertThat(again.get("existing").asInt()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select status from staff_attendance_records where staff_id = ? and attendance_date = ?",
				String.class, lead, java.sql.Date.valueOf(today()))).isEqualTo("LATE");

		// the day's roll lists every ACTIVE staff member with the record, in name order, keyset paged
		JsonNode roll = get(t.base() + "/days/" + today(), s.admin());
		assertThat(roll.get("timeZone").asString()).isEqualTo("Africa/Addis_Ababa");
		JsonNode entries = roll.get("staff").get("items");
		assertThat(entries).hasSize(5);
		assertThat(entries).allSatisfy((e) -> assertThat(e.get("record").isNull()).isFalse());
		JsonNode first = get(t.base() + "/days/" + today() + "?limit=2", s.admin());
		assertThat(first.get("staff").get("items")).hasSize(2);
		JsonNode second = get(t.base() + "/days/" + today() + "?limit=2&cursor=" + first.get("staff").get("nextCursor").asString(), s.admin());
		assertThat(second.get("staff").get("items").get(0).get("staffId").asString())
			.isNotEqualTo(first.get("staff").get("items").get(1).get("staffId").asString());
		// another day: the same people, no records yet
		JsonNode tomorrowless = get(t.base() + "/days/" + today().minusDays(1), s.admin());
		assertThat(tomorrowless.get("staff").get("items")).hasSize(5);
		assertThat(tomorrowless.get("staff").get("items")).allSatisfy((e) -> assertThat(absent(e, "record")).isTrue());
		call(HttpMethod.GET, t.base() + "/days/" + today() + "?cursor=garbage", s.admin(), null, 400);
		// a campus filter that matches nobody
		assertThat(get(t.base() + "/days/" + today() + "?campusId=" + UUID.randomUUID(), s.admin()).get("staff").get("items")).isEmpty();
	}

	@Test
	void everythingIsCheckedBeforeAnythingIsWritten() throws Exception {
		StaffSetup t = staffSetup("Staff validate school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		UUID other = staffId(s, s.otherTeacher().userId());
		assertThat(errorCode(mark(t, s.admin(), today(), day(false), 400))).isEqualTo("staff_attendance.nothing_to_record");
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(lead, "PRESENT", null, null),
				staffMark(lead, "ABSENT", null, null)), 400))).isEqualTo("staff_attendance.duplicate_staff");
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(lead, "PRESENT", 5, null)), 400)))
			.isEqualTo("staff_attendance.invalid_minutes_late");
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(lead, "LATE", 601, null)), 400)))
			.isEqualTo("staff_attendance.invalid_minutes_late");
		mark(t, s.admin(), today(), day(false, staffMark(lead, "PRESENT", null, "x".repeat(201))), 400);
		// unknown staff, another school's staff, a staff member who is no longer active
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(lead, "PRESENT", null, null),
				staffMark(UUID.randomUUID(), "PRESENT", null, null)), 404))).isEqualTo("staff_attendance.staff_not_found");
		Setup elsewhere = setup("Staff elsewhere school");
		UUID foreign = staffId(elsewhere, elsewhere.leadId());
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(foreign, "PRESENT", null, null)), 404)))
			.isEqualTo("staff_attendance.staff_not_found");
		this.jdbc.update("update school_staff set status = 'FORMER' where id = ?", other);
		assertThat(errorCode(mark(t, s.admin(), today(), day(false, staffMark(lead, "PRESENT", null, null),
				staffMark(other, "ABSENT", null, null)), 409))).isEqualTo("staff_attendance.staff_not_active");
		// dates: the future, and too long ago
		assertThat(errorCode(mark(t, s.admin(), today().plusDays(1), day(false, staffMark(lead, "PRESENT", null, null)), 409)))
			.isEqualTo("staff_attendance.date_in_future");
		assertThat(errorCode(mark(t, s.admin(), today().minusDays(63), day(false, staffMark(lead, "PRESENT", null, null)), 400)))
			.isEqualTo("staff_attendance.date_too_old");
		call(HttpMethod.PUT, t.base() + "/days/not-a-date", s.admin(), day(false, staffMark(lead, "PRESENT", null, null)), 400);
		assertThat(staffRows(s.schoolId())).isZero();
		// the oldest allowed day works, and markRestPresent skips a former staff member
		mark(t, s.admin(), today().minusDays(62), day(true), 200);
		assertThat(staffRows(s.schoolId())).isEqualTo(4);
	}

	@Test
	void aCorrectionNeedsAReasonAndIsAppendedNeverEdited() throws Exception {
		StaffSetup t = staffSetup("Staff correct school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		JsonNode marked = mark(t, s.admin(), today(), day(false, staffMark(lead, "LATE", 12, NOTE)), 200);
		UUID recordId = uuid(marked.get("outcomes").get(0).get("record"), "id");
		String url = t.base() + "/records/" + recordId + "/corrections";
		Map<String, Object> fix = new LinkedHashMap<>();
		fix.put("status", "PRESENT");
		fix.put("reason", "The bus was held up by the road works");
		fix.put("expectedVersion", 0);

		// refused: no reason, a blank reason, minutes without LATE, a stale version, nothing to change
		Map<String, Object> noReason = new LinkedHashMap<>(fix);
		noReason.remove("reason");
		call(HttpMethod.POST, url, s.admin(), noReason, 400);
		Map<String, Object> blank = new LinkedHashMap<>(fix);
		blank.put("reason", "   ");
		call(HttpMethod.POST, url, s.admin(), blank, 400);
		Map<String, Object> longReason = new LinkedHashMap<>(fix);
		longReason.put("reason", "r".repeat(201));
		call(HttpMethod.POST, url, s.admin(), longReason, 400);
		Map<String, Object> minutes = new LinkedHashMap<>(fix);
		minutes.put("minutesLate", 5);
		assertThat(errorCode(call(HttpMethod.POST, url, s.admin(), minutes, 400))).isEqualTo("staff_attendance.invalid_minutes_late");
		Map<String, Object> stale = new LinkedHashMap<>(fix);
		stale.put("expectedVersion", 3);
		assertThat(errorCode(call(HttpMethod.POST, url, s.admin(), stale, 409))).isEqualTo("staff_attendance.version_conflict");
		Map<String, Object> same = new LinkedHashMap<>(fix);
		same.put("status", "LATE");
		same.put("minutesLate", 12);
		assertThat(errorCode(call(HttpMethod.POST, url, s.admin(), same, 400))).isEqualTo("staff_attendance.nothing_to_correct");
		assertThat(this.jdbc.queryForObject("select count(*) from staff_attendance_corrections", Integer.class)).isZero();

		JsonNode done = call(HttpMethod.POST, url, s.admin(), fix, 201);
		assertThat(done.get("record").get("status").asString()).isEqualTo("PRESENT");
		assertThat(done.get("record").get("version").asInt()).isEqualTo(1);
		assertThat(done.get("record").get("corrections").asInt()).isEqualTo(1);
		assertThat(done.get("record").get("note").asString()).isEqualTo(NOTE);
		assertThat(done.get("record").has("minutesLate") && !done.get("record").get("minutesLate").isNull()).isFalse();
		JsonNode correction = done.get("corrections").get(0);
		assertThat(correction.get("oldStatus").asString()).isEqualTo("LATE");
		assertThat(correction.get("newStatus").asString()).isEqualTo("PRESENT");
		assertThat(correction.get("oldMinutesLate").asInt()).isEqualTo(12);
		assertThat(correction.get("reason").asString()).isEqualTo("The bus was held up by the road works");
		assertThat(uuid(correction, "correctedBy")).isEqualTo(s.admin().userId());

		// a second correction (with the new version) clears the note; the first one is still there, unchanged
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("status", "HALF_DAY");
		second.put("note", "");
		second.put("reason", "Left at noon");
		second.put("expectedVersion", 1);
		JsonNode twice = call(HttpMethod.POST, url, s.admin(), second, 201);
		assertThat(twice.get("corrections")).hasSize(2);
		assertThat(twice.get("corrections").get(0).get("newStatus").asString()).isEqualTo("PRESENT");
		assertThat(absent(twice.get("record"), "note")).isTrue();
		assertThat(get(t.base() + "/records/" + recordId, s.admin()).get("corrections")).hasSize(2);

		// append-only in the database too
		assertThatThrownBy(() -> this.jdbc.update("update staff_attendance_corrections set reason = 'x'")).hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("delete from staff_attendance_corrections")).hasMessageContaining("append-only");

		// audited with codes only: never the reason or the note
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'staff_attendance.record.correct' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_logs where school_id = ? and (cast(metadata as text) like ? or cast(changes as text) like ?
				       or cast(metadata as text) like ? or cast(metadata as text) like ?)""", Integer.class, s.schoolId(), "%road works%",
				"%road works%", "%" + NOTE + "%", "%Left at noon%")).isZero();
		// a record that does not exist, or belongs to another school
		assertThat(errorCode(call(HttpMethod.POST, t.base() + "/records/" + UUID.randomUUID() + "/corrections", s.admin(), fix, 404)))
			.isEqualTo("staff_attendance.record_not_found");
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("Staff correct other"), "staff-correct-other-admin");
		call(HttpMethod.POST, url, otherAdmin, fix, 404);
		call(HttpMethod.POST, "/api/v1/schools/" + this.personas.newSchool("Staff third school") + "/staff-attendance/records/" + recordId + "/corrections",
				this.personas.platformAdmin(), fix, 403);
	}

	@Test
	void aTeacherReadsOnlyTheirOwnRecordsAndNeverAColleagues() throws Exception {
		StaffSetup t = staffSetup("Staff privacy school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		UUID subject = staffId(s, s.subjectId());
		JsonNode marked = mark(t, s.admin(), today(), day(false, staffMark(lead, "LATE", 7, NOTE), staffMark(subject, "PRESENT", null, null)), 200);
		UUID leadRecord = null;
		UUID subjectRecord = null;
		for (JsonNode outcome : marked.get("outcomes")) {
			UUID id = uuid(outcome.get("record"), "id");
			if (outcome.get("staffId").asString().equals(lead.toString())) {
				leadRecord = id;
			}
			else {
				subjectRecord = id;
			}
		}

		// each reads their own, and only theirs, with the note
		JsonNode mine = get(t.base() + "/me", s.lead());
		assertThat(mine.get("items")).hasSize(1);
		assertThat(mine.get("items").get(0).get("note").asString()).isEqualTo(NOTE);
		assertThat(get(t.base() + "/me", s.subject()).get("items")).hasSize(1);
		assertThat(get(t.base() + "/me", s.assistant()).get("items")).isEmpty();
		assertThat(get(t.base() + "/me", t.support()).get("items")).isEmpty();
		assertThat(get(t.base() + "/records/" + leadRecord, s.lead()).get("record").get("status").asString()).isEqualTo("LATE");

		// ...but a colleague's record does not exist for them: not by id, not in a list, not by asking for the person
		assertThat(errorCode(call(HttpMethod.GET, t.base() + "/records/" + leadRecord, s.subject(), null, 404)))
			.isEqualTo("staff_attendance.record_not_found");
		assertThat(errorCode(call(HttpMethod.GET, t.base() + "/records/" + subjectRecord, s.lead(), null, 404)))
			.isEqualTo("staff_attendance.record_not_found");
		call(HttpMethod.GET, t.base() + "/records/" + leadRecord, s.assistant(), null, 404);
		call(HttpMethod.GET, t.base() + "/records/" + leadRecord, t.support(), null, 404);
		call(HttpMethod.GET, t.base() + "/records/" + UUID.randomUUID(), s.subject(), null, 404);
		for (Persona teacher : List.of(s.lead(), s.subject(), s.assistant(), s.otherTeacher(), t.support())) {
			assertThat(errorCode(call(HttpMethod.GET, t.base() + "/records", teacher, null, 403))).as(teacher.name())
				.isEqualTo("staff_attendance.access_denied");
			call(HttpMethod.GET, t.base() + "/records?staffId=" + lead, teacher, null, 403);
			call(HttpMethod.GET, t.base() + "/days/" + today(), teacher, null, 403);
			call(HttpMethod.GET, t.base() + "/reports/daily", teacher, null, 403);
			call(HttpMethod.GET, t.base() + "/reports/monthly", teacher, null, 403);
			call(HttpMethod.GET, t.base() + "/reports/daily.csv", teacher, null, 403);
			call(HttpMethod.GET, t.base() + "/reports/monthly.csv", teacher, null, 403);
			call(HttpMethod.PUT, t.base() + "/days/" + today(), teacher, day(true), 403);
		}
		// not even a correction of their own record
		Map<String, Object> fix = Map.of("status", "PRESENT", "reason", "I was on time", "expectedVersion", 0);
		call(HttpMethod.POST, t.base() + "/records/" + leadRecord + "/corrections", s.lead(), fix, 403);
		assertThat(this.jdbc.queryForObject("select status from staff_attendance_records where id = ?", String.class, leadRecord)).isEqualTo("LATE");

		// students and guardians: a student sees the school (403), a guardian does not even find it (404)
		call(HttpMethod.GET, t.base() + "/me", s.student1(), null, 403);
		call(HttpMethod.GET, t.base() + "/records", s.student1(), null, 403);
		call(HttpMethod.GET, t.base() + "/records/" + leadRecord, s.student1(), null, 404);
		for (String path : List.of("/me", "/records", "/days/" + today(), "/reports/daily", "/records/" + leadRecord)) {
			call(HttpMethod.GET, t.base() + path, s.guardian1(), null, 404);
		}
		call(HttpMethod.PUT, t.base() + "/days/" + today(), s.guardian1(), day(true), 404);
		// an administrator who is not staff has no records of their own
		call(HttpMethod.GET, t.base() + "/me", s.admin(), null, 403);

		// another school's administrator finds nothing, also not through their own school's URL
		Setup elsewhere = setup("Staff privacy elsewhere");
		call(HttpMethod.GET, t.base() + "/records", elsewhere.admin(), null, 404);
		call(HttpMethod.GET, t.base() + "/days/" + today(), elsewhere.admin(), null, 404);
		setting(elsewhere.schoolId(), "attendance.staff.enabled", true);
		call(HttpMethod.GET, elsewhere.api() + "/staff-attendance/records/" + leadRecord, elsewhere.admin(), null, 404);
		call(HttpMethod.GET, elsewhere.api() + "/staff-attendance/records/" + leadRecord, elsewhere.lead(), null, 404);

		// a reader who may not mark reads everything; the manager and the reader differ only in writing
		Persona reader = this.personas.withPlatformPermissions("school.read", "school.staff_attendance.read");
		assertThat(get(t.base() + "/records", reader).get("items")).hasSize(2);
		assertThat(get(t.base() + "/records?staffId=" + lead, reader).get("items")).hasSize(1);
		assertThat(get(t.base() + "/records/" + leadRecord, reader).get("record").get("note").asString()).isEqualTo(NOTE);
		call(HttpMethod.GET, t.base() + "/days/" + today(), reader, null, 200);
		call(HttpMethod.GET, t.base() + "/reports/monthly", reader, null, 200);
		call(HttpMethod.PUT, t.base() + "/days/" + today(), reader, day(true), 403);
		call(HttpMethod.POST, t.base() + "/records/" + leadRecord + "/corrections", reader, fix, 403);
		// attendance readers and managers of the roll have no say over staff
		Persona rollManager = this.personas.withPlatformPermissions("school.read", "school.attendance.manage", "school.attendance.read");
		call(HttpMethod.GET, t.base() + "/records", rollManager, null, 403);
		call(HttpMethod.GET, t.base() + "/reports/monthly.csv", rollManager, null, 403);
		// the campus administrator sees the school but runs no staff attendance
		// (covered by the authorization matrix)
	}

	@Test
	void datesAreSchoolLocalAtTheBoundaries() throws Exception {
		StaffSetup t = staffSetup("Staff dates school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		LocalDate the25th = LocalDate.of(2026, 11, 25);
		LocalDate the26th = LocalDate.of(2026, 11, 26);
		// 20:30 UTC is 23:30 on the 25th in Addis Ababa: the 26th has not started
		moveClockTo("2026-11-25T20:30:00Z");
		assertThat(errorCode(mark(t, s.admin(), the26th, day(false, staffMark(lead, "PRESENT", null, null)), 409)))
			.isEqualTo("staff_attendance.date_in_future");
		mark(t, s.admin(), the25th, day(false, staffMark(lead, "PRESENT", null, null)), 200);
		// 21:30 UTC is 00:30 on the 26th
		moveClockTo("2026-11-25T21:30:00Z");
		JsonNode result = mark(t, s.admin(), the26th, day(false, staffMark(lead, "ABSENT", null, null)), 200);
		assertThat(result.get("outcomes").get(0).get("record").get("date").asString()).isEqualTo("2026-11-26");
		// the default range of a list ends on the school's today (the 26th), which UTC has not reached yet
		JsonNode mine = get(t.base() + "/me", s.lead());
		assertThat(mine.get("items")).hasSize(2);
		assertThat(mine.get("items").get(0).get("date").asString()).isEqualTo("2026-11-26");
		moveClockTo("2026-11-25T20:30:00Z");
		assertThat(get(t.base() + "/me", s.lead()).get("items")).hasSize(1);
		assertThat(get(t.base() + "/records?to=" + the26th, s.admin()).get("items")).hasSize(2);

		// a campus with its own zone: 05:00 UTC on the 26th is 21:00 on the 25th in Los Angeles
		UUID campus = Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, timezone) values (?, ?, 'West', 'America/Los_Angeles')", campus,
				s.schoolId());
		UUID west = staffId(s, t.supportUser());
		this.jdbc.update("update school_staff set campus_id = ? where id = ?", campus, west);
		moveClockTo("2026-11-26T05:00:00Z");
		assertThat(errorCode(mark(t, s.admin(), the26th, day(false, staffMark(west, "PRESENT", null, null)), 409)))
			.isEqualTo("staff_attendance.date_in_future");
		JsonNode ok = mark(t, s.admin(), the25th, day(false, staffMark(west, "PRESENT", null, null)), 200);
		assertThat(ok.get("outcomes").get(0).get("record").get("campusId").asString()).isEqualTo(campus.toString());
		// the whole-school request is refused as a whole when one staff member's day has not started
		assertThat(errorCode(mark(t, s.admin(), the26th, day(true, staffMark(lead, "PRESENT", null, null)), 409)))
			.isEqualTo("staff_attendance.date_in_future");
		assertThat(this.jdbc.queryForObject("select count(*) from staff_attendance_records where attendance_date = ?", Integer.class,
				java.sql.Date.valueOf(the26th))).isEqualTo(1); // only the lead's earlier ABSENT
		// the roll can be filtered by campus
		assertThat(get(t.base() + "/days/" + the25th + "?campusId=" + campus, s.admin()).get("staff").get("items")).hasSize(1);
		moveClockTo("2026-11-26T09:00:00Z");
		mark(t, s.admin(), the26th, day(false, staffMark(west, "LATE", 3, null)), 200);
	}

	@Test
	void staffRecordsNeverAppearInClassPointsOrStudentReports() throws Exception {
		StaffSetup t = staffSetup("Staff isolation school");
		Setup s = t.s();
		UUID lead = staffId(s, s.leadId());
		// the same person marks students on the roll and is marked as staff
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT", null, "student note", null)), 200);
		mark(t, s.admin(), today(), day(true, staffMark(lead, "LATE", 20, NOTE)), 200);
		assertThat(staffRows(s.schoolId())).isEqualTo(5);
		assertThat(countRecords(s.classId())).isEqualTo(1);

		String leadName = this.jdbc.queryForObject("select display_name from users where id = ?", String.class, s.leadId());
		String supportName = this.jdbc.queryForObject("select display_name from users where id = ?", String.class, t.supportUser());
		List<String> reads = new ArrayList<>();
		for (String path : List.of("/attendance/report", "/attendance/period-report", "/attendance/dashboard?date=" + today(),
				"/classes/" + s.classId() + "/attendance?date=" + today(), "/students/" + s.s1() + "/attendance",
				"/attendance/report.csv", "/attendance/period-report.csv", "/points/report", "/points/report.csv")) {
			String body = this.mvc.perform(MockMvcRequestBuilders.get(s.api() + path).with(s.admin().bearer()))
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
			reads.add(path + " " + body.length());
			assertThat(body).as(path).doesNotContain(NOTE).doesNotContain(leadName).doesNotContain(supportName);
		}
		for (Persona reader : List.of(s.student1(), s.guardian1(), s.lead())) {
			String body = this.mvc.perform(MockMvcRequestBuilders.get(s.api() + "/students/" + s.s1() + "/attendance").with(reader.bearer()))
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
			assertThat(body).doesNotContain(NOTE).doesNotContain("LATE");
		}
		JsonNode report = get(s.api() + "/attendance/report", s.admin());
		assertThat(report.get("items")).hasSize(1);
		assertThat(report.get("items").get(0).get("late").asInt()).isZero();
		JsonNode dashboard = get(s.api() + "/attendance/dashboard?date=" + today(), s.admin());
		assertThat(dashboard.get("totals").get("late").asInt()).isZero();
		assertThat(dashboard.get("totals").get("marked").asInt()).isEqualTo(1);
		// the staff member is not a student of any roll
		assertThat(get(s.api() + "/classes/" + s.classId() + "/attendance?date=" + today(), s.lead()).get("students")).hasSize(3);
		assertThat(reads).isNotEmpty();
	}

	@Test
	void theSummariesCountPerDayAndPerStaffMemberAndExportAsCsv() throws Exception {
		StaffSetup t = staffSetup("Staff report school");
		Setup s = t.s();
		moveClockTo("2026-11-20T09:00:00Z");
		UUID lead = staffId(s, s.leadId());
		UUID subject = staffId(s, s.subjectId());
		UUID other = staffId(s, s.otherTeacher().userId());
		this.jdbc.update("update users set display_name = '=HYPERLINK(\"http://evil\")' where id = ?", s.leadId());
		this.jdbc.update("update school_staff set job_title = '+cmd|calc' where id = ?", lead);
		mark(t, s.admin(), LocalDate.of(2026, 11, 2), day(false, staffMark(lead, "PRESENT", null, null), staffMark(subject, "PRESENT", null, null)), 200);
		mark(t, s.admin(), LocalDate.of(2026, 11, 3), day(false, staffMark(lead, "LATE", 10, null), staffMark(subject, "HALF_DAY", null, null)), 200);
		mark(t, s.admin(), LocalDate.of(2026, 11, 4), day(false, staffMark(lead, "ABSENT", null, NOTE), staffMark(subject, "EXCUSED", null, null),
				staffMark(other, "LATE", 5, null)), 200);
		mark(t, s.admin(), LocalDate.of(2026, 10, 30), day(false, staffMark(lead, "PRESENT", null, null)), 200);
		this.jdbc.update("update school_staff set status = 'FORMER' where id = ?", other);

		JsonNode daily = get(t.base() + "/reports/daily?from=2026-11-01&to=2026-11-20", s.admin());
		assertThat(daily.get("rows")).hasSize(3);
		JsonNode nov3 = daily.get("rows").get(1);
		assertThat(nov3.get("date").asString()).isEqualTo("2026-11-03");
		assertThat(nov3.get("marked").asInt()).isEqualTo(2);
		assertThat(nov3.get("late").asInt()).isEqualTo(1);
		assertThat(nov3.get("halfDay").asInt()).isEqualTo(1);
		assertThat(nov3.get("minutesLate").asInt()).isEqualTo(10);
		assertThat(daily.get("rows").get(2).get("excused").asInt()).isEqualTo(1);
		assertThat(get(t.base() + "/reports/daily", s.admin()).get("rows")).hasSize(4); // the default is the last 30 days
		call(HttpMethod.GET, t.base() + "/reports/daily?from=2026-01-01&to=2026-11-01", s.admin(), null, 400);
		call(HttpMethod.GET, t.base() + "/reports/daily?from=2026-11-05&to=2026-11-01", s.admin(), null, 400);

		JsonNode monthly = get(t.base() + "/reports/monthly?month=2026-11", s.admin());
		assertThat(monthly.get("month").asString()).isEqualTo("2026-11");
		JsonNode rows = monthly.get("staff").get("items");
		// ACTIVE staff (lead, subject, assistant, support) and the former staff member who has a record that month
		assertThat(rows).hasSize(5);
		JsonNode leadRow = null;
		for (JsonNode row : rows) {
			if (row.get("staffId").asString().equals(lead.toString())) {
				leadRow = row;
			}
		}
		assertThat(leadRow.get("marked").asInt()).isEqualTo(3);
		assertThat(leadRow.get("present").asInt()).isEqualTo(1);
		assertThat(leadRow.get("late").asInt()).isEqualTo(1);
		assertThat(leadRow.get("absent").asInt()).isEqualTo(1);
		assertThat(leadRow.get("minutesLate").asInt()).isEqualTo(10);
		assertThat(get(t.base() + "/reports/monthly?month=2026-10", s.admin()).get("staff").get("items")).hasSize(4);
		assertThat(get(t.base() + "/reports/monthly", s.admin()).get("month").asString()).isEqualTo("2026-11");
		JsonNode page1 = get(t.base() + "/reports/monthly?month=2026-11&limit=2", s.admin());
		assertThat(page1.get("staff").get("items")).hasSize(2);
		assertThat(get(t.base() + "/reports/monthly?month=2026-11&limit=2&cursor=" + page1.get("staff").get("nextCursor").asString(), s.admin())
			.get("staff").get("items")).hasSize(2);
		call(HttpMethod.GET, t.base() + "/reports/monthly?month=2026-13", s.admin(), null, 400);
		call(HttpMethod.GET, t.base() + "/reports/monthly?month=November", s.admin(), null, 400);

		// CSV: BOM, CRLF, localized headers, formulas neutralized, no notes, audited
		MockHttpServletResponse csv = this.mvc
			.perform(MockMvcRequestBuilders.get(t.base() + "/reports/monthly.csv?month=2026-11").with(s.admin().bearer()))
			.andReturn()
			.getResponse();
		assertThat(csv.getStatus()).isEqualTo(200);
		assertThat(csv.getContentType()).startsWith("text/csv");
		assertThat(csv.getHeader("Content-Disposition")).contains("staff-attendance-2026-11.csv");
		assertThat(csv.getHeader("Cache-Control")).contains("no-store");
		byte[] bytes = csv.getContentAsByteArray();
		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).startsWith("﻿Staff member,Type,Job title,Marks,Present,Absent,Late,Half day,Excused,Minutes late\r\n");
		assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\")\",TEACHER,'+cmd|calc,3,1,1,1,0,0,10\r\n");
		assertThat(text).doesNotContain(",=HYPERLINK").doesNotContain(NOTE);
		assertThat(text.lines().count()).isEqualTo(6);
		MockHttpServletResponse dailyCsv = this.mvc
			.perform(MockMvcRequestBuilders.get(t.base() + "/reports/daily.csv?from=2026-11-01&to=2026-11-20").with(s.admin().bearer()))
			.andReturn()
			.getResponse();
		assertThat(new String(dailyCsv.getContentAsByteArray(), StandardCharsets.UTF_8))
			.startsWith("﻿Date,Marks,Present,Absent,Late,Half day,Excused,Minutes late\r\n2026-11-02,2,2,0,0,0,0,0\r\n");
		MockHttpServletResponse amharic = this.mvc
			.perform(MockMvcRequestBuilders.get(t.base() + "/reports/monthly.csv?month=2026-11").with(s.admin().bearer()).header("Accept-Language", "am"))
			.andReturn()
			.getResponse();
		assertThat(new String(amharic.getContentAsByteArray(), StandardCharsets.UTF_8)).contains("ሠራተኛ");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'staff_attendance.report.export' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(3);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_logs where school_id = ? and action = 'staff_attendance.report.export'
				  and (cast(metadata as text) like '%HYPERLINK%' or cast(metadata as text) like ? or cast(metadata as text) like '%calc%')""",
				Integer.class, s.schoolId(), "%" + NOTE + "%")).isZero();
		assertThat(this.jdbc.queryForObject("select cast(metadata as text) from audit_logs where action = 'staff_attendance.report.export' order by id limit 1",
				String.class)).contains("MONTHLY").contains("2026-11-01").contains("2026-11-30");

		// invalid input and refused callers write no audit row
		call(HttpMethod.GET, t.base() + "/reports/monthly.csv?month=nope", s.admin(), null, 400);
		call(HttpMethod.GET, t.base() + "/reports/monthly.csv", s.lead(), null, 403);
		call(HttpMethod.GET, t.base() + "/reports/daily.csv", s.guardian1(), null, 404);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'staff_attendance.report.export' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(3);
	}

	@Test
	void theStaffExportsAreRateLimited() throws Exception {
		StaffSetup t = staffSetup("Staff export limit school");
		Persona exporter = this.personas.schoolAdmin(t.s().schoolId(), "staff-exporter");
		int limited = 0;
		for (int i = 0; i < 12; i++) {
			String path = (i % 2 == 0) ? "/reports/daily.csv" : "/reports/monthly.csv";
			MockHttpServletResponse response = this.mvc.perform(MockMvcRequestBuilders.get(t.base() + path).with(exporter.bearer()))
				.andReturn()
				.getResponse();
			if (response.getStatus() == 429) {
				limited++;
				assertThat(response.getContentAsString()).contains("staff_attendance.export_rate_limited");
			}
			else {
				assertThat(response.getStatus()).isEqualTo(200);
			}
		}
		// ten per ten minutes for the daily and the monthly together, and the roll exports keep their own counter
		assertThat(limited).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'staff_attendance.report.export' and school_id = ?",
				Integer.class, t.s().schoolId())).isEqualTo(10);
		assertThat(this.mvc.perform(MockMvcRequestBuilders.get(t.s().api() + "/attendance/report.csv").with(exporter.bearer())).andReturn()
			.getResponse().getStatus()).isEqualTo(200);
	}

}
