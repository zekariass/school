package com.nemaz.school.attendance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static QR (M7e): a teacher scans a student's card. What the scanner is told, what is refused and logged, the roll's own
 * rules, the offline-safe client operation id, SUSPECTED_COPY, and the rate limit.
 */
@IntegrationTest
class StaticScanApiTest extends QrTestSupport {

	private Setup scanSetup(String label) {
		Setup s = setup(label);
		methods(s, ALL_METHODS.toArray(String[]::new));
		atNoon();
		return s;
	}

	@Test
	void aTeacherScansACardAndLearnsOnlyTheFirstNameAndAvatar() throws Exception {
		Setup s = scanSetup("Scan happy school");
		this.jdbc.update("update users set family_name = 'Secretfamily', date_of_birth = date '2014-03-04' where id = ?", s.s1());
		String token = issue(s, s.s1());
		MockHttpServletResponse response = perform(HttpMethod.POST, s.rollUrl() + "/scans", s.lead(), map("token", token)).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		JsonNode result = this.json.readTree(response.getContentAsString());
		assertThat(result.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(result.get("status").asString()).isEqualTo("PRESENT");
		JsonNode person = result.get("person");
		assertThat(person.get("firstName").asString()).isEqualTo("Abel");
		assertThat(uuid(person, "personId")).isEqualTo(s.s1());
		Set<String> names = new HashSet<>();
		person.propertyNames().forEach(names::add);
		assertThat(names).isSubsetOf("personId", "firstName", "avatar", "avatarConfig");
		assertThat(response.getContentAsString()).doesNotContain(token).doesNotContain("Secretfamily").doesNotContain("2014").doesNotContain("dateOfBirth");

		assertThat(this.jdbc.queryForMap("select source, recorded_by, status from attendance_records where student_id = ?", s.s1()))
			.containsEntry("source", "QR_STATIC")
			.containsEntry("recorded_by", s.leadId())
			.containsEntry("status", "PRESENT");
		assertThat(this.jdbc.queryForObject("select taken_by from attendance_sessions where class_id = ?", UUID.class, s.classId())).isEqualTo(s.leadId());
		assertThat(this.jdbc.queryForMap("select method, scanner_user_id, result from attendance_checkins where student_id = ?", s.s1()))
			.containsEntry("method", "QR_STATIC")
			.containsEntry("scanner_user_id", s.leadId())
			.containsEntry("result", "ACCEPTED");
		assertThat(this.jdbc.queryForObject("select last_used_at from attendance_credentials where student_id = ?", Object.class, s.s1())).isNotNull();
	}

	@Test
	void aRevokedCardIsRefusedAndLoggedWithTheScannerAndAReissuedOneWorks() throws Exception {
		Setup s = scanSetup("Scan revoked school");
		String lost = issue(s, s.s1());
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials/revoke", s.admin(), map("reason", "LOST"), 200);
		JsonNode refused = scan(s, s.subject(), lost, 409);
		assertThat(refused.get("result").asString()).isEqualTo("REJECTED_REVOKED");
		assertThat(refused.get("code").asString()).isEqualTo("attendance.credential_revoked");
		assertThat(refused.get("detail").asString()).isNotBlank();
		assertThat(refused.get("person").get("firstName").asString()).isEqualTo("Abel");
		assertThat(recordStatus(s.classId(), s.s1())).isNull();
		assertThat(this.jdbc.queryForMap("select result, scanner_user_id, credential_id is not null as has_credential from attendance_checkins where student_id = ?",
				s.s1())).containsEntry("result", "REJECTED_REVOKED").containsEntry("scanner_user_id", s.subjectId()).containsEntry("has_credential", true);
		assertThat(this.jdbc.queryForObject("select cast(metadata as text) from audit_logs where action = 'attendance.scan.refused' and school_id = ?",
				String.class, s.schoolId())).contains("REVOKED").doesNotContain(lost);

		String replacement = issue(s, s.s1());
		assertThat(scan(s, s.subject(), replacement, 200).get("result").asString()).isEqualTo("ACCEPTED");
		// the lost card stays dead
		assertThat(scan(s, s.subject(), lost, 409).get("result").asString()).isEqualTo("REJECTED_REVOKED");
	}

	@Test
	void anExpiredCardIsLoggedButAnsweredLikeAnUnknownOne() throws Exception {
		Setup s = scanSetup("Scan expired school");
		String token = call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials", s.admin(), map("ttlDays", 1), 201)
			.get("qrPayload").asString();
		assertThat(scan(s, s.lead(), token, 200).get("result").asString()).isEqualTo("ACCEPTED");
		moveClockTo("2026-11-29T09:00:05Z");
		JsonNode refused = scan(s, s.lead(), map("token", token, "date", "2026-11-29"), 409);
		assertThat(refused.get("result").asString()).isEqualTo("REJECTED_INVALID");
		assertThat(refused.has("person")).isFalse();
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED", "REJECTED_EXPIRED");
		assertThat(this.jdbc.queryForObject("select cast(metadata as text) from audit_logs where action = 'attendance.scan.refused' and school_id = ?",
				String.class, s.schoolId())).contains("EXPIRED");
	}

	@Test
	void unknownExpiredOtherSchoolAndStaffCardsAreAllAnsweredTheSameAndRevealNothing() throws Exception {
		Setup s = scanSetup("Scan generic school");
		Setup other = setup("Scan generic other");
		methods(other, ALL_METHODS.toArray(String[]::new));
		String expired = call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials", s.admin(), map("ttlDays", 1), 201)
			.get("qrPayload").asString();
		String otherSchool = issue(other, other.s1());
		UUID staffId = this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, s.subjectId());
		String staffCard = call(HttpMethod.POST, s.api() + "/staff/" + staffId + "/attendance-credentials", s.admin(), null, 201).get("qrPayload").asString();
		moveClockTo("2026-11-29T09:00:05Z");
		List<JsonNode> answers = new ArrayList<>();
		for (String token : List.of("AAAAAAAAAAAAAAAAAAAAAA", "not-a-token", "xxxxxxxxxxxx", expired, otherSchool, staffCard)) {
			answers.add(scan(s, s.lead(), token, 409));
		}
		for (JsonNode answer : answers) {
			assertThat(answer.get("result").asString()).isEqualTo("REJECTED_INVALID");
			assertThat(answer.get("code").asString()).isEqualTo("attendance.credential_invalid");
			assertThat(answer.has("person")).isFalse();
			assertThat(answer.has("checkinId")).isFalse();
			assertThat(answer).isEqualTo(answers.getFirst());
		}
		// only the expired card names a person, so only it is in the ledger; nothing at all is written in the other school
		assertThat(ledgerResults(s.schoolId())).containsExactly("REJECTED_EXPIRED");
		assertThat(ledgerResults(other.schoolId())).isEmpty();
		assertThat(count("select count(*) from attendance_checkins where credential_id in (select id from attendance_credentials where school_id = ?)",
				other.schoolId())).isZero();
		// every refusal is audited as a denied scan by the scanner, none with a token
		assertThat(count("select count(*) from audit_logs where action = 'attendance.scan.refused' and outcome = 'DENIED' and school_id = ?", s.schoolId()))
			.isEqualTo(6);
		for (String token : List.of(expired, otherSchool, staffCard)) {
			assertThat(count("select count(*) from audit_logs where cast(metadata as text) like ?", "%" + token + "%")).isZero();
		}
		assertThat(recordStatus(other.classId(), other.s1())).isNull();
	}

	@Test
	void aCardOfAStudentWhoIsNotInTheClassNamesTheStudentButNotTheirId() throws Exception {
		Setup s = scanSetup("Scan class school");
		UUID outsider = this.personas.newStudent(s.schoolId(), "Fikru");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), outsider);
		String token = issue(s, outsider);
		JsonNode refused = scan(s, s.lead(), token, 409);
		assertThat(refused.get("result").asString()).isEqualTo("REJECTED_NOT_ENROLLED");
		assertThat(refused.get("person").get("firstName").asString()).isEqualTo("Fikru");
		assertThat(refused.get("person").has("personId")).isFalse();
		assertThat(refused.get("code").asString()).isEqualTo("attendance.student_not_in_class");
		assertThat(recordStatus(s.classId(), outsider)).isNull();
		assertThat(ledgerResults(s.schoolId())).containsExactly("REJECTED_NOT_ENROLLED");
	}

	@Test
	void aScanNeverOverwritesADifferentMarkAndAnIdenticalOneIsADuplicate() throws Exception {
		Setup s = scanSetup("Scan conflict school");
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT", null, "note", null), mark(s.s2(), "PRESENT")), 200);
		JsonNode conflict = scan(s, s.lead(), issue(s, s.s1()), 409);
		assertThat(conflict.get("result").asString()).isEqualTo("REJECTED_CONFLICT");
		assertThat(conflict.get("current").get("status").asString()).isEqualTo("ABSENT");
		assertThat(recordStatus(s.classId(), s.s1())).isEqualTo("ABSENT");
		assertThat(scan(s, s.lead(), issue(s, s.s2()), 409).get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(count("select count(*) from attendance_records where class_id = ? and checkin_id is not null", s.classId())).isZero();
		// a scan that sets LATE over a stored PRESENT is a different mark: a conflict, nothing overwritten
		JsonNode late = scan(s, s.lead(), map("token", issue(s, s.s3()), "status", "LATE", "minutesLate", 8), 200);
		assertThat(late.get("status").asString()).isEqualTo("LATE");
		assertThat(this.jdbc.queryForMap("select status, minutes_late from attendance_records where student_id = ?", s.s3()))
			.containsEntry("status", "LATE").containsEntry("minutes_late", 8);
	}

	@Test
	void aScanFollowsTheRollsDatesAndLockWindowWithoutAnOverride() throws Exception {
		Setup s = scanSetup("Scan window school");
		setting(s.schoolId(), "attendance.lock_after_hours", 1);
		String token = issue(s, s.s1());
		// yesterday locked an hour into today (01:00 local); it is noon, so not even the lead teacher may add a mark: no override exists
		JsonNode locked = scan(s, s.lead(), map("token", token, "date", "2026-11-26"), 409);
		assertThat(locked.get("result").asString()).isEqualTo("REJECTED_WINDOW");
		assertThat(locked.get("code").asString()).isEqualTo("attendance.checkin_window_closed");
		assertThat(scan(s, s.admin(), map("token", token, "date", "2026-11-26"), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		// a day that has not started (beyond the one hour of tolerance)
		assertThat(scan(s, s.lead(), map("token", token, "date", "2026-11-29"), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		assertThat(countRecords(s.classId())).isZero();
		// with the default lock of 24 hours yesterday is still open at noon, three days ago is not
		setting(s.schoolId(), "attendance.lock_after_hours", 24);
		assertThat(scan(s, s.lead(), map("token", token, "date", "2026-11-26"), 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(scan(s, s.lead(), map("token", issue(s, s.s2()), "date", "2026-11-24"), 409).get("result").asString()).isEqualTo("REJECTED_WINDOW");
		// the school's day, not UTC's: at 21:30 UTC it is already 00:30 of the next day in Addis Ababa
		moveClockTo("2026-11-27T21:30:05Z");
		assertThat(scan(s, s.lead(), map("token", issue(s, s.s3())), 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(this.jdbc.queryForObject("select attendance_date from attendance_records where student_id = ?", java.sql.Date.class, s.s3()).toString())
			.isEqualTo("2026-11-28");
		// a slot the school does not use is a bad request
		call(HttpMethod.POST, s.rollUrl() + "/scans", s.lead(), map("token", token, "slot", "P2"), 400);
	}

	@Test
	void aScanOnlyMarksPresentOrLate() throws Exception {
		Setup s = scanSetup("Scan status school");
		String token = issue(s, s.s1());
		for (String status : List.of("ABSENT", "EXCUSED", "LEFT_EARLY")) {
			assertThat(errorCode(scan(s, s.lead(), map("token", token, "status", status), 400))).contains("attendance.checkin_status_invalid");
		}
		call(HttpMethod.POST, s.rollUrl() + "/scans", s.lead(), map("token", token, "status", "NOPE"), 400);
		assertThat(errorCode(scan(s, s.lead(), map("token", token, "status", "PRESENT", "minutesLate", 5), 400))).contains("attendance.invalid_minutes_late");
		call(HttpMethod.POST, s.rollUrl() + "/scans", s.lead(), map(), 400);
		assertThat(countRecords(s.classId())).isZero();
		assertThat(ledgerResults(s.schoolId())).isEmpty();
	}

	@Test
	void onlyThePeopleWhoTakeTheRollScanAndOnlyWhenTheMethodIsOn() throws Exception {
		Setup s = scanSetup("Scan access school");
		Setup other = setup("Scan access other");
		String t1 = issue(s, s.s1());
		String t2 = issue(s, s.s2());
		String t3 = issue(s, s.s3());
		assertThat(scan(s, s.subject(), t1, 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(scan(s, s.assistant(), t2, 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(scan(s, s.admin(), t3, 200).get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(errorCode(scan(s, s.student1(), t1, 403))).contains("class.access_denied");
		scan(s, s.guardian1(), t1, 403);
		scan(s, s.otherTeacher(), t1, 404);
		scan(s, other.admin(), t1, 404);
		call(HttpMethod.POST, s.rollUrl() + "/scans", null, map("token", t1), 401);
		// the method switched off
		methods(s, "MANUAL", "QR_ROTATING");
		assertThat(errorCode(scan(s, s.lead(), t1, 403))).contains("attendance.method_disabled");
		methods(s, ALL_METHODS.toArray(String[]::new));
		// a school that is not active
		this.jdbc.update("update schools set status = 'ONBOARDING' where id = ?", s.schoolId());
		assertThat(errorCode(scan(s, s.lead(), t1, 403))).contains("attendance.school_not_active");
		this.jdbc.update("update schools set status = 'ACTIVE' where id = ?", s.schoolId());
	}

	@Test
	void aRetryOfTheSameClientOperationAnswersTheFirstOutcomeAndWritesNothingMore() throws Exception {
		Setup s = scanSetup("Scan op school");
		String token = issue(s, s.s1());
		UUID op = newId();
		JsonNode first = scan(s, s.lead(), map("token", token, "clientOpId", op), 200);
		JsonNode retry = scan(s, s.lead(), map("token", token, "clientOpId", op), 200);
		assertThat(retry.get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(retry.get("checkinId")).isEqualTo(first.get("checkinId"));
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED");
		assertThat(countRecords(s.classId())).isEqualTo(1);
		// somebody else cannot use the operation id
		assertThat(errorCode(scan(s, s.subject(), map("token", token, "clientOpId", op), 409))).contains("attendance.client_op_conflict");
	}

	@Test
	void twoScannersReadingOneCardWithinAMinuteRaiseTheSuspectedCopyAlert() throws Exception {
		Setup s = scanSetup("Scan copy school");
		String t1 = issue(s, s.s1());
		String t2 = issue(s, s.s2());
		// one scanner scanning twice is nothing
		scan(s, s.lead(), t2, 200);
		scan(s, s.lead(), t2, 409);
		assertThat(count("select count(*) from attendance_credentials where student_id = ? and suspected_copy_at is not null", s.s2())).isZero();
		// two scanners within a minute are: the scan is still processed (here a duplicate), the credential is flagged and audited
		scan(s, s.lead(), t1, 200);
		JsonNode second = scan(s, s.subject(), t1, 409);
		assertThat(second.get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		assertThat(count("select count(*) from attendance_credentials where student_id = ? and suspected_copy_at is not null", s.s1())).isEqualTo(1);
		assertThat(count("select count(*) from audit_logs where action = 'attendance.credential.suspected_copy' and school_id = ?", s.schoolId())).isEqualTo(1);
		JsonNode alerts = get(s.api() + "/attendance/credentials?suspectedCopy=true", s.admin());
		assertThat(alerts.get("items")).hasSize(1);
		assertThat(uuid(alerts.get("items").get(0), "studentId")).isEqualTo(s.s1());
		assertThat(alerts.get("items").get(0).get("state").asString()).isEqualTo("LIVE");
		assertThat(alerts.get("items").get(0).get("suspectedCopyAt").asString()).isNotBlank();
		// the school decides: revoking it with the reason takes it off the alert list
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials/revoke", s.admin(), map("reason", "SUSPECTED_COPY"), 200);
		assertThat(get(s.api() + "/attendance/credentials?suspectedCopy=true", s.admin()).get("items")).isEmpty();
		// two minutes later another scanner reading the card (t2) is not within a minute of the first
		moveClockTo("2026-11-27T09:02:35Z");
		scan(s, s.subject(), t2, 409);
		assertThat(count("select count(*) from attendance_credentials where student_id = ? and suspected_copy_at is not null", s.s2())).isZero();
	}

	@Test
	void everyScannerIsRateLimited() throws Exception {
		Setup s = scanSetup("Scan limit school");
		Persona scanner = this.personas.withRole("scan-limit-admin", "SCHOOL_ADMIN", com.nemaz.school.common.security.AccessScope.school(s.schoolId()));
		for (int i = 0; i < 120; i++) {
			scan(s, scanner, "AAAAAAAAAAAAAAAAAAAAAA", 409);
		}
		assertThat(errorCode(call(HttpMethod.POST, s.rollUrl() + "/scans", scanner, map("token", "AAAAAAAAAAAAAAAAAAAAAA"), 429)))
			.contains("attendance.scan_rate_limited");
		// another scanner is not affected
		scan(s, s.lead(), "AAAAAAAAAAAAAAAAAAAAAA", 409);
	}

}
