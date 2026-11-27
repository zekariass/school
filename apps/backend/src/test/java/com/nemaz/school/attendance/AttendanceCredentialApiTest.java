package com.nemaz.school.attendance;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The attendance credentials (M7e): the QR of a card. Issued once, stored only as a keyed hash, one live per person, revocable,
 * reissued, expiring, never the login code, and visible only to the people who run cards.
 */
@IntegrationTest
class AttendanceCredentialApiTest extends QrTestSupport {

	private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22}$");

	private String studentCredentials(Setup s, UUID student) {
		return s.api() + "/students/" + student + "/attendance-credentials";
	}

	private JsonNode issue(Setup s, Persona as, UUID student, int status) throws Exception {
		return call(HttpMethod.POST, studentCredentials(s, student), as, null, status);
	}

	@Test
	void theTokenIsShownOnceStoredOnlyAsAKeyedHashAndNeverReadAgain() throws Exception {
		Setup s = setup("Cred once school");
		MockHttpServletResponse response = perform(HttpMethod.POST, studentCredentials(s, s.s1()), s.admin(), null).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		JsonNode issued = this.json.readTree(response.getContentAsString());
		String token = issued.get("qrPayload").asString();
		assertThat(token).matches(TOKEN);
		JsonNode view = issued.get("credential");
		assertThat(view.get("state").asString()).isEqualTo("LIVE");
		assertThat(view.get("subjectKind").asString()).isEqualTo("STUDENT");
		assertThat(uuid(view, "studentId")).isEqualTo(s.s1());
		assertThat(view.has("hint")).isFalse();
		assertThat(Duration.between(java.time.Instant.parse(view.get("issuedAt").asString()), java.time.Instant.parse(view.get("expiresAt").asString())))
			.isEqualTo(Duration.ofDays(365));

		// the database holds the HMAC under the active pepper key, and the token nowhere
		Map<String, Object> row = this.jdbc.queryForMap("select token_hash, key_id from attendance_credentials where id = ?", uuid(view, "id"));
		assertThat(row.get("key_id")).isEqualTo("t1");
		assertThat((String) row.get("token_hash")).hasSize(64).isEqualTo(tokenHash(PEPPER_T1, token)).doesNotContain(token);
		assertThat(this.jdbc.queryForObject("select cast(t as text) from attendance_credentials t where id = ?", String.class, uuid(view, "id")))
			.doesNotContain(token);
		// it is another secret than the same token under the other pepper key: the pepper matters
		assertThat(tokenHash(PEPPER_T0, token)).isNotEqualTo(tokenHash(PEPPER_T1, token));

		// no read returns it again; the audit row names the credential and never the token
		String listed = get(studentCredentials(s, s.s1()), s.admin()).toString();
		assertThat(listed).doesNotContain(token).doesNotContain("qrPayload");
		assertThat(get(s.api() + "/attendance/credentials", s.admin()).toString()).doesNotContain(token);
		assertThat(this.jdbc.queryForObject("select cast(metadata as text) from audit_logs where action = 'attendance.credential.issue' and school_id = ?",
				String.class, s.schoolId())).doesNotContain(token).contains("STUDENT");
		// a second issue while one is live is refused
		assertThat(errorCode(issue(s, s.admin(), s.s1(), 409))).contains("attendance.credential_exists");
	}

	@Test
	void aCredentialIsNotALoginCode() throws Exception {
		Setup s = setup("Cred login school");
		String token = this.issue(s, s.s1());
		MockHttpServletResponse response = this.mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login-code")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(this.json.writeValueAsString(map("code", token))))
			.andReturn()
			.getResponse();
		assertThat(response.getStatus()).isBetween(400, 401);
		assertThat(response.getContentAsString()).doesNotContain("accessToken");
		// and a login code never records attendance
		assertThat(this.jdbc.queryForObject("select count(*) from student_login_codes where code_hash = ?", Integer.class, tokenHash(PEPPER_T1, token))).isZero();
	}

	@Test
	void onlyTheCredentialManagersAndTheLeadOfTheStudentsClassIssueAndRevoke() throws Exception {
		Setup s = setup("Cred access school");
		Setup other = setup("Cred access other");
		UUID s2 = s.s2();
		// refused before anything is said about the student: a made-up id gets the same 403
		for (Persona nobody : List.of(s.subject(), s.assistant(), s.otherTeacher(), s.student1())) {
			assertThat(errorCode(issue(s, nobody, s2, 403))).contains("attendance.access_denied");
			assertThat(errorCode(issue(s, nobody, UUID.randomUUID(), 403))).contains("attendance.access_denied");
			call(HttpMethod.GET, studentCredentials(s, s2), nobody, null, 403);
			call(HttpMethod.POST, studentCredentials(s, s2) + "/revoke", nobody, map("reason", "LOST"), 403);
		}
		// guardians are no members of the school; another school's administrator finds nothing
		issue(s, s.guardian1(), s2, 404);
		issue(s, other.admin(), s2, 404);
		// a manager of the other school asking about this school's student in their own school: it is not a student there
		assertThat(errorCode(issue(other, other.admin(), s2, 404))).contains("student.not_found");
		assertThat(count("select count(*) from attendance_credentials where school_id = ?", s.schoolId())).isZero();
		// the LEAD teacher of the class, a school administrator and the platform administrator issue
		issue(s, s.lead(), s2, 201);
		issue(s, s.admin(), s.s3(), 201);
		issue(s, this.personas.platformAdmin(), s.s1(), 201);
		// an unknown student is 404 to a manager, a student who is not ACTIVE in the school 409
		assertThat(errorCode(issue(s, s.admin(), UUID.randomUUID(), 404))).contains("student.not_found");
		UUID gone = this.personas.newStudent(s.schoolId(), "Gizaw");
		this.jdbc.update("update school_students set status = 'WITHDRAWN' where student_id = ?", gone);
		assertThat(errorCode(issue(s, s.admin(), gone, 409))).contains("attendance.credential_person_not_active");
		// the lead of ANOTHER class cannot issue for this class's student
		issue(s, s.otherTeacher(), s.s1(), 403);
	}

	@Test
	void revokeReissueAndOneLiveCredentialPerStudent() throws Exception {
		Setup s = setup("Cred revoke school");
		String first = issue(s, s.s1());
		JsonNode revoked = call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/revoke", s.admin(), map("reason", "LOST"), 200);
		assertThat(revoked.get("state").asString()).isEqualTo("REVOKED");
		assertThat(revoked.get("revokedReason").asString()).isEqualTo("LOST");
		assertThat(errorCode(call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/revoke", s.admin(), map("reason", "LOST"), 404)))
			.contains("attendance.credential_not_found");
		call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/revoke", s.admin(), map(), 400);
		// a new one can be issued after a revocation; the old one stays revoked
		String second = issue(s, s.s1());
		assertThat(second).isNotEqualTo(first);
		// reissue replaces the live one in one step
		JsonNode reissued = call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/reissue", s.admin(), map("reason", "SUSPECTED_COPY"), 201);
		assertThat(reissued.get("qrPayload").asString()).matches(TOKEN).isNotIn(first, second);
		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"select revoked_at is null as live, revoked_reason from attendance_credentials where student_id = ? order by issued_at, id", s.s1());
		assertThat(rows).hasSize(3);
		assertThat(rows.stream().filter((r) -> (Boolean) r.get("live")).count()).isEqualTo(1);
		assertThat(rows.get(1).get("revoked_reason")).isEqualTo("SUSPECTED_COPY");
		// the database itself refuses a second live credential
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into attendance_credentials (id, school_id, student_id, token_hash, key_id, issued_by, issued_at, expires_at)
				values (?, ?, ?, ?, 't1', ?, now(), now() + interval '1 day')""", newId(), s.schoolId(), s.s1(), "x" + newId(), s.admin().userId()))
			.isInstanceOf(DataAccessException.class);
		// the default reason is REPLACED, LEFT_SCHOOL is not a reason to reissue, and a reissue with nothing live just issues
		call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/reissue", s.admin(), null, 201);
		assertThat(this.jdbc.queryForObject("select revoked_reason from attendance_credentials where student_id = ? order by revoked_at desc nulls last limit 1",
				String.class, s.s1())).isEqualTo("REPLACED");
		call(HttpMethod.POST, studentCredentials(s, s.s1()) + "/reissue", s.admin(), map("reason", "LEFT_SCHOOL"), 400);
		call(HttpMethod.POST, studentCredentials(s, s.s2()) + "/reissue", s.admin(), null, 201);
		// every revoke and issue is an audit row, none with a token
		assertThat(count("select count(*) from audit_logs where action = 'attendance.credential.revoke' and school_id = ?", s.schoolId())).isEqualTo(3);
	}

	@Test
	void aCredentialExpiresAndTheTtlIsBounded() throws Exception {
		Setup s = setup("Cred expiry school");
		atNoon();
		JsonNode issued = call(HttpMethod.POST, studentCredentials(s, s.s1()), s.admin(), map("ttlDays", 1), 201);
		assertThat(issued.get("credential").get("state").asString()).isEqualTo("LIVE");
		moveClockTo("2026-11-29T09:00:05Z");
		JsonNode listed = get(studentCredentials(s, s.s1()), s.admin());
		assertThat(listed.get(0).get("state").asString()).isEqualTo("EXPIRED");
		assertThat(get(s.api() + "/attendance/credentials?state=EXPIRED", s.admin()).get("items")).hasSize(1);
		assertThat(get(s.api() + "/attendance/credentials?state=LIVE", s.admin()).get("items")).isEmpty();
		// it still occupies the slot until it is revoked or reissued
		assertThat(errorCode(call(HttpMethod.POST, studentCredentials(s, s.s1()), s.admin(), null, 409))).contains("attendance.credential_exists");
		call(HttpMethod.POST, studentCredentials(s, s.s2()), s.admin(), map("ttlDays", 0), 400);
		call(HttpMethod.POST, studentCredentials(s, s.s2()), s.admin(), map("ttlDays", 1096), 400);
		call(HttpMethod.POST, studentCredentials(s, s.s2()), s.admin(), map("ttlDays", 1095), 201);
	}

	@Test
	void aClassSheetIssuesEveryCardOnceAndSkipsStudentsWhoAlreadyHaveOne() throws Exception {
		Setup s = setup("Cred sheet school");
		String url = s.api() + "/classes/" + s.classId() + "/attendance-credentials";
		MockHttpServletResponse response = perform(HttpMethod.POST, url, s.lead(), null).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		JsonNode sheet = this.json.readTree(response.getContentAsString());
		assertThat(sheet.get("items")).hasSize(3);
		assertThat(sheet.get("skipped")).isEmpty();
		Set<String> tokens = new HashSet<>();
		for (JsonNode item : sheet.get("items")) {
			assertThat(item.get("qrPayload").asString()).matches(TOKEN);
			assertThat(item.get("displayName").asString()).isNotBlank();
			tokens.add(item.get("qrPayload").asString());
		}
		assertThat(tokens).hasSize(3);
		assertThat(sheet.get("items").get(0).get("displayName").asString()).isEqualTo("Abel");
		// a second sheet skips them all; replaceExisting makes new cards and revokes the old ones
		JsonNode again = call(HttpMethod.POST, url, s.admin(), null, 200);
		assertThat(again.get("items")).isEmpty();
		assertThat(again.get("skipped")).hasSize(3);
		assertThat(again.get("skipped").get(0).get("reason").asString()).isEqualTo("ALREADY_ISSUED");
		JsonNode replaced = call(HttpMethod.POST, url, s.lead(), map("replaceExisting", true, "studentIds", List.of(s.s1())), 200);
		assertThat(replaced.get("items")).hasSize(1);
		assertThat(count("select count(*) from attendance_credentials where student_id = ? and revoked_reason = 'REPLACED'", s.s1())).isEqualTo(1);
		// a student who is not in the class is refused, the class's other people may not print its cards
		call(HttpMethod.POST, url, s.admin(), map("studentIds", List.of(UUID.randomUUID())), 409);
		call(HttpMethod.POST, url, s.subject(), null, 403);
		call(HttpMethod.POST, url, s.student1(), null, 403);
		call(HttpMethod.POST, url, s.guardian1(), null, 403);
		call(HttpMethod.POST, url, s.otherTeacher(), null, 404);
	}

	@Test
	void aCardDiesWithThePlaceInTheSchool() throws Exception {
		Setup s = setup("Cred leaver school");
		issue(s, s.s1());
		UUID staffId = this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, s.subjectId());
		call(HttpMethod.POST, s.api() + "/staff/" + staffId + "/attendance-credentials", s.admin(), null, 201);
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/withdraw", s.admin(), map(), 200);
		call(HttpMethod.POST, s.api() + "/staff/" + staffId + "/deactivate", s.admin(), map(), 200);
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(
				"select count(*) from attendance_credentials where school_id = ? and revoked_reason = 'LEFT_SCHOOL'", s.schoolId())).isEqualTo(2));
	}

	@Test
	void staffCredentialsFollowTheSameRulesForManagersOnly() throws Exception {
		Setup s = setup("Cred staff school");
		UUID staffId = this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, s.subjectId());
		String url = s.api() + "/staff/" + staffId + "/attendance-credentials";
		for (Persona nobody : List.of(s.lead(), s.subject(), s.student1())) {
			call(HttpMethod.POST, url, nobody, null, 403);
			call(HttpMethod.GET, url, nobody, null, 403);
		}
		call(HttpMethod.POST, url, s.guardian1(), null, 404);
		JsonNode issued = call(HttpMethod.POST, url, s.admin(), null, 201);
		assertThat(issued.get("credential").get("subjectKind").asString()).isEqualTo("STAFF");
		assertThat(uuid(issued.get("credential"), "staffId")).isEqualTo(staffId);
		assertThat(errorCode(call(HttpMethod.POST, url, s.admin(), null, 409))).contains("attendance.credential_exists");
		call(HttpMethod.POST, url + "/reissue", s.admin(), map("reason", "LOST"), 201);
		call(HttpMethod.POST, url + "/revoke", s.admin(), map("reason", "OTHER"), 200);
		assertThat(get(url, s.admin())).hasSize(2);
		assertThat(errorCode(call(HttpMethod.POST, s.api() + "/staff/" + UUID.randomUUID() + "/attendance-credentials", s.admin(), null, 404)))
			.contains("staff_attendance.staff_not_found");
		this.jdbc.update("update school_staff set status = 'FORMER' where id = ?", staffId);
		assertThat(errorCode(call(HttpMethod.POST, url + "/reissue", s.admin(), null, 409))).contains("staff_attendance.staff_not_active");
	}

	@Test
	void theSchoolListFiltersAndPagesAndIsForManagersOnly() throws Exception {
		Setup s = setup("Cred list school");
		issue(s, s.s1());
		issue(s, s.s2());
		issue(s, s.s3());
		call(HttpMethod.POST, studentCredentials(s, s.s2()) + "/revoke", s.admin(), map("reason", "LOST"), 200);
		String url = s.api() + "/attendance/credentials";
		assertThat(get(url, s.admin()).get("items")).hasSize(3);
		assertThat(get(url + "?state=REVOKED", s.admin()).get("items")).hasSize(1);
		assertThat(get(url + "?state=LIVE&kind=STUDENT", s.admin()).get("items")).hasSize(2);
		assertThat(get(url + "?kind=STAFF", s.admin()).get("items")).isEmpty();
		JsonNode page = get(url + "?limit=2", s.admin());
		assertThat(page.get("items")).hasSize(2);
		assertThat(get(url + "?limit=2&cursor=" + page.get("nextCursor").asString(), s.admin()).get("items")).hasSize(1);
		call(HttpMethod.GET, url + "?state=NOPE", s.admin(), null, 400);
		for (Persona nobody : List.of(s.lead(), s.subject(), s.student1())) {
			call(HttpMethod.GET, url, nobody, null, 403);
		}
		call(HttpMethod.GET, url, s.guardian1(), null, 404);
	}

	@Test
	void aCredentialHashedUnderAnOlderPepperKeyKeepsWorkingAndMovesToTheActiveKey() throws Exception {
		Setup s = setup("Cred rotate school");
		methods(s, "MANUAL", "QR_STATIC");
		// a credential made before the rotation: hashed under key t0 (still configured, no longer active)
		String token = "AbCdEfGhIjKlMnOpQrStUv";
		UUID id = newId();
		this.jdbc.update("""
				insert into attendance_credentials (id, school_id, student_id, token_hash, key_id, issued_by, issued_at, expires_at)
				values (?, ?, ?, ?, 't0', ?, now(), now() + interval '30 days')""", id, s.schoolId(), s.s1(), tokenHash(PEPPER_T0, token), s.admin().userId());
		assertThat(scan(s, s.lead(), token, 200).get("result").asString()).isEqualTo("ACCEPTED");
		Map<String, Object> row = this.jdbc.queryForMap("select token_hash, key_id, last_used_at from attendance_credentials where id = ?", id);
		assertThat(row.get("key_id")).isEqualTo("t1");
		assertThat(row.get("token_hash")).isEqualTo(tokenHash(PEPPER_T1, token));
		assertThat(row.get("last_used_at")).isNotNull();
		// and it still verifies under the new key
		JsonNode second = scan(s, s.subject(), token, 409);
		assertThat(second.get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		// a credential under a key that is no longer configured cannot be verified: it looks unknown (reissue is the remedy)
		String lost = "ZyXwVuTsRqPoNmLkJiHgFe";
		this.jdbc.update("""
				insert into attendance_credentials (id, school_id, student_id, token_hash, key_id, issued_by, issued_at, expires_at)
				values (?, ?, ?, ?, 'gone', ?, now(), now() + interval '30 days')""", newId(), s.schoolId(), s.s2(),
				tokenHash("Hf9wPtZqZ8P5OV7B9Mok7tIpN72WnK1OVDcjtLwgMRA=", lost), s.admin().userId());
		assertThat(scan(s, s.lead(), lost, 409).get("result").asString()).isEqualTo("REJECTED_INVALID");
	}

}
