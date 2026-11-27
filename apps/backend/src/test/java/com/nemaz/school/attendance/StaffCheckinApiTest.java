package com.nemaz.school.attendance;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff check-in by QR (M7e): the manager's rotating code and the staff member's own scan, the manager scanning a staff card,
 * all under the rules of M7d (one record per staff member and school-local day, never overwritten, a change is a correction).
 */
@IntegrationTest
class StaffCheckinApiTest extends QrTestSupport {

	private record StaffSetup(Setup s, Persona support, UUID supportStaff, UUID leadStaff) {

		String base() {
			return this.s.api() + "/staff-attendance";
		}

	}

	private StaffSetup staffSetup(String label) {
		Setup s = setup(label);
		setting(s.schoolId(), "attendance.staff.enabled", true);
		methods(s, ALL_METHODS.toArray(String[]::new));
		UUID support = this.personas.newStaff(s.schoolId(), label + "-support", "SUPPORT_STAFF");
		this.jdbc.update("update school_staff set started_on = null where school_id = ?", s.schoolId());
		atNoon();
		return new StaffSetup(s, person(label + "-support", support), staff(s, support), staff(s, s.leadId()));
	}

	private UUID staff(Setup s, UUID userId) {
		return this.jdbc.queryForObject("select id from school_staff where school_id = ? and user_id = ?", UUID.class, s.schoolId(), userId);
	}

	private UUID openStaff(StaffSetup t, java.util.Map<String, Object> body) throws Exception {
		return uuid(call(HttpMethod.POST, t.base() + "/qr-sessions", t.s().admin(), body, 201), "id");
	}

	@Test
	void staffCheckInWithTheCodeTheManagerShowsAndTheRecordIsTheSameAsAManualOne() throws Exception {
		StaffSetup t = staffSetup("Staff qr school");
		Setup s = t.s();
		JsonNode session = call(HttpMethod.POST, t.base() + "/qr-sessions", s.admin(), map(), 201);
		UUID id = uuid(session, "id");
		assertThat(session.get("subjectKind").asString()).isEqualTo("STAFF");
		assertThat(session.get("date").asString()).isEqualTo("2026-11-27");
		assertThat(session.has("classId")).isFalse();
		assertThat(get(qrBase(s) + "/" + id + "/codes", s.admin()).get("codes").get(0).get("code").asString()).isEqualTo(code(id, window(30)));

		JsonNode result = checkIn(s, id, s.lead(), currentCode(id), 200);
		assertThat(result.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(result.get("status").asString()).isEqualTo("PRESENT");
		checkIn(s, id, t.support(), currentCode(id), 200);
		assertThat(this.jdbc.queryForMap("select method, status, recorded_by, checkin_id is not null as linked, check_in_at is not null as timed from staff_attendance_records where staff_id = ?",
				t.leadStaff())).containsEntry("method", "QR_ROTATING").containsEntry("status", "PRESENT")
			.containsEntry("recorded_by", s.leadId()).containsEntry("linked", true).containsEntry("timed", true);
		// the manager's day shows both, and the live list names them
		JsonNode day = get(t.base() + "/days/2026-11-27", s.admin());
		int qr = 0;
		for (JsonNode entry : day.get("staff").get("items")) {
			if (entry.has("record") && "QR_ROTATING".equals(entry.get("record").get("method").asString())) {
				qr++;
			}
		}
		assertThat(qr).isEqualTo(2);
		assertThat(sessionDetail(s, id, s.admin()).get("entries")).hasSize(2);
		// a second scan is a duplicate; the ledger has everything
		assertThat(checkIn(s, id, s.lead(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED", "ACCEPTED", "REJECTED_DUPLICATE");
		// a wrong code is refused like for students
		this.jdbc.update("delete from staff_attendance_records where staff_id = ? ", t.supportStaff());
		assertThat(checkIn(s, id, t.support(), "AAAAAAAAAAAAAAAAAAAAAA", 409).get("result").asString()).isEqualTo("REJECTED_EXPIRED");
	}

	@Test
	void onlyManagersRunTheCodeAndOnlyStaffCheckInToIt() throws Exception {
		StaffSetup t = staffSetup("Staff qr access school");
		Setup s = t.s();
		UUID id = openStaff(t, map());
		// a teacher is staff and nothing more: no code, no list, no scan of a card
		for (Persona nobody : java.util.List.of(s.lead(), s.subject(), t.support())) {
			assertThat(errorCode(call(HttpMethod.POST, t.base() + "/qr-sessions", nobody, map(), 403))).contains("staff_attendance.access_denied");
			call(HttpMethod.GET, qrBase(s) + "/" + id, nobody, null, 403);
			call(HttpMethod.GET, qrBase(s) + "/" + id + "/codes", nobody, null, 403);
			call(HttpMethod.POST, qrBase(s) + "/" + id + "/close", nobody, null, 403);
			call(HttpMethod.POST, t.base() + "/scans", nobody, map("token", "AAAAAAAAAAAAAAAAAAAAAA"), 403);
		}
		call(HttpMethod.POST, t.base() + "/qr-sessions", s.guardian1(), map(), 404);
		// students and guardians do not check in to a staff code
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.student1(), map("code", currentCode(id)), 403)))
			.contains("attendance.checkin_not_allowed");
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.guardian1(), map("code", currentCode(id)), 404);
		// another school's staff cannot find the session
		Setup other = setup("Staff qr other");
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", other.lead(), map("code", currentCode(id)), 404);
		call(HttpMethod.POST, qrBase(other) + "/" + id + "/check-ins", other.lead(), map("code", currentCode(id)), 404);
		assertThat(ledgerResults(s.schoolId())).isEmpty();
	}

	@Test
	void theSwitchesOfStaffAttendanceAndTheMethodApply() throws Exception {
		Setup s = setup("Staff qr switch school");
		atNoon();
		String base = s.api() + "/staff-attendance";
		// the school setting of M7d comes first
		assertThat(errorCode(call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map(), 403))).contains("staff_attendance.disabled");
		setting(s.schoolId(), "attendance.staff.enabled", true);
		assertThat(errorCode(call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map(), 403))).contains("attendance.method_disabled");
		methods(s, "MANUAL", "QR_ROTATING");
		UUID id = uuid(call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map(), 201), "id");
		call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map("durationMinutes", 481), 400);
		call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map("campusId", UUID.randomUUID()), 404);
		// the card needs the other method
		assertThat(errorCode(call(HttpMethod.POST, base + "/scans", s.admin(), map("token", "AAAAAAAAAAAAAAAAAAAAAA"), 403))).contains("attendance.method_disabled");
		// switching staff attendance off refuses the check-in
		setting(s.schoolId(), "attendance.staff.enabled", false);
		assertThat(errorCode(call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", s.lead(), map("code", currentCode(id)), 403)))
			.contains("staff_attendance.disabled");
		setting(s.schoolId(), "attendance.staff.enabled", true);
		// a closed code and a code past its time
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/close", s.admin(), null, 200);
		assertThat(checkIn(s, id, s.lead(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		UUID second = uuid(call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map("durationMinutes", 5), 201), "id");
		moveClockTo("2026-11-27T09:10:05Z");
		assertThat(checkIn(s, second, s.lead(), currentCode(second), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		this.jdbc.update("update schools set status = 'ONBOARDING' where id = ?", s.schoolId());
		assertThat(errorCode(call(HttpMethod.POST, base + "/qr-sessions", s.admin(), map(), 403))).contains("attendance.school_not_active");
		this.jdbc.update("update schools set status = 'ACTIVE' where id = ?", s.schoolId());
	}

	@Test
	void aCheckInAfterTheThresholdIsLateAndTheManagerCanOverrideTheSchoolsDefault() throws Exception {
		StaffSetup t = staffSetup("Staff qr late school");
		Setup s = t.s();
		setting(s.schoolId(), "attendance.staff.late_after_minutes", 10);
		UUID id = openStaff(t, map());
		assertThat(sessionDetail(s, id, s.admin()).get("session").get("lateAfterMinutes").asInt()).isEqualTo(10);
		moveClockTo("2026-11-27T09:05:35Z");
		assertThat(checkIn(s, id, s.lead(), currentCode(id), 200).get("status").asString()).isEqualTo("PRESENT");
		moveClockTo("2026-11-27T09:11:35Z");
		JsonNode late = checkIn(s, id, t.support(), currentCode(id), 200);
		assertThat(late.get("status").asString()).isEqualTo("LATE");
		assertThat(late.get("minutesLate").asInt()).isEqualTo(11);
		assertThat(this.jdbc.queryForMap("select status, minutes_late from staff_attendance_records where staff_id = ?", t.supportStaff()))
			.containsEntry("status", "LATE").containsEntry("minutes_late", 11);
		// the manager's own threshold wins
		atNoon();
		UUID relaxed = openStaff(t, map("lateAfterMinutes", 60));
		assertThat(sessionDetail(s, relaxed, s.admin()).get("session").get("lateAfterMinutes").asInt()).isEqualTo(60);
	}

	@Test
	void aCheckInNeverOverwritesAManualRecordAndTheDayIsTheStaffMembersOwn() throws Exception {
		StaffSetup t = staffSetup("Staff qr conflict school");
		Setup s = t.s();
		call(HttpMethod.PUT, t.base() + "/days/2026-11-27", s.admin(), map("records", java.util.List.of(
				map("staffId", t.leadStaff(), "status", "ABSENT"), map("staffId", t.supportStaff(), "status", "PRESENT"))), 200);
		UUID id = openStaff(t, map());
		assertThat(checkIn(s, id, s.lead(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_CONFLICT");
		assertThat(checkIn(s, id, t.support(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(this.jdbc.queryForObject("select status from staff_attendance_records where staff_id = ?", String.class, t.leadStaff())).isEqualTo("ABSENT");
		assertThat(count("select count(*) from staff_attendance_records where school_id = ? and checkin_id is not null", s.schoolId())).isZero();
		// a staff member who left is no member of the school any more: it does not exist for them
		this.jdbc.update("update school_staff set status = 'FORMER' where id = ?", t.supportStaff());
		this.jdbc.update("delete from staff_attendance_records where staff_id = ?", t.supportStaff());
		call(HttpMethod.POST, qrBase(s) + "/" + id + "/check-ins", t.support(), map("code", currentCode(id)), 404);
	}

	@Test
	void theDayIsTheCampusLocalDayAndACampusCodeIsForThatCampusOnly() throws Exception {
		StaffSetup t = staffSetup("Staff qr campus school");
		Setup s = t.s();
		UUID west = newId();
		UUID east = newId();
		this.jdbc.update("insert into campuses (id, school_id, name, timezone) values (?, ?, 'West', 'America/Los_Angeles')", west, s.schoolId());
		this.jdbc.update("insert into campuses (id, school_id, name) values (?, ?, 'East')", east, s.schoolId());
		this.jdbc.update("update school_staff set campus_id = ? where id = ?", west, t.supportStaff());
		this.jdbc.update("update school_staff set campus_id = ? where id = ?", east, t.leadStaff());
		// 05:00 UTC is 21:00 of the previous day in Los Angeles
		moveClockTo("2026-11-27T05:00:05Z");
		UUID westCode = openStaff(t, map("campusId", west));
		JsonNode view = sessionDetail(s, westCode, s.admin()).get("session");
		assertThat(view.get("date").asString()).isEqualTo("2026-11-26");
		assertThat(view.get("timeZone").asString()).isEqualTo("America/Los_Angeles");
		assertThat(checkIn(s, westCode, t.support(), currentCode(westCode), 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(this.jdbc.queryForObject("select attendance_date from staff_attendance_records where staff_id = ?", java.sql.Date.class, t.supportStaff()).toString())
			.isEqualTo("2026-11-26");
		// the other campus's staff, and staff without a campus, are not for this code
		assertThat(checkIn(s, westCode, s.lead(), currentCode(westCode), 409).get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
		this.jdbc.update("update school_staff set campus_id = null where id = ?", t.leadStaff());
		assertThat(checkIn(s, westCode, s.lead(), currentCode(westCode), 409).get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
		// a whole-school code takes everybody, on the day of each one's own campus: Addis Ababa is already on the 27th
		UUID everyone = openStaff(t, map());
		assertThat(checkIn(s, everyone, s.lead(), currentCode(everyone), 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(this.jdbc.queryForObject("select attendance_date from staff_attendance_records where staff_id = ?", java.sql.Date.class, t.leadStaff()).toString())
			.isEqualTo("2026-11-27");
		// the support member's campus is on the 26th, the whole-school code says the 27th: not their day yet
		this.jdbc.update("delete from staff_attendance_records where staff_id = ?", t.supportStaff());
		assertThat(checkIn(s, everyone, t.support(), currentCode(everyone), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
	}

	@Test
	void aManagerScansAStaffCardForTodayAndARevokedOneIsRefused() throws Exception {
		StaffSetup t = staffSetup("Staff card school");
		Setup s = t.s();
		String url = t.base() + "/scans";
		String card = call(HttpMethod.POST, s.api() + "/staff/" + t.leadStaff() + "/attendance-credentials", s.admin(), null, 201).get("qrPayload").asString();
		JsonNode ok = call(HttpMethod.POST, url, s.admin(), map("token", card), 200);
		assertThat(ok.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(ok.get("person").get("firstName").asString()).isEqualTo(this.jdbc.queryForObject("select given_name from users where id = ?", String.class, s.leadId()));
		assertThat(this.jdbc.queryForMap("select method, status, recorded_by from staff_attendance_records where staff_id = ?", t.leadStaff()))
			.containsEntry("method", "QR_STATIC").containsEntry("status", "PRESENT").containsEntry("recorded_by", s.admin().userId());
		assertThat(call(HttpMethod.POST, url, s.admin(), map("token", card), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		// a student's card is not a staff card, a revoked card is refused and logged with the scanner
		assertThat(call(HttpMethod.POST, url, s.admin(), map("token", issue(s, s.s1())), 409).get("result").asString()).isEqualTo("REJECTED_INVALID");
		call(HttpMethod.POST, s.api() + "/staff/" + t.leadStaff() + "/attendance-credentials/revoke", s.admin(), map("reason", "LOST"), 200);
		this.jdbc.update("delete from staff_attendance_records where staff_id = ?", t.leadStaff());
		JsonNode revoked = call(HttpMethod.POST, url, s.admin(), map("token", card), 409);
		assertThat(revoked.get("result").asString()).isEqualTo("REJECTED_REVOKED");
		assertThat(this.jdbc.queryForObject("select scanner_user_id from attendance_checkins where result = 'REJECTED_REVOKED' and school_id = ?", UUID.class,
				s.schoolId())).isEqualTo(s.admin().userId());
		assertThat(count("select count(*) from staff_attendance_records where staff_id = ?", t.leadStaff())).isZero();
	}

	@Test
	void aManagerTakesAnEntryOffTheDayWhileNobodyHasCorrectedIt() throws Exception {
		StaffSetup t = staffSetup("Staff qr remove school");
		Setup s = t.s();
		UUID id = openStaff(t, map());
		UUID first = uuid(checkIn(s, id, s.lead(), currentCode(id), 200), "checkinId");
		UUID second = uuid(checkIn(s, id, t.support(), currentCode(id), 200), "checkinId");
		call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + first, s.admin(), null, 200);
		assertThat(count("select count(*) from staff_attendance_records where staff_id = ?", t.leadStaff())).isZero();
		UUID record = this.jdbc.queryForObject("select id from staff_attendance_records where staff_id = ?", UUID.class, t.supportStaff());
		call(HttpMethod.POST, t.base() + "/records/" + record + "/corrections", s.admin(), map("status", "ABSENT", "reason", "left early", "expectedVersion", 0), 201);
		assertThat(errorCode(call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + second, s.admin(), null, 409))).contains("attendance.checkin_changed");
		call(HttpMethod.DELETE, qrBase(s) + "/" + id + "/check-ins/" + first, s.lead(), null, 403);
		assertThat(checkIn(s, id, s.lead(), currentCode(id), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
	}

}
