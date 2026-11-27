package com.nemaz.school.attendance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rotating QR (M7e): a teacher shows a code, the student scans it with their own app. The code window (current and previous
 * only), replays and forgeries, who may check in for which class, duplicates, the roll's own rules (conflicts, the window), the
 * live list and its removals, the rate limits, and the switches (method, active school, feature).
 */
@IntegrationTest
class RotatingQrApiTest extends QrTestSupport {

	private Setup qrSetup(String label) {
		Setup s = setup(label);
		methods(s, ALL_METHODS.toArray(String[]::new));
		atNoon();
		return s;
	}

	private UUID id(JsonNode node) {
		return uuid(node, "id");
	}

	@Test
	void aStudentChecksInWithTheCodeOnTheTeachersScreen() throws Exception {
		Setup s = qrSetup("Qr happy school");
		assertThat(count("select count(*) from attendance_sessions where class_id = ?", s.classId())).isZero();
		JsonNode session = openCode(s, s.lead(), map("lateAfterMinutes", 10), 201);
		UUID id = id(session);
		assertThat(session.get("open").asBoolean()).isTrue();
		assertThat(session.get("rotationSeconds").asInt()).isEqualTo(30);
		assertThat(session.get("date").asString()).isEqualTo("2026-11-27");
		assertThat(session.get("slot").asString()).isEqualTo("DAY");
		assertThat(session.get("subjectKind").asString()).isEqualTo("STUDENT");
		assertThat(session.get("timeZone").asString()).isEqualTo("Africa/Addis_Ababa");
		// opening a code creates no roll (the dashboard would count an empty one as taken)
		assertThat(count("select count(*) from attendance_sessions where class_id = ?", s.classId())).isZero();

		// the display gets the current window and about ten minutes ahead, never cached
		var response = perform(HttpMethod.GET, qrBase(s) + "/" + id + "/codes", s.lead(), null).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		JsonNode codes = this.json.readTree(response.getContentAsString());
		assertThat(codes.get("codes").size()).isBetween(19, 21);
		JsonNode first = codes.get("codes").get(0);
		assertThat(first.get("code").asString()).isEqualTo(code(id, window(30)));
		assertThat(first.get("payload").asString()).isEqualTo(id + "." + code(id, window(30)));
		assertThat(codes.get("codes").get(1).get("code").asString()).isEqualTo(code(id, window(30) + 1));
		assertThat(first.get("code").asString()).hasSize(22);

		JsonNode result = checkIn(s, id, s.student1(), currentCode(id), 200);
		assertThat(result.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(result.get("status").asString()).isEqualTo("PRESENT");
		assertThat(result.has("person")).isFalse();
		assertThat(result.has("current")).isFalse();
		UUID checkin = uuid(result, "checkinId");

		// the roll exists now, credited to the teacher who opened the code; the mark is the student's own, tied to its check-in
		assertThat(this.jdbc.queryForObject("select taken_by from attendance_sessions where class_id = ?", UUID.class, s.classId()))
			.isEqualTo(s.leadId());
		assertThat(this.jdbc.queryForMap("select source, recorded_by, checkin_id, status from attendance_records where student_id = ?", s.s1()))
			.containsEntry("source", "QR_ROTATING")
			.containsEntry("recorded_by", s.s1())
			.containsEntry("checkin_id", checkin)
			.containsEntry("status", "PRESENT");
		JsonNode roll = roll(s, s.lead(), today());
		assertThat(student(roll, s.s1()).get("record").get("source").asString()).isEqualTo("QR_ROTATING");
		assertThat(roll.get("counts").get("present").asInt()).isEqualTo(1);

		JsonNode detail = sessionDetail(s, id, s.lead());
		assertThat(detail.get("session").get("acceptedCount").asInt()).isEqualTo(1);
		assertThat(detail.get("entries")).hasSize(1);
		assertThat(detail.get("entries").get(0).get("displayName").asString()).isEqualTo("Abel");
		assertThat(detail.get("entries").get(0).get("removed").asBoolean()).isFalse();
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED");
	}

	@Test
	void onlyTheCurrentAndThePreviousWindowAreAcceptedAndNoOtherCodeIs() throws Exception {
		Setup s = qrSetup("Qr window school");
		Persona s2 = newClassStudent(s, "Dawit");
		Persona s3 = newClassStudent(s, "Eden");
		UUID id = openCode(s);
		long w = window(30);

		// a code of the previous window still works (a slow scan), one of the next window is not valid yet
		moveClockTo("2026-11-27T09:00:35Z");
		assertThat(window(30)).isEqualTo(w + 1);
		checkIn(s, id, s.student1(), code(id, w), 200);
		JsonNode future = checkIn(s, id, s2, code(id, w + 2), 409);
		assertThat(future.get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		assertThat(future.get("code").asString()).isEqualTo("attendance.checkin_code_expired");
		assertThat(future.get("detail").asString()).isNotBlank();

		// two windows later the first code is a replay: refused, logged
		moveClockTo("2026-11-27T09:01:05Z");
		assertThat(window(30)).isEqualTo(w + 2);
		assertThat(checkIn(s, id, s2, code(id, w), 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		// a forged code, a code of another session and the empty-ish code are refused alike
		assertThat(checkIn(s, id, s2, "AAAAAAAAAAAAAAAAAAAAAA", 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		assertThat(checkIn(s, id, s2, code(UUID.randomUUID(), w + 2), 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s2, map("code", "short"), 400);
		assertThat(recordStatus(s.classId(), s2.userId())).isNull();
		// the current code works for whoever is in the class (the known limit: a photograph is shared with a friend; the list shows it)
		checkIn(s, id, s3, code(id, w + 2), 200);
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED", "REJECTED_EXPIRED", "REJECTED_EXPIRED", "REJECTED_EXPIRED",
				"REJECTED_EXPIRED", "ACCEPTED");
	}

	@Test
	void oneAcceptedCheckInPerStudentPerSessionAndTheLedgerIsAppendOnly() throws Exception {
		Setup s = qrSetup("Qr duplicate school");
		UUID id = openCode(s);
		checkIn(s, id, s.student1(), currentCode(id), 200);
		JsonNode again = checkIn(s, id, s.student1(), currentCode(id), 409);
		assertThat(again.get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(again.get("code").asString()).isEqualTo("attendance.checkin_duplicate");
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED", "REJECTED_DUPLICATE");
		assertThat(countRecords(s.classId())).isEqualTo(1);
		// a second accepted row for the same person and session cannot exist, and the ledger cannot be edited or erased
		assertThatThrownBy(() -> this.jdbc.update("update attendance_checkins set result = 'ACCEPTED' where school_id = ?", s.schoolId()))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("delete from attendance_checkins where school_id = ?", s.schoolId()))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into attendance_checkins (id, school_id, subject_type, student_id, class_id, attendance_date, slot, method,
				                                 scanner_user_id, qr_session_id, result, marked_status, checked_in_at)
				values (?, ?, 'STUDENT', ?, ?, date '2026-11-27', 'DAY', 'QR_ROTATING', ?, ?, 'ACCEPTED', 'PRESENT', now())""", newId(),
				s.schoolId(), s.s1(), s.classId(), s.s1(), id)).isInstanceOf(DataAccessException.class);
	}

	@Test
	void aStudentWhoIsNotInTheClassAndAStudentOfAnotherSchoolCannotCheckIn() throws Exception {
		Setup s = qrSetup("Qr class school");
		Setup other = setup("Qr other school");
		methods(other, ALL_METHODS.toArray(String[]::new));
		UUID id = openCode(s);
		// a student of the school, but of another class
		UUID outsider = this.personas.newStudent(s.schoolId(), "Fikru");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), outsider);
		JsonNode refused = checkIn(s, id, person("Fikru", outsider), currentCode(id), 409);
		assertThat(refused.get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
		assertThat(refused.get("code").asString()).isEqualTo("attendance.student_not_in_class");
		assertThat(recordStatus(s.classId(), outsider)).isNull();
		assertThat(this.jdbc.queryForObject("select class_id from attendance_checkins where student_id = ?", UUID.class, outsider))
			.isEqualTo(s.classId());
		// a student of another school: the school and the session do not exist for them
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", other.student1(), map("code", currentCode(id)), 404)))
			.contains("school.not_found");
		assertThat(errorCode(call(HttpMethod.POST, qrBase(other) + "/" + id + "/check-ins", other.student1(), map("code", currentCode(id)), 404)))
			.contains("attendance.qr_session_not_found");
		// and the other school's own session id does not open anything of this school either
		UUID theirs = uuid(openCode(other, other.lead(), map(), 201), "id");
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + theirs + "/check-ins", s.student1(), map("code", code(theirs, window(30))), 404)))
			.contains("attendance.qr_session_not_found");
		// adults never check in as students: a guardian, a teacher
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.lead(), map("code", currentCode(id)), 403)))
			.contains("attendance.checkin_not_allowed");
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.guardian1(), map("code", currentCode(id)), 404);
		// anonymous callers never check in
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", null, map("code", currentCode(id)), 401);
		assertThat(ledgerResults(s.schoolId())).containsExactly("REJECTED_NOT_ENROLLED");
	}

	@Test
	void aCodeIsRefusedOutsideItsSessionClosedExpiredOrOnAnotherDay() throws Exception {
		Setup s = qrSetup("Qr time school");
		Persona s2 = newClassStudent(s, "Dawit");
		UUID id = openCode(s);
		// after its fifteen minutes
		moveClockTo("2026-11-27T09:20:05Z");
		JsonNode late = checkIn(s, id, s.student1(), currentCode(id), 409);
		assertThat(late.get("result").asString()).isEqualTo("REJECTED_WINDOW");
		assertThat(late.get("code").asString()).isEqualTo("attendance.checkin_window_closed");
		assertThat(errorCode(get409(qrBase(s) + "/" + id + "/codes", s.lead()))).contains("attendance.qr_session_closed");

		// a closed code stops at once, and closing twice is harmless
		atNoon();
		UUID second = openCode(s);
		JsonNode closed = call(HttpMethod.POST, qrBase(s) + "/" + second + "/close", s.lead(), null, 200);
		assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
		assertThat(closed.get("open").asBoolean()).isFalse();
		call(HttpMethod.POST, qrBase(s) + "/" + second + "/close", s.lead(), null, 200);
		assertThat(checkIn(s, second, s2, currentCode(second), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");

		// a code opened late in the evening does not carry over midnight (the school's day, not UTC's)
		moveClockTo("2026-11-27T20:50:05Z");
		UUID evening = uuid(openCode(s, s.lead(), map("durationMinutes", 120), 201), "id");
		moveClockTo("2026-11-27T21:10:05Z");
		assertThat(this.clock.instant().atZone(ADDIS).toLocalDate().toString()).isEqualTo("2026-11-28");
		assertThat(checkIn(s, evening, s2, currentCode(evening), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		assertThat(countRecords(s.classId())).isZero();

		// opening a code closes the class's other open codes of the same roll: an old photograph dies with it
		atNoon();
		UUID older = openCode(s);
		UUID newer = openCode(s);
		assertThat(sessionDetail(s, older, s.lead()).get("session").get("status").asString()).isEqualTo("CLOSED");
		assertThat(checkIn(s, older, s2, currentCode(older), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		checkIn(s, newer, s2, currentCode(newer), 200);
	}

	private JsonNode get409(String url, Persona as) throws Exception {
		return call(HttpMethod.GET, url, as, null, 409);
	}

	@Test
	void aScanNeverOverwritesATeachersMarkAndFollowsTheSameRulesAsTheRoll() throws Exception {
		Setup s = qrSetup("Qr conflict school");
		Persona s2 = newClassStudent(s, "Dawit");
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT", null, "private-teacher-note", null), mark(s2.userId(), "PRESENT")), 200);
		UUID id = openCode(s);
		// a different mark is a conflict: nothing is written, the teacher decides; the student never sees the stored mark or its note
		JsonNode conflict = checkIn(s, id, s.student1(), currentCode(id), 409);
		assertThat(conflict.get("result").asString()).isEqualTo("REJECTED_CONFLICT");
		assertThat(conflict.has("current")).isFalse();
		assertThat(conflict.toString()).doesNotContain("private-teacher-note");
		assertThat(recordStatus(s.classId(), s.s1())).isEqualTo("ABSENT");
		// the same mark is a duplicate
		assertThat(checkIn(s, id, s2, currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(recordStatus(s.classId(), s2.userId())).isEqualTo("PRESENT");
		assertThat(count("select count(*) from attendance_records where class_id = ? and checkin_id is not null", s.classId())).isZero();
		// a check-in never marks absent and the roll's students who left the class cannot check in
		UUID former = this.personas.newStudent(s.schoolId(), "Gizaw");
		this.personas.addStudentToClass(s.schoolId(), s.classId(), former);
		this.jdbc.update("update class_students set status = 'FORMER', left_at = timestamptz '2026-11-01 00:00:00+00' where student_id = ?", former);
		assertThat(checkIn(s, id, person("Gizaw", former), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
	}

	@Test
	void aCheckInAfterTheThresholdIsLateAndEarlierOnesArePresent() throws Exception {
		Setup s = qrSetup("Qr late school");
		Persona s2 = newClassStudent(s, "Dawit");
		UUID id = id(openCode(s, s.lead(), map("lateAfterMinutes", 5), 201));
		moveClockTo("2026-11-27T09:03:05Z");
		assertThat(checkIn(s, id, s2, currentCode(id), 200).get("status").asString()).isEqualTo("PRESENT");
		moveClockTo("2026-11-27T09:07:35Z");
		JsonNode late = checkIn(s, id, s.student1(), currentCode(id), 200);
		assertThat(late.get("status").asString()).isEqualTo("LATE");
		assertThat(late.get("minutesLate").asInt()).isEqualTo(7);
		assertThat(this.jdbc.queryForMap("select status, minutes_late from attendance_records where student_id = ?", s.s1()))
			.containsEntry("status", "LATE")
			.containsEntry("minutes_late", 7);
	}

	@Test
	void theTeachersLiveListShowsEveryCheckInAndTheTeacherCanTakeOneOff() throws Exception {
		Setup s = qrSetup("Qr list school");
		Persona s2 = newClassStudent(s, "Dawit");
		UUID id = openCode(s);
		UUID first = uuid(checkIn(s, id, s.student1(), currentCode(id), 200), "checkinId");
		UUID second = uuid(checkIn(s, id, s2, currentCode(id), 200), "checkinId");
		assertThat(sessionDetail(s, id, s.lead()).get("entries")).hasSize(2);
		int versionBefore = roll(s, s.lead(), today()).get("sessionVersion").asInt();

		// removing an entry takes the mark off the roll, appends the removal, bumps the roll's version
		JsonNode after = call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + first, s.lead(), null, 200);
		assertThat(after.get("session").get("acceptedCount").asInt()).isEqualTo(1);
		assertThat(after.get("entries").get(0).get("removed").asBoolean()).isTrue();
		assertThat(recordStatus(s.classId(), s.s1())).isNull();
		assertThat(roll(s, s.lead(), today()).get("sessionVersion").asInt()).isGreaterThan(versionBefore);
		assertThat(count("select count(*) from attendance_checkin_removals where checkin_id = ?", first)).isEqualTo(1);
		assertThat(count("select count(*) from audit_logs where action = 'attendance.checkin.remove' and school_id = ?", s.schoolId())).isEqualTo(1);
		// it cannot be removed twice, and the student cannot check in again to this session (the teacher marks them by hand)
		assertThat(errorCode(call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + first, s.lead(), null, 404)))
			.contains("attendance.checkin_not_found");
		assertThat(checkIn(s, id, s.student1(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		// a mark the teacher has changed since is not undone behind their back
		JsonNode record = student(roll(s, s.lead(), today()), s2.userId()).get("record");
		take(s, s.lead(), rollBody(today(), false, mark(s2.userId(), "LATE", 4, null, record.get("version").asInt())), 200);
		assertThat(errorCode(call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + second, s.lead(), null, 409)))
			.contains("attendance.checkin_changed");
		assertThat(recordStatus(s.classId(), s2.userId())).isEqualTo("LATE");
		// the removal rows cannot be edited either
		assertThatThrownBy(() -> this.jdbc.update("delete from attendance_checkin_removals")).isInstanceOf(DataAccessException.class);
	}

	@Test
	void onlyThePeopleWhoRunTheRollSeeTheSessionAndItsCodes() throws Exception {
		Setup s = qrSetup("Qr access school");
		UUID id = openCode(s);
		String codes = qrBase(s) + "/" + id + "/codes";
		for (Persona runner : List.of(s.lead(), s.subject(), s.assistant(), s.admin())) {
			get(qrBase(s) + "/" + id, runner);
			get(codes, runner);
		}
		// a student of the class may not; a guardian is no member of the school and finds nothing; a teacher of another class cannot find it
		call(HttpMethod.GET, qrBase(s) + "/" + id, s.student1(), null, 403);
		call(HttpMethod.GET, codes, s.student1(), null, 403);
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/close", s.student1(), null, 403);
		call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.student1(), map(), 403);
		call(HttpMethod.GET, qrBase(s) + "/" + id, s.guardian1(), null, 404);
		call(HttpMethod.GET, codes, s.guardian1(), null, 404);
		call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.guardian1(), map(), 403);
		assertThat(errorCode(call(HttpMethod.GET, qrBase(s) + "/" + id, s.otherTeacher(), null, 404))).contains("attendance.qr_session_not_found");
		call(HttpMethod.GET, codes, s.otherTeacher(), null, 404);
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/close", s.otherTeacher(), null, 404);
		call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.otherTeacher(), map(), 404);
		// another school's administrator finds nothing
		Setup other = setup("Qr access other");
		call(HttpMethod.GET, qrBase(s) + "/" + id, other.admin(), null, 404);
		call(HttpMethod.GET, qrBase(other) + "/" + id, other.admin(), null, 404);
		call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", other.admin(), map(), 404);
	}

	@Test
	void theSwitchesRefuseACodeAndACheckIn() throws Exception {
		Setup s = setup("Qr switch school");
		atNoon();
		// nothing but manual marking is on by default
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map(), 403))).contains("attendance.method_disabled");
		methods(s, "MANUAL", "QR_STATIC");
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map(), 403))).contains("attendance.method_disabled");
		methods(s, "MANUAL", "QR_ROTATING");
		UUID id = openCode(s);
		// switching it off refuses the check-in as well
		methods(s, "MANUAL");
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(), map("code", currentCode(id)), 403)))
			.contains("attendance.method_disabled");
		methods(s, "MANUAL", "QR_ROTATING");
		// a school that is not active yet
		this.jdbc.update("update schools set status = 'ONBOARDING' where id = ?", s.schoolId());
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map(), 403))).contains("attendance.school_not_active");
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(), map("code", currentCode(id)), 403)))
			.contains("attendance.school_not_active");
		this.jdbc.update("update schools set status = 'ACTIVE' where id = ?", s.schoolId());
		// the feature switched off
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('attendance', 'SCHOOL', ?, false)",
				s.schoolId());
		this.caches.getCache("school-configuration").clear();
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map(), 403))).contains("attendance.feature_disabled");
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(), map("code", currentCode(id)), 403)))
			.contains("attendance.feature_disabled");
		assertThat(ledgerResults(s.schoolId())).isEmpty();
		this.jdbc.update("delete from feature_overrides where school_id = ?", s.schoolId());
		this.caches.getCache("school-configuration").clear();
		// a bad request: a slot the school does not use, a duration that is too long
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map("slot", "P3"), 400))).contains("attendance.slot_not_enabled");
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map("durationMinutes", 500), 400)))
			.contains("attendance.qr_invalid_duration");
	}

	@Test
	void theRollsLockWindowRefusesOpeningACodeForALockedDay() throws Exception {
		Setup s = qrSetup("Qr lock school");
		setting(s.schoolId(), "attendance.lock_after_hours", 0);
		// 23:30 local: the day has half an hour left, a code can be opened; at 00:10 the next day it is another day anyway
		moveClockTo("2026-11-27T20:30:05Z");
		UUID id = uuid(openCode(s, s.lead(), map("durationMinutes", 120), 201), "id");
		moveClockTo("2026-11-27T21:05:05Z");
		assertThat(checkIn(s, id, s.student1(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		assertThat(countRecords(s.classId())).isZero();
	}

	@Test
	void everyCheckInIsRateLimitedPerStudent() throws Exception {
		Setup s = qrSetup("Qr student limit school");
		UUID id = openCode(s);
		for (int i = 0; i < 10; i++) {
			assertThat(checkIn(s, id, s.student1(), "AAAAAAAAAAAAAAAAAAAAAA", 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		}
		JsonNode limited = call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(), map("code", currentCode(id)), 429);
		assertThat(errorCode(limited)).contains("attendance.checkin_rate_limited");
		// even the right code waits; another student is not affected; the refused ones are the only ledger rows of the student
		assertThat(countRecords(s.classId())).isZero();
		Persona s2 = newClassStudent(s, "Dawit");
		checkIn(s, id, s2, currentCode(id), 200);
		assertThat(count("select count(*) from attendance_checkins where student_id = ?", s.s1())).isEqualTo(10);
	}

	@Test
	void everyRotatingSessionIsRateLimitedPerClassButOnlyForPeopleWhoMayCheckIn() throws Exception {
		Setup s = qrSetup("Qr class limit school");
		UUID id = openCode(s);
		// application-test.yaml lowers the per-session allowance to 25 a minute; outsiders do not use any of it
		List<Persona> outsiders = new ArrayList<>();
		for (int i = 0; i < 26; i++) {
			UUID outsider = this.personas.newStudent(s.schoolId(), "Out" + i);
			this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), outsider);
			outsiders.add(person("Out" + i, outsider));
		}
		for (Persona outsider : outsiders) {
			assertThat(checkIn(s, id, outsider, currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
		}
		List<Persona> insiders = new ArrayList<>();
		for (int i = 0; i < 26; i++) {
			insiders.add(newClassStudent(s, "In" + i));
		}
		for (int i = 0; i < 25; i++) {
			assertThat(checkIn(s, id, insiders.get(i), "AAAAAAAAAAAAAAAAAAAAAA", 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
		}
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", insiders.get(25), map("code", currentCode(id)), 429)))
			.contains("attendance.checkin_rate_limited");
	}

	@Test
	void aPeriodCodeCreatesTheRollWithItsSubjectSoTheTeacherCanStillTakeItWithThatSubject() throws Exception {
		Setup s = qrSetup("Qr period school");
		setting(s.schoolId(), "attendance.slots", List.of("DAY", "P1"));
		UUID maths = this.jdbc.queryForObject("select id from subjects where school_id = ? and code = 'M'", UUID.class, s.schoolId());
		this.jdbc.update("insert into class_subjects (class_id, subject_id, school_id) values (?, ?, ?)", s.classId(), maths, s.schoolId());
		// a subject belongs to a period only, and to one of the class's subjects
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map("subjectId", maths), 400)))
			.contains("attendance.subject_requires_period");
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", s.lead(), map("slot", "P1", "subjectId", UUID.randomUUID()), 400)))
			.contains("attendance.invalid_subject");
		UUID id = uuid(openCode(s, s.lead(), map("slot", "P1", "subjectId", maths), 201), "id");
		checkIn(s, id, s.student1(), currentCode(id), 200);
		JsonNode roll = get(s.rollUrl() + "?date=" + today() + "&slot=P1", s.lead());
		assertThat(uuid(roll, "subjectId")).isEqualTo(maths);
		assertThat(student(roll, s.s1()).get("record").get("source").asString()).isEqualTo("QR_ROTATING");
		// the teacher takes the same roll with the same subject (no 409 attendance.subject_mismatch)
		JsonNode taken = take(s, s.lead(), map("date", today().toString(), "slot", "P1", "subjectId", maths, "records", list(mark(s.s2(), "PRESENT"))), 200);
		assertThat(taken.get("applied").asInt()).isEqualTo(1);
	}

	@Test
	void noLocationIsAcceptedOrStoredAnywhereInAttendance() throws Exception {
		Setup s = qrSetup("Qr location school");
		UUID id = openCode(s);
		// a check-in that also carries a position, an address and a network identity is processed without them
		JsonNode result = call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(),
				map("code", currentCode(id), "latitude", 9.0054, "longitude", 38.7636, "location", map("lat", 9.0, "lng", 38.7), "gps", "9.0,38.7",
						"address", "Bole", "ip", "10.0.0.1", "accuracy", 5),
				200);
		assertThat(result.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(result.toString()).doesNotContainIgnoringCase("latitude").doesNotContainIgnoringCase("longitude");
		// no column that could hold one exists in any attendance table, and no schema of the API names one
		List<String> columns = this.jdbc.queryForList("""
				select table_name || '.' || column_name from information_schema.columns
				where table_schema = 'public' and (table_name like 'attendance_%' or table_name like 'staff_attendance_%')
				  and (column_name ~* '(^|_)(lat|latitude|lng|lon|long|longitude|geo|gps|location|position|address|ip|ip_address|coords?|accuracy|device_location)($|_)'
				       or column_name ~* 'geo|gps|locat|latitude|longitude')""", String.class);
		assertThat(columns).isEmpty();
		JsonNode docs = this.json.readTree(this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")
			.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("docs")))
			.andReturn().getResponse().getContentAsString());
		for (String schema : List.of("QrCheckinRequest", "StaticScanRequest", "StaffScanRequest", "OpenClassQrSessionRequest",
				"OpenStaffQrSessionRequest", "CheckinResult", "AttendanceCredentialView", "IssuedAttendanceCredential")) {
			String text = docs.get("components").get("schemas").get(schema).get("properties").toString().toLowerCase();
			assertThat(text).as(schema).doesNotContain("latitude", "longitude", "location", "gps", "geo", "coordinate");
		}
	}

}
