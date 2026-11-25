package com.nemaz.school.people;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Student login codes: issuing, signing in, revoking, expiry, uniform failures, rate limits, audit hygiene. */
@IntegrationTest
@RecordApplicationEvents
class LoginCodeSignInTest extends SchoolApiTestSupport {

	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	@org.springframework.beans.factory.annotation.Autowired
	ApplicationEvents events;

	private UUID schoolId;

	private UUID classId;

	private Persona admin;

	private Persona teacher;

	private UUID s1;

	private UUID s2;

	@BeforeEach
	void classroom() {
		this.schoolId = this.personas.newSchool("Code school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "code-admin");
		UUID teacherId = this.personas.newTeacher(this.schoolId, "Code teacher");
		this.teacher = this.personas.personaFor("code-teacher", teacherId);
		this.classId = this.personas.newClass(this.schoolId, "Codes 1A");
		this.personas.addTeacherToClass(this.schoolId, this.classId, teacherId, "LEAD", null);
		this.s1 = this.personas.newStudent(this.schoolId, "Kid One");
		this.s2 = this.personas.newStudent(this.schoolId, "Kid Two");
		this.personas.addStudentToClass(this.schoolId, this.classId, this.s1);
		this.personas.addStudentToClass(this.schoolId, this.classId, this.s2);
	}

	private String codesUrl() {
		return school(this.schoolId) + "/classes/" + this.classId + "/login-codes";
	}

	private JsonNode issue(Object body) throws Exception {
		return post(codesUrl(), this.teacher, body, 200);
	}

	private ResultActions signIn(String code) throws Exception {
		return postJson("/api/v1/auth/login-code", Map.of("code", code));
	}

	private static String textOf(JsonNode issued, int index) {
		return issued.get(index).get("textCode").asString();
	}

	@Test
	void issuingTextCodesGivesEachStudentAnUnambiguousCodeShownOnce() throws Exception {
		JsonNode issued = issue(Map.of("type", "TEXT"));
		assertThat(issued).hasSize(2);
		Set<String> seen = new HashSet<>();
		for (JsonNode item : issued) {
			String code = item.get("textCode").asString();
			assertThat(code).matches("^[" + ALPHABET + "]{5}-[" + ALPHABET + "]{5}$");
			assertThat(code).doesNotContain("0", "O", "1", "I", "L");
			assertThat(item.has("qrToken")).isFalse();
			assertThat(item.get("displayName").asString()).startsWith("Kid");
			assertThat(seen.add(code)).isTrue();
		}
		// only a keyed hash is stored, and the response must not be cached
		List<String> hashes = this.jdbc.queryForList("select code_hash from student_login_codes where class_id = ?", String.class, this.classId);
		assertThat(hashes).hasSize(2);
		for (String code : seen) {
			assertThat(hashes).noneMatch((h) -> h.contains(code) || h.contains(code.replace("-", "")));
		}
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(codesUrl()).with(this.teacher.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
		assertThat(this.events.stream(LoginCodeIssued.class).count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void aTextCodeSignsTheStudentInAndTheSessionIsShortAndTheStudentHasTheirMemberships() throws Exception {
		String code = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		JsonNode tokens = this.json.readTree(signIn(code).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(tokens.get("accessToken").asString()).isNotBlank();
		// a classroom device: the refresh token lives two days idle, not fourteen
		assertThat(tokens.get("refreshTokenExpiresIn").asLong()).isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
		assertThat(this.jdbc.queryForObject("select auth_method from auth_sessions where user_id = ?", String.class, this.s1))
			.isEqualTo("LOGIN_CODE");
		assertThat(this.jdbc.queryForObject("select extract(epoch from expires_at - created_at) from auth_sessions where user_id = ?",
				Double.class, this.s1)).isLessThanOrEqualTo((double) Duration.ofDays(7).toSeconds() + 1);
		assertThat(this.jdbc.queryForObject("select last_used_at is not null from student_login_codes where student_id = ?",
				Boolean.class, this.s1)).isTrue();

		JsonNode me = getWithToken("/api/v1/me", tokens.get("accessToken").asString()).andExpect(status().isOk()) == null ? null
				: this.json.readTree(getWithToken("/api/v1/me", tokens.get("accessToken").asString()).andReturn().getResponse()
					.getContentAsString());
		assertThat(me.get("accountType").asString()).isEqualTo("MANAGED");
		assertThat(me.get("memberships")).hasSize(1);
		JsonNode membership = me.get("memberships").get(0);
		assertThat(membership.get("type").asString()).isEqualTo("STUDENT");
		assertThat(membership.get("schoolId").asString()).isEqualTo(this.schoolId.toString());
		assertThat(membership.get("studentId").asString()).isEqualTo(this.s1.toString());
		assertThat(membership.get("schoolName").asString()).isEqualTo("Code school");
		assertThat(membership.has("staffId")).isFalse();
		assertThat(me.get("roleAssignments")).isEmpty();

		JsonNode classes = this.json.readTree(getWithToken("/api/v1/me/classes", tokens.get("accessToken").asString())
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(classes).hasSize(1);
		assertThat(classes.get(0).get("id").asString()).isEqualTo(this.classId.toString());
		// the access token works on the student's own class and refreshes like any other session
		getWithToken(school(this.schoolId) + "/classes/" + this.classId, tokens.get("accessToken").asString()).andExpect(status().isOk());
		postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken").asString())).andExpect(status().isOk());
	}

	@Test
	void caseAndTheHyphenDoNotMatter() throws Exception {
		String code = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		signIn(code.toLowerCase()).andExpect(status().isOk());
		signIn(code.replace("-", "")).andExpect(status().isOk());
		signIn("  " + code.replace("-", " ").toLowerCase() + " ").andExpect(status().isOk());
	}

	@Test
	void aQrTokenWorksAndBothTypesAreIssuedTogether() throws Exception {
		JsonNode issued = issue(Map.of("type", "BOTH", "studentIds", List.of(this.s2)));
		String qr = issued.get(0).get("qrToken").asString();
		assertThat(qr).matches("^[A-Za-z0-9_-]{22}$");
		assertThat(issued.get(0).get("textCode").asString()).contains("-");
		assertThat(this.jdbc.queryForList("select code_type from student_login_codes where student_id = ? order by code_type",
				String.class, this.s2)).containsExactly("QR", "TEXT");
		signIn(qr).andExpect(status().isOk());
		signIn(issued.get(0).get("textCode").asString()).andExpect(status().isOk());
		// QR tokens are case sensitive
		signIn(qr.toLowerCase().equals(qr) ? qr.toUpperCase() : qr.toLowerCase()).andExpect(status().isBadRequest());
	}

	@Test
	void issuingAgainRevokesTheEarlierCodesOfTheStudent() throws Exception {
		String first = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		this.events.clear();
		String second = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		assertThat(second).isNotEqualTo(first);
		signIn(first).andExpect(status().isBadRequest());
		signIn(second).andExpect(status().isOk());
		assertThat(this.jdbc.queryForList("select revoked_at is not null from student_login_codes where student_id = ? order by created_at, id",
				Boolean.class, this.s1)).containsExactly(true, false);
		assertThat(this.events.stream(LoginCodeRevoked.class).map(LoginCodeRevoked::reason).toList()).containsExactly("REPLACED");
		// the other student's codes are untouched
		assertThat(this.jdbc.queryForObject("select count(*) from student_login_codes where student_id = ?", Integer.class, this.s2))
			.isZero();
	}

	@Test
	void revokingOneCodeOrAllOfAStudentStopsThem() throws Exception {
		JsonNode issued = issue(Map.of("type", "BOTH", "studentIds", List.of(this.s1)));
		UUID codeId = this.jdbc.queryForObject("select id from student_login_codes where student_id = ? and code_type = 'TEXT'", UUID.class, this.s1);
		call(HttpMethod.DELETE, codesUrl() + "/" + codeId, this.teacher, null, 204);
		signIn(issued.get(0).get("textCode").asString()).andExpect(status().isBadRequest());
		signIn(issued.get(0).get("qrToken").asString()).andExpect(status().isOk());
		JsonNode revoked = post(school(this.schoolId) + "/students/" + this.s1 + "/login-codes/revoke", this.teacher, null, 200);
		assertThat(revoked.get("revoked").asInt()).isEqualTo(1);
		signIn(issued.get(0).get("qrToken").asString()).andExpect(status().isBadRequest());
		assertThat(get(school(this.schoolId) + "/students/" + this.s1 + "/login-codes", this.teacher)).isEmpty();
		// unknown code ids and ids of other classes are 404
		call(HttpMethod.DELETE, codesUrl() + "/" + UUID.randomUUID(), this.teacher, null, 404);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'login_code.revoke' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(2);
	}

	@Test
	void codesExpireAfterTheirTimeToLive() throws Exception {
		String shortLived = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1), "ttlDays", 1)), 0);
		String longLived = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s2))), 0);
		try {
			this.clock.advance(Duration.ofHours(23));
			signIn(shortLived).andExpect(status().isOk());
			this.clock.advance(Duration.ofHours(2));
			signIn(shortLived).andExpect(status().isBadRequest());
			signIn(longLived).andExpect(status().isOk());
			this.clock.advance(Duration.ofDays(366));
			signIn(longLived).andExpect(status().isBadRequest());
		}
		finally {
			this.clock.reset();
		}
		// the default lifetime is the setting's 365 days; a custom lifetime is bounded
		post(codesUrl(), this.teacher, Map.of("ttlDays", 731), 400);
		post(codesUrl(), this.teacher, Map.of("ttlDays", 0), 400);
	}

	@Test
	void everyKindOfFailureIsTheSameBadRequest() throws Exception {
		String revoked = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		post(school(this.schoolId) + "/students/" + this.s1 + "/login-codes/revoke", this.teacher, null, 200);
		String withdrawn = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s2))), 0);
		post(school(this.schoolId) + "/students/" + this.s2 + "/withdraw", this.admin, null, 200);
		UUID s3 = this.personas.newStudent(this.schoolId, "Kid Three");
		this.personas.addStudentToClass(this.schoolId, this.classId, s3);
		String suspendedSchool = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(s3))), 0);
		this.jdbc.update("update schools set status = 'SUSPENDED' where id = ?", this.schoolId);
		UUID s4 = this.personas.newStudent(this.personas.fixture().schoolA(), "Kid Four");
		String malformed = "not a code";
		String unknown = "AAAAA-BBBBB";
		String unknownQr = "A".repeat(22);

		String expected = null;
		for (String code : List.of(revoked, withdrawn, suspendedSchool, malformed, unknown, unknownQr, "", "   ")) {
			if (code.isBlank()) {
				signIn(code).andExpect(status().isBadRequest());
				continue;
			}
			String body = signIn(code).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			JsonNode problem = this.json.readTree(body);
			assertThat(problem.get("code").asString()).as(code).isEqualTo("auth.invalid_code");
			String shape = problem.get("status").asString() + problem.get("title").asString() + problem.get("detail").asString();
			assertThat(expected == null || expected.equals(shape)).as("same body for " + code).isTrue();
			expected = shape;
		}
		this.jdbc.update("update schools set status = 'ACTIVE' where id = ?", this.schoolId);
		assertThat(s4).isNotNull();
		// a deactivated account fails the same way
		String active = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(s3))), 0);
		this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", s3);
		signIn(active).andExpect(status().isBadRequest());
	}

	@Test
	void guessingIsRateLimitedPerAddressAndPerCodePrefixButAClassroomCanSignInTogether() throws Exception {
		// 20 wrong guesses from one address, then 429 (even for a code that would be right)
		String ip = randomIp();
		String real = textOf(issue(Map.of("type", "TEXT", "studentIds", List.of(this.s1))), 0);
		for (int i = 0; i < 20; i++) {
			postJson("/api/v1/auth/login-code", Map.of("code", randomCode()), from(ip)).andExpect(status().isBadRequest());
		}
		postJson("/api/v1/auth/login-code", Map.of("code", randomCode()), from(ip)).andExpect(status().isTooManyRequests());
		postJson("/api/v1/auth/login-code", Map.of("code", real), from(ip)).andExpect(status().isTooManyRequests());
		// another address is not affected
		postJson("/api/v1/auth/login-code", Map.of("code", real), from(randomIp())).andExpect(status().isOk());

		// 10 wrong guesses sharing a prefix, from ten addresses, lock that prefix for everybody
		String prefix = randomCode().substring(0, 5);
		for (int i = 0; i < 10; i++) {
			postJson("/api/v1/auth/login-code", Map.of("code", prefix + "-" + randomCode().substring(6)), from(randomIp()))
				.andExpect(status().isBadRequest());
		}
		postJson("/api/v1/auth/login-code", Map.of("code", prefix + "-" + randomCode().substring(6)), from(randomIp()))
			.andExpect(status().isTooManyRequests())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));

		// successful sign-ins do not count: 25 pupils behind one address
		List<UUID> pupils = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			UUID pupil = this.personas.newStudent(this.schoolId, "Pupil " + i);
			this.personas.addStudentToClass(this.schoolId, this.classId, pupil);
			pupils.add(pupil);
		}
		JsonNode sheet = issue(Map.of("type", "TEXT", "studentIds", pupils));
		String shared = randomIp();
		for (JsonNode item : sheet) {
			postJson("/api/v1/auth/login-code", Map.of("code", item.get("textCode").asString()), from(shared)).andExpect(status().isOk());
		}
	}

	private static String randomCode() {
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			code.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
			if (i == 4) {
				code.append('-');
			}
		}
		return code.toString();
	}

	@Test
	void codesNeverReachTheAuditLogAndEveryActionIsAudited() throws Exception {
		JsonNode issued = issue(Map.of("type", "BOTH", "studentIds", List.of(this.s1)));
		String text = issued.get(0).get("textCode").asString();
		String qr = issued.get(0).get("qrToken").asString();
		signIn(text).andExpect(status().isOk());
		signIn("ZZZZZ-ZZZZZ").andExpect(status().isBadRequest());
		String audit = this.jdbc.queryForList("select action || ' ' || coalesce(metadata::text, '') || ' ' || coalesce(changes::text, '') from audit_logs where school_id = ? or action like 'auth.login_code%'",
				String.class, this.schoolId).toString();
		assertThat(audit).doesNotContain(text).doesNotContain(text.replace("-", "")).doesNotContain(qr).doesNotContain("ZZZZZ");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'login_code.issue' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'auth.login_code.sign_in' and outcome = 'SUCCESS' and school_id = ?",
				Integer.class, this.schoolId)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'auth.login_code.sign_in' and outcome = 'FAILURE'",
				Integer.class)).isGreaterThanOrEqualTo(1);
		// the response object never prints its codes
		assertThat(new com.nemaz.school.people.application.LoginCodeService.IssuedCode(this.s1, "x", qr, text, java.time.Instant.now())
			.toString()).doesNotContain(qr).doesNotContain(text);
	}

	@Test
	void onlyTeachersOfTheClassOrItsManagersMayIssueCodes() throws Exception {
		Persona pupil = this.personas.personaFor("pupil", this.s1);
		post(codesUrl(), pupil, Map.of("type", "TEXT"), 403);
		Persona strangerTeacher = this.personas.personaFor("stranger", this.personas.newTeacher(this.schoolId, "Stranger"));
		post(codesUrl(), strangerTeacher, Map.of("type", "TEXT"), 404);
		post(codesUrl(), this.admin, Map.of("type", "TEXT"), 200);
		// students who are not in the class
		UUID outsider = this.personas.newStudent(this.schoolId, "Not in class");
		post(codesUrl(), this.teacher, Map.of("studentIds", List.of(outsider)), 409);
		// another school's administrator
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("Foreign"), "foreign-admin");
		post(codesUrl(), otherAdmin, Map.of("type", "TEXT"), 404);
		// a class with no students issues nothing
		UUID emptyClass = this.personas.newClass(this.schoolId, "Empty");
		assertThat(post(school(this.schoolId) + "/classes/" + emptyClass + "/login-codes", this.admin, Map.of(), 200)).isEmpty();
	}

	@Test
	void retryingWithTheSameIdempotencyKeyIssuesFreshCodesAndStoresNoPlaintext() throws Exception {
		// Issuing is deliberately not @Idempotent: replaying a response would keep the plaintext
		// codes (credentials) in idempotency_keys for 24 hours. A retry issues new codes and
		// revokes the previous ones instead.
		String key = UUID.randomUUID().toString();
		String body = this.json.writeValueAsString(Map.of("type", "TEXT"));
		List<String> bodies = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			bodies.add(this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(codesUrl()).with(this.teacher.bearer())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		}
		String first = this.json.readTree(bodies.get(0)).get(0).get("textCode").asString();
		String second = this.json.readTree(bodies.get(1)).get(0).get("textCode").asString();
		assertThat(second).isNotEqualTo(first);
		signIn(first).andExpect(status().isBadRequest());
		signIn(second).andExpect(status().isOk());
		assertThat(this.jdbc.queryForObject("select count(*) from idempotency_keys where response_body::text like ?",
				Integer.class, "%" + second + "%")).isZero();
	}

}
