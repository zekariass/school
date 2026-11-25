package com.nemaz.school.guardians;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Printed parent codes: plaintext once, multi-use with a cap, expiry, reissue, always PENDING, never stored. */
@IntegrationTest
class ParentCodeTest extends GuardianTestSupport {

	private String codes(Setup s) {
		return school(s.schoolId()) + "/classes/" + s.classId() + "/parent-codes";
	}

	private String issue(Setup s, Persona as) throws Exception {
		var response = perform(org.springframework.http.HttpMethod.POST, codes(s), as, Map.of()).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		JsonNode issued = this.json.readTree(response.getContentAsString());
		assertThat(issued).hasSize(1);
		assertThat(issued.get(0).get("childFirstName").asString()).isEqualTo("Abel");
		assertThat(issued.get(0).get("maxUses").asInt()).isEqualTo(4);
		String code = issued.get(0).get("code").asString();
		assertThat(code).matches("^[A-Z2-9]{5}-[A-Z2-9]{5}$");
		return code;
	}

	@Test
	void aCodeLinksUpToFourAdultsAsPendingAndIsNeverStored() throws Exception {
		Setup s = setup("Parent code school");
		setting(s.schoolId(), "guardians.auto_verify_invite", true);
		String code = issue(s, s.lead());
		assertThat(this.jdbc.queryForObject("select count(*) from invitations where code_hash = ? or token_hash = ?",
				Integer.class, code, code)).isZero();
		assertThat(this.jdbc.queryForList("select coalesce(metadata::text, '') from audit_logs where school_id = ?",
				String.class, s.schoolId())).noneMatch((m) -> m.contains(code) || m.contains(code.replace("-", "")));

		JsonNode preview = read(preview(code(code.toLowerCase().replace("-", "")), randomIp()));
		assertThat(preview.get("channel").asString()).isEqualTo("PRINTED_CODE");
		assertThat(preview.has("relationshipType")).isFalse();

		for (int i = 0; i < 4; i++) {
			String phone = newPhone();
			acceptWithCode(code(code), phone, Map.of());
			JsonNode link = linkOf(s.studentId(), userByPhone(phone));
			// printed codes never auto-verify
			assertThat(link.get("status").asString()).isEqualTo("PENDING");
			assertThat(link.get("requested_via").asString()).isEqualTo("PARENT_CODE");
		}
		assertThat(errorCode(read(preview(code(code), randomIp())))).isEqualTo("invitation.not_found");

		// a signed-in adult names the relationship themself
		String fresh = issue(s, s.admin());
		Persona adult = this.personas.newUser("code-redeemer");
		JsonNode link = post("/api/v1/me/guardian-links", adult, Map.of("code", fresh, "relationshipType", "GRANDPARENT"),
				200);
		assertThat(link.get("status").asString()).isEqualTo("PENDING");
		assertThat(link.get("relationshipType").asString()).isEqualTo("GRANDPARENT");
		assertThat(link.has("guardianPhone")).isFalse();
	}

	@Test
	void reissuingRevokesThePreviousCodeAndCodesExpire() throws Exception {
		Setup s = setup("Code expiry school");
		String first = issue(s, s.lead());
		String second = issue(s, s.lead());
		assertThat(preview(code(first), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(preview(code(second), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(this.jdbc.queryForObject("""
				select count(*) from invitations where student_id = ? and channel = 'PRINTED_CODE' and status = 'REVOKED'""",
				Integer.class, s.studentId())).isOne();
		this.clock.advance(Duration.ofDays(31));
		assertThat(preview(code(second), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(404);
		this.clock.reset();
		setting(s.schoolId(), "guardians.parent_code.ttl_days", 2);
		String shortLived = issue(s, s.lead());
		this.clock.advance(Duration.ofDays(3));
		assertThat(preview(code(shortLived), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void guessingCodesIsLimitedPerPrefix() throws Exception {
		setup("Code guessing school");
		for (int i = 0; i < 10; i++) {
			String guess = "QQQQQ" + "ABCDEFGHJK".substring(i, i + 1) + "2345";
			assertThat(preview(code(guess), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(404);
		}
		assertThat(preview(code("QQQQQ-99999"), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void teachersInviteOnlyForTheirStudentsAndAssistantsDoNot() throws Exception {
		Setup s = setup("Teacher invite school");
		UUID assistant = this.personas.newTeacher(s.schoolId(), "assistant");
		this.personas.addTeacherToClass(s.schoolId(), s.classId(), assistant, "ASSISTANT", null);
		UUID other = this.personas.newTeacher(s.schoolId(), "other-teacher");
		UUID otherClass = this.personas.newClass(s.schoolId(), "2B");
		this.personas.addTeacherToClass(s.schoolId(), otherClass, other, "CO_TEACHER", null);

		Map<String, Object> body = Map.of("phone", newPhone(), "relationshipType", "FATHER");
		assertThat(errorCode(post(invitations(s), as(assistant), body, 403))).isEqualTo("guardian.access_denied");
		post(codes(s), as(assistant), Map.of(), 403);
		// a teacher of another class cannot see the student at all
		assertThat(errorCode(post(invitations(s), as(other), body, 404))).isEqualTo("student.not_found");
		post(invitations(s), s.lead(), body, 201);

		setting(s.schoolId(), "guardians.teachers_can_invite", false);
		post(invitations(s), s.lead(), Map.of("phone", newPhone(), "relationshipType", "FATHER"), 403);
		post(codes(s), s.lead(), Map.of(), 403);
		post(invitations(s), s.admin(), Map.of("phone", newPhone(), "relationshipType", "FATHER"), 201);
	}

	@Test
	void studentsOutsideTheClassAreRefused() throws Exception {
		Setup s = setup("Code roster school");
		UUID outsider = this.personas.newStudent(s.schoolId(), "outsider");
		JsonNode problem = post(codes(s), s.admin(), Map.of("studentIds", List.of(outsider)), 409);
		assertThat(errorCode(problem)).isEqualTo("invitation.student_not_in_class");
	}

}
