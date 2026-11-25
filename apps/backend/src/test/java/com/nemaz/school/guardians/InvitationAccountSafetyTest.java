package com.nemaz.school.guardians;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.AccessScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accepting an invitation proves a phone, but never takes over an account that holds it without having verified it,
 * a school-managed account or an inactive one: those are refused with 409 {@code invitation.contact_unavailable},
 * without a session, a verification, a link or a used-up invitation.
 */
@IntegrationTest
class InvitationAccountSafetyTest extends GuardianTestSupport {

	/** A printed parent code for the setup's student (the acceptor chooses the destination). */
	private String parentCode(Setup s) throws Exception {
		JsonNode issued = post(school(s.schoolId()) + "/classes/" + s.classId() + "/parent-codes", s.admin(), Map.of(),
				200);
		return issued.get(0).get("code").asString();
	}

	/** Requests and submits the one-time code for {@code phone}; returns the response body with its status checked. */
	private JsonNode acceptExpecting(String code, String phone, int status) throws Exception {
		String ip = randomIp();
		assertThat(requestCode(code(code), phone, ip).andReturn().getResponse().getStatus()).isEqualTo(202);
		String otp = this.messages.awaitCode(phone);
		var response = accept(code(code), phone, otp, ip, Map.of("displayName", "Attacker")).andReturn().getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		return this.json.readTree(response.getContentAsString());
	}

	private void assertNothingHappened(Setup s, UUID holder) {
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where user_id = ?", Integer.class, holder))
			.isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from student_guardians where student_id = ?", Integer.class,
				s.studentId())).isZero();
		assertThat(this.jdbc.queryForObject(
				"select use_count from invitations where student_id = ? and channel = 'PRINTED_CODE' and status = 'PENDING'",
				Integer.class, s.studentId())).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from guardian_profiles where user_id = ?", Integer.class,
				holder)).isZero();
	}

	@Test
	void anAccountWithTheSamePhoneUnverifiedIsNotTakenOver() throws Exception {
		Setup s = setup("Takeover unverified school");
		String phone = newPhone();
		UUID holder = user(phone, null, "ACTIVE", false).id();
		// the holder may be an administrator: the attacker must not inherit that
		this.personas.grant(holder, RoleCodes.SCHOOL_ADMIN, AccessScope.school(s.schoolId()));
		String code = parentCode(s);
		JsonNode problem = acceptExpecting(code, phone, 409);
		assertThat(errorCode(problem)).isEqualTo("invitation.contact_unavailable");
		assertThat(problem.has("accessToken")).isFalse();
		assertThat(this.jdbc.queryForObject("select phone_verified_at is null from users where id = ?", Boolean.class,
				holder)).isTrue();
		assertNothingHappened(s, holder);
	}

	@Test
	void aSchoolManagedAccountHoldingThePhoneIsRefused() throws Exception {
		Setup s = setup("Takeover managed school");
		String phone = newPhone();
		UUID managed = Ids.newId();
		this.jdbc.update("""
				insert into users (id, account_type, given_name, display_name, phone_e164, phone_verified_at, status)
				values (?, 'MANAGED', 'Child', 'Child', ?, now(), 'ACTIVE')""", managed, phone);
		JsonNode problem = acceptExpecting(parentCode(s), phone, 409);
		assertThat(errorCode(problem)).isEqualTo("invitation.contact_unavailable");
		assertNothingHappened(s, managed);
	}

	@Test
	void aSuspendedOrDeactivatedHolderIsRefused() throws Exception {
		Setup s = setup("Takeover inactive school");
		String code = parentCode(s);
		for (String status : new String[] { "SUSPENDED", "DEACTIVATED" }) {
			String phone = newPhone();
			UUID holder = user(phone, null, status, true).id();
			JsonNode problem = acceptExpecting(code, phone, 409);
			assertThat(errorCode(problem)).isEqualTo("invitation.contact_unavailable");
			assertNothingHappened(s, holder);
		}
	}

	@Test
	void anActiveAccountThatVerifiedThePhoneAcceptsAsBefore() throws Exception {
		Setup s = setup("Verified holder school");
		String phone = newPhone();
		UUID holder = user(phone, null, "ACTIVE", true).id();
		JsonNode tokens = acceptExpecting(parentCode(s), phone, 200);
		assertThat(tokens.get("accessToken").asString()).isNotBlank();
		assertThat(this.jdbc.queryForObject("select display_name from users where id = ?", String.class, holder))
			.isEqualTo("Test user");
		assertThat(linkOf(s.studentId(), holder).get("status").asString()).isEqualTo("PENDING");
		assertThat(this.jdbc.queryForObject("select use_count from invitations where student_id = ? and channel = 'PRINTED_CODE'",
				Integer.class, s.studentId())).isOne();
	}

}
