package com.nemaz.school.attendance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Taking, reading and correcting a roll: semantics, versions and conflicts, dates, slots, the lock and who may. */
@IntegrationTest
class RollApiTest extends AttendanceTestSupport {

	@SafeVarargs
	private static Map<String, Object> body(LocalDate date, boolean all, Map<String, Object>... marks) {
		return rollBody(date, all, marks);
	}

	@Test
	void aTeacherTakesTheRollWithEveryonePresentByDefaultAndCorrectsIt() throws Exception {
		Setup s = setup("Roll school");
		JsonNode first = take(s, s.lead(), body(today(), true, mark(s.s1(), "ABSENT", null, "no word from home", null)), 200);
		assertThat(first.get("applied").asInt()).isEqualTo(3);
		assertThat(first.get("conflicts").asInt()).isZero();
		assertThat(first.get("sessionVersion").asInt()).isEqualTo(1);
		assertThat(outcome(first, s.s2()).get("outcome").asString()).isEqualTo("APPLIED");

		JsonNode view = roll(s, s.lead(), today());
		assertThat(view.get("students")).hasSize(3);
		assertThat(student(view, s.s1()).get("record").get("status").asString()).isEqualTo("ABSENT");
		assertThat(student(view, s.s1()).get("record").get("note").asString()).isEqualTo("no word from home");
		assertThat(student(view, s.s1()).get("record").get("version").asInt()).isZero();
		assertThat(student(view, s.s2()).get("record").get("status").asString()).isEqualTo("PRESENT");
		assertThat(view.get("counts").get("present").asInt()).isEqualTo(2);
		assertThat(view.get("counts").get("absent").asInt()).isEqualTo(1);
		assertThat(view.get("counts").get("unmarked").asInt()).isZero();
		assertThat(view.get("locked").asBoolean()).isFalse();
		assertThat(view.get("timeZone").asString()).isEqualTo("Africa/Addis_Ababa");
		// the students come with a name and no personal data
		assertThat(student(view, s.s1()).get("displayName").asString()).isEqualTo("Abel");
		assertThat(student(view, s.s1()).has("dateOfBirth")).isFalse();

		// a correction of an existing record carries that record's version
		JsonNode fix = take(s, s.lead(), body(today(), false, mark(s.s1(), "LATE", 10, null, 0)), 200);
		assertThat(fix.get("applied").asInt()).isEqualTo(1);
		assertThat(fix.get("sessionVersion").asInt()).isEqualTo(2);
		JsonNode record = outcome(fix, s.s1()).get("record");
		assertThat(record.get("version").asInt()).isEqualTo(1);
		assertThat(record.get("minutesLate").asInt()).isEqualTo(10);
		assertThat(absent(record, "note")).isTrue();
	}

	@Test
	void omittedStudentsStayUnmarkedAndMarkAllPresentNeverTouchesExistingRecords() throws Exception {
		Setup s = setup("Semantics school");
		take(s, s.lead(), body(today(), false, mark(s.s1(), "ABSENT")), 200);
		JsonNode view = roll(s, s.lead(), today());
		assertThat(absent(student(view, s.s2()), "record")).isTrue();
		assertThat(view.get("counts").get("unmarked").asInt()).isEqualTo(2);
		assertThat(view.get("counts").get("marked").asInt()).isEqualTo(1);

		// markAllPresent marks the students without a record and leaves the absent one absent
		JsonNode all = take(s, s.lead(), body(today(), true), 200);
		assertThat(all.get("applied").asInt()).isEqualTo(2);
		assertThat(student(roll(s, s.lead(), today()), s.s1()).get("record").get("status").asString()).isEqualTo("ABSENT");
		// the same call again changes nothing
		JsonNode again = take(s, s.lead(), body(today(), true), 200);
		assertThat(again.get("applied").asInt()).isZero();
		assertThat(again.get("sessionVersion").asInt()).isEqualTo(all.get("sessionVersion").asInt());
		// an empty request is an error
		assertThat(errorCode(take(s, s.lead(), body(today(), false), 400))).isEqualTo("attendance.nothing_to_record");
	}

	@Test
	void aMarkOverADifferentRecordNeedsItsVersionOtherwiseItIsAConflictAndNothingIsOverwritten() throws Exception {
		Setup s = setup("Conflict school");
		take(s, s.lead(), body(today(), false, mark(s.s1(), "ABSENT", null, "lead's note", null)), 200);

		// the subject teacher, who did not see it, marks the same student present
		JsonNode result = take(s, s.subject(), body(today(), false, mark(s.s1(), "PRESENT"), mark(s.s2(), "PRESENT")), 409);
		assertThat(result.get("conflicts").asInt()).isEqualTo(1);
		assertThat(result.get("applied").asInt()).isEqualTo(1);
		JsonNode conflict = outcome(result, s.s1());
		assertThat(conflict.get("outcome").asString()).isEqualTo("CONFLICT");
		assertThat(conflict.get("record").get("status").asString()).isEqualTo("ABSENT");
		assertThat(conflict.get("record").get("version").asInt()).isZero();
		assertThat(conflict.get("submitted").get("status").asString()).isEqualTo("PRESENT");
		assertThat(this.jdbc.queryForObject("select status from attendance_records where student_id = ?", String.class, s.s1()))
			.isEqualTo("ABSENT");
		assertThat(this.jdbc.queryForObject("select count(*) from attendance_records where student_id = ?", Integer.class, s.s2()))
			.isEqualTo(1);

		// two teachers who agree do not clash
		JsonNode agree = take(s, s.subject(), body(today(), false, mark(s.s1(), "ABSENT", null, "lead's note", null)), 200);
		assertThat(outcome(agree, s.s1()).get("outcome").asString()).isEqualTo("UNCHANGED");
		assertThat(outcome(agree, s.s1()).get("record").get("version").asInt()).isZero();

		// with the current version the edit goes through; with a stale one it does not
		JsonNode informed = take(s, s.subject(), body(today(), false, mark(s.s1(), "EXCUSED", null, null, 0)), 200);
		assertThat(outcome(informed, s.s1()).get("record").get("version").asInt()).isEqualTo(1);
		JsonNode stale = take(s, s.lead(), body(today(), false, mark(s.s1(), "ABSENT", null, null, 0)), 409);
		assertThat(outcome(stale, s.s1()).get("record").get("status").asString()).isEqualTo("EXCUSED");

		// the roll's own version also counts, while nobody changed the roll since
		int sessionVersion = roll(s, s.lead(), today()).get("sessionVersion").asInt();
		Map<String, Object> withBase = body(today(), false, mark(s.s1(), "LATE"));
		withBase.put("baseVersion", sessionVersion);
		JsonNode viaBase = take(s, s.lead(), withBase, 200);
		assertThat(viaBase.get("applied").asInt()).isEqualTo(1);
		Map<String, Object> staleBase = body(today(), false, mark(s.s1(), "ABSENT"));
		staleBase.put("baseVersion", sessionVersion);
		take(s, s.lead(), staleBase, 409);
	}

	@Test
	void validationFailuresWriteNothing() throws Exception {
		Setup s = setup("Validation school");
		// minutes late only with LATE, 1 to 600
		assertThat(errorCode(take(s, s.lead(), body(today(), false, mark(s.s1(), "PRESENT", 5, null, null)), 400)))
			.isEqualTo("attendance.invalid_minutes_late");
		assertThat(errorCode(take(s, s.lead(), body(today(), false, mark(s.s1(), "LATE", 601, null, null)), 400)))
			.isEqualTo("attendance.invalid_minutes_late");
		assertThat(errorCode(take(s, s.lead(), body(today(), false, mark(s.s1(), "PRESENT"), mark(s.s1(), "ABSENT")), 400)))
			.isEqualTo("attendance.duplicate_student");
		// a student of another class (or school) is not on this roll: nothing of the request is written
		UUID stranger = this.personas.newStudent(s.schoolId(), "Stranger");
		assertThat(errorCode(take(s, s.lead(), body(today(), false, mark(s.s1(), "PRESENT"), mark(stranger, "PRESENT")), 409)))
			.isEqualTo("attendance.student_not_in_class");
		assertThat(countRecords(s.classId())).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from attendance_sessions where class_id = ?", Integer.class, s.classId()))
			.isZero();
		// the note is short, the status is an enumeration
		take(s, s.lead(), body(today(), false, mark(s.s1(), "PRESENT", null, "x".repeat(201), null)), 400);
		take(s, s.lead(), body(today(), false, mark(s.s1(), "BUNKING")), 400);
	}

	@Test
	void slotsAreConfiguredPerSchool() throws Exception {
		Setup s = setup("Slot school");
		assertThat(roll(s, s.lead(), today()).get("slots")).extracting(JsonNode::asString).containsExactly("DAY");
		Map<String, Object> am = body(today(), false, mark(s.s1(), "PRESENT"));
		am.put("slot", "AM");
		assertThat(errorCode(take(s, s.lead(), am, 400))).isEqualTo("attendance.slot_not_enabled");
		setting(s.schoolId(), "attendance.slots", List.of("AM", "PM"));
		take(s, s.lead(), am, 200);
		Map<String, Object> pm = body(today(), false, mark(s.s1(), "ABSENT"));
		pm.put("slot", "PM");
		take(s, s.lead(), pm, 200);
		// DAY is not enabled any more, and the default slot of a read is the first configured one
		take(s, s.lead(), body(today(), false, mark(s.s1(), "PRESENT")), 400);
		assertThat(get(s.rollUrl() + "?date=" + today(), s.lead()).get("slot").asString()).isEqualTo("AM");
		assertThat(student(get(s.rollUrl() + "?date=" + today() + "&slot=PM", s.lead()), s.s1()).get("record").get("status")
			.asString()).isEqualTo("ABSENT");
		// a school cannot invent a slot name
		assertThatThrownBy(() -> setting(s.schoolId(), "attendance.slots", List.of("LUNCH"))).isInstanceOf(RuntimeException.class);
	}

	@Test
	void theDateIsTheSchoolLocalDateAndTheFutureIsRefusedAroundMidnightInAddisAbaba() throws Exception {
		Setup s = setup("Addis school");
		LocalDate day = LocalDate.of(2026, 11, 25);
		// 22:30 local (19:30 UTC): the 26th has not started and is more than the tolerance (1 hour) away
		moveClockTo("2026-11-25T19:30:00Z");
		assertThat(today()).isEqualTo(day);
		assertThat(errorCode(take(s, s.lead(), body(day.plusDays(1), true), 409))).isEqualTo("attendance.date_in_future");
		take(s, s.lead(), body(day, true), 200);
		// 23:30 local (20:30 UTC): within an hour of the next day, a roll for the 26th may be started
		moveClockTo("2026-11-25T20:30:00Z");
		take(s, s.lead(), body(day.plusDays(1), true), 200);
		assertThat(errorCode(take(s, s.lead(), body(day.plusDays(2), true), 409))).isEqualTo("attendance.date_in_future");
		// 00:30 local on the 26th, which is still the 25th in UTC at 21:30: the school's date is the 26th
		moveClockTo("2026-11-25T21:30:00Z");
		assertThat(get(s.rollUrl(), s.lead()).get("date").asString()).isEqualTo("2026-11-26");
	}

	@Test
	void theDateFollowsTheSchoolTimeZoneNotTheServers() throws Exception {
		Setup s = setup("Kiritimati school");
		// UTC+14: at 10:30 UTC on the 25th it is 00:30 on the 26th there, at the other end of the world it is the 24th
		this.jdbc.update("update schools set timezone = 'Pacific/Kiritimati' where id = ?", s.schoolId());
		moveClockTo("2026-11-25T10:30:00Z");
		JsonNode view = get(s.rollUrl(), s.lead());
		assertThat(view.get("date").asString()).isEqualTo("2026-11-26");
		take(s, s.lead(), body(LocalDate.of(2026, 11, 26), true), 200);
		assertThat(errorCode(take(s, s.lead(), body(LocalDate.of(2026, 11, 28), true), 409))).isEqualTo("attendance.date_in_future");

		Setup la = setup("Los Angeles school");
		this.jdbc.update("update schools set timezone = 'America/Los_Angeles' where id = ?", la.schoolId());
		// 07:30 UTC on the 25th is 23:30 on the 24th (UTC-8): the 25th may be started, the 26th may not
		moveClockTo("2026-11-25T07:30:00Z");
		assertThat(get(la.rollUrl(), la.lead()).get("date").asString()).isEqualTo("2026-11-24");
		take(la, la.lead(), body(LocalDate.of(2026, 11, 25), true), 200);
		assertThat(errorCode(take(la, la.lead(), body(LocalDate.of(2026, 11, 26), true), 409))).isEqualTo("attendance.date_in_future");
		// the same instant is the 25th, 10:30, in Addis Ababa
		Setup addis = setup("Same instant school");
		assertThat(get(addis.rollUrl(), addis.lead()).get("date").asString()).isEqualTo("2026-11-25");
	}

	@Test
	void aRollLocksAfterTheWindowEvenAcrossADaylightSavingChange() throws Exception {
		Setup s = setup("Lock DST school");
		this.jdbc.update("update schools set timezone = 'America/Los_Angeles' where id = ?", s.schoolId());
		setting(s.schoolId(), "attendance.lock_after_hours", 0);
		LocalDate sunday = LocalDate.of(2026, 11, 1); // the clocks go back: this local day has 25 hours
		moveClockTo("2026-11-01T20:00:00Z");
		take(s, s.lead(), body(sunday, true), 200);
		// the day ends at 2026-11-02T08:00Z (midnight PST), not at 07:00Z
		moveClockTo("2026-11-02T07:30:00Z");
		take(s, s.assistant(), body(sunday, false, mark(s.s1(), "ABSENT", null, null, 0)), 200);
		assertThat(roll(s, s.lead(), sunday).get("locked").asBoolean()).isFalse();
		moveClockTo("2026-11-02T08:01:00Z");
		assertThat(roll(s, s.lead(), sunday).get("locked").asBoolean()).isTrue();
		assertThat(errorCode(take(s, s.assistant(), body(sunday, false, mark(s.s1(), "PRESENT", null, null, 1)), 409)))
			.isEqualTo("attendance.session_locked");
	}

	@Test
	void aLockedRollIsEditedOnlyByTheLeadAndAdministratorsWithAReasonAndThatIsAudited() throws Exception {
		Setup s = setup("Lock school");
		LocalDate old = today().minusDays(3); // default window: locks 24 hours after the end of the date
		take(s, s.lead(), body(today(), true), 200);
		// nobody took the old roll: creating it late is an edit of a locked roll too
		assertThat(errorCode(take(s, s.assistant(), body(old, true), 409))).isEqualTo("attendance.session_locked");
		assertThat(errorCode(take(s, s.subject(), body(old, true), 409))).isEqualTo("attendance.session_locked");
		assertThat(errorCode(take(s, s.lead(), body(old, true), 400))).isEqualTo("attendance.override_reason_required");
		Map<String, Object> override = body(old, true);
		override.put("overrideReason", "Roll was kept on paper");
		JsonNode done = take(s, s.lead(), override, 200);
		assertThat(done.get("overridden").asBoolean()).isTrue();
		assertThat(this.jdbc.queryForList("select cast(metadata as text) from audit_logs where action = 'attendance.roll.override' and school_id = ?",
				String.class, s.schoolId())).singleElement().asString().contains("Roll was kept on paper");
		assertThat(roll(s, s.lead(), old).get("locked").asBoolean()).isTrue();
		assertThat(roll(s, s.lead(), old).get("mayEditLocked").asBoolean()).isTrue();
		assertThat(roll(s, s.assistant(), old).get("mayEditLocked").asBoolean()).isFalse();

		Map<String, Object> adminFix = body(old, false, mark(s.s1(), "ABSENT", null, null, 0));
		adminFix.put("overrideReason", "Parent called the office");
		JsonNode byAdmin = take(s, s.admin(), adminFix, 200);
		assertThat(byAdmin.get("overridden").asBoolean()).isTrue();

		// a longer window keeps rolls open
		setting(s.schoolId(), "attendance.lock_after_hours", 240);
		assertThat(roll(s, s.lead(), old).get("locked").asBoolean()).isFalse();
		take(s, s.assistant(), body(old, false, mark(s.s2(), "ABSENT", null, null, 0)), 200);
		// the roll of the day before yesterday under the default window is still open all of today
		setting(s.schoolId(), "attendance.lock_after_hours", 24);
		assertThat(roll(s, s.lead(), today().minusDays(1)).get("locked").asBoolean()).isFalse();
		assertThat(roll(s, s.lead(), today().minusDays(2)).get("locked").asBoolean()).isTrue();
	}

	@Test
	void studentsMustHaveBeenInTheClassOnThatDay() throws Exception {
		Setup s = setup("Membership school");
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		LocalDate lastWeek = today().minusDays(7);
		// joined three days ago, so not on last week's roll
		UUID newcomer = this.personas.newStudent(s.schoolId(), "Newcomer");
		this.personas.addStudentToClass(s.schoolId(), s.classId(), newcomer);
		this.jdbc.update("update class_students set joined_at = now() - interval '3 days' where student_id = ?", newcomer);
		// left two days ago: was there last week, is not on today's roll
		UUID leaver = this.personas.newStudent(s.schoolId(), "Leaver");
		this.personas.addStudentToClass(s.schoolId(), s.classId(), leaver);
		backdateMemberships(s.classId());
		this.jdbc.update("update class_students set joined_at = now() - interval '3 days' where student_id = ?", newcomer);
		this.jdbc.update("""
				update class_students set status = 'FORMER', left_at = now() - interval '2 days', left_reason = 'TRANSFERRED_CLASS'
				where student_id = ?""", leaver);

		JsonNode lastWeekRoll = roll(s, s.lead(), lastWeek);
		assertThat(lastWeekRoll.get("students")).extracting((n) -> n.get("studentId").asString())
			.contains(leaver.toString())
			.doesNotContain(newcomer.toString());
		JsonNode todayRoll = roll(s, s.lead(), today());
		assertThat(todayRoll.get("students")).extracting((n) -> n.get("studentId").asString())
			.contains(newcomer.toString())
			.doesNotContain(leaver.toString());
		assertThat(errorCode(take(s, s.lead(), body(lastWeek, false, mark(newcomer, "PRESENT")), 409)))
			.isEqualTo("attendance.student_not_in_class");
		assertThat(errorCode(take(s, s.lead(), body(today(), false, mark(leaver, "PRESENT")), 409)))
			.isEqualTo("attendance.student_not_in_class");
		take(s, s.lead(), body(lastWeek, false, mark(leaver, "PRESENT")), 200);
		// markAllPresent only marks the students of that day
		take(s, s.lead(), body(today(), true), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from attendance_records where student_id = ? and attendance_date = ?",
				Integer.class, leaver, java.sql.Date.valueOf(today()))).isZero();
	}

	@Test
	void anArchivedClassTakesNoAttendance() throws Exception {
		Setup s = setup("Archived school");
		call(HttpMethod.POST, s.api() + "/classes/" + s.classId() + "/archive", s.admin(), null, 200);
		assertThat(errorCode(take(s, s.lead(), body(today(), true), 409))).isEqualTo("class.archived");
		assertThat(errorCode(take(s, s.admin(), body(today(), true), 409))).isEqualTo("class.archived");
	}

	@Test
	void whoMayTakeTheRoll() throws Exception {
		Setup s = setup("Who school");
		take(s, s.lead(), body(today(), true), 200);
		take(s, s.subject(), body(today(), false, mark(s.s3(), "PRESENT")), 200);
		take(s, s.assistant(), body(today(), false, mark(s.s3(), "PRESENT")), 200);
		take(s, s.admin(), body(today(), false, mark(s.s3(), "PRESENT")), 200);
		setting(s.schoolId(), "attendance.assistants_can_take", false);
		assertThat(errorCode(take(s, s.assistant(), body(today(), false, mark(s.s3(), "PRESENT")), 403)))
			.isEqualTo("attendance.assistants_not_allowed");
		take(s, s.subject(), body(today(), false, mark(s.s3(), "PRESENT")), 200);
		// a teacher of another class, the children and the family may see the class or not, but may not take its roll
		assertThat(errorCode(take(s, s.otherTeacher(), body(today(), true), 404))).isEqualTo("class.not_found");
		assertThat(errorCode(take(s, s.student1(), body(today(), true), 403))).isEqualTo("class.access_denied");
		assertThat(errorCode(take(s, s.guardian1(), body(today(), true), 403))).isEqualTo("class.access_denied");
		Persona stranger = this.personas.newUser("stranger");
		take(s, stranger, body(today(), true), 404);
		// another school's administrator cannot tell the class exists
		UUID otherSchool = this.personas.newSchool("Other school");
		Persona otherAdmin = this.personas.schoolAdmin(otherSchool, "other-admin");
		assertThat(errorCode(take(s, otherAdmin, body(today(), true), 404))).isEqualTo("class.not_found");
		call(HttpMethod.PUT, s.rollUrl(), null, body(today(), true), 401);
		// reading: students and guardians who can see the class are told they may not
		call(HttpMethod.GET, s.rollUrl(), s.student1(), null, 403);
		call(HttpMethod.GET, s.rollUrl(), s.guardian1(), null, 403);
		call(HttpMethod.GET, s.rollUrl(), s.otherTeacher(), null, 404);
		get(s.rollUrl(), s.assistant());
	}

	@Test
	void parallelSubmissionsOfTheSameRollWriteOnce() throws Exception {
		Setup s = setup("Parallel roll school");
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				Persona as = (i % 2 == 0) ? s.lead() : s.subject();
				Callable<Integer> call = () -> perform(HttpMethod.PUT, s.rollUrl(), as, body(today(), true, mark(s.s1(), "ABSENT")))
					.andReturn()
					.getResponse()
					.getStatus();
				results.add(pool.submit(call));
			}
			for (Future<Integer> f : results) {
				assertThat(f.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from attendance_sessions where class_id = ?", Integer.class, s.classId()))
			.isEqualTo(1);
		assertThat(countRecords(s.classId())).isEqualTo(3);
		assertThat(this.jdbc.queryForObject("select max(version) from attendance_records where class_id = ?", Integer.class,
				s.classId())).isZero();
	}

	@Test
	void everyRollWriteIsAuditedWithoutNotesOrNames() throws Exception {
		Setup s = setup("Audit school");
		String note = "private-note-" + UUID.randomUUID();
		take(s, s.lead(), body(today(), true, mark(s.s1(), "ABSENT", null, note, null)), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'attendance.roll.save' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where cast(metadata as text) like ?", Integer.class,
				"%" + note + "%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class,
				"%" + note + "%")).isZero();
	}

}
