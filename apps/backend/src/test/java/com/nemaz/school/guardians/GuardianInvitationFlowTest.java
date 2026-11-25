package com.nemaz.school.guardians;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SMS invitation to an adult: preview, one-time code, accept (new or existing account), verification, the guardian's
 * view, auto-verification, uniform errors, rate limits, notifications and what reaches the audit log.
 */
@IntegrationTest
class GuardianInvitationFlowTest extends GuardianTestSupport {

	@Test
	void invitedAdultAcceptsAsPendingAndBecomesAGuardianWhenTheLeadTeacherApproves() throws Exception {
		Instant started = this.clock.instant().minusSeconds(1);
		Setup s = setup("Invite flow school");
		String phone = newPhone();
		String token = inviteBySms(s, s.lead(), phone);

		JsonNode preview = read(preview(token(token), randomIp()));
		assertThat(preview.get("schoolName").asString()).isEqualTo("Invite flow school");
		assertThat(preview.get("childFirstName").asString()).isEqualTo("Abel");
		assertThat(preview.get("relationshipType").asString()).isEqualTo("MOTHER");
		assertThat(preview.get("channel").asString()).isEqualTo("SMS");
		assertThat(preview.propertyNames()).containsExactlyInAnyOrder("schoolName", "childFirstName", "relationshipType",
				"channel");

		String accessToken = acceptWithCode(token(token), phone,
				Map.of("displayName", "Almaz Bekele", "preferredLanguage", "en"));
		UUID guardian = userByPhone(phone);
		assertThat(this.jdbc.queryForMap("select display_name, preferred_language_code, phone_verified_at is not null as verified,"
				+ " account_type from users where id = ?", guardian))
			.containsEntry("display_name", "Almaz Bekele")
			.containsEntry("preferred_language_code", "en")
			.containsEntry("verified", true)
			.containsEntry("account_type", "STANDARD");
		assertThat(this.jdbc.queryForObject("select count(*) from guardian_profiles where user_id = ?", Integer.class,
				guardian)).isOne();
		JsonNode link = linkOf(s.studentId(), guardian);
		assertThat(link.get("status").asString()).isEqualTo("PENDING");
		assertThat(link.get("requested_via").asString()).isEqualTo("INVITE_LINK");

		// PENDING grants nothing: no class, no record, only the pending entry
		Persona adult = as(guardian);
		JsonNode children = get("/api/v1/me/children", adult);
		assertThat(children.get("children")).isEmpty();
		assertThat(children.get("pending")).hasSize(1);
		assertThat(children.get("pending").get(0).get("childFirstName").asString()).isEqualTo("Abel");
		assertThat(children.get("pending").get(0).propertyNames()).doesNotContain("studentId", "displayName");
		call(org.springframework.http.HttpMethod.GET, school(s.schoolId()) + "/classes/" + s.classId(), adult, null, 404);
		call(org.springframework.http.HttpMethod.GET, school(s.schoolId()) + "/students/" + s.studentId(), adult, null, 404);
		assertThat(memberships(adult)).noneMatch((m) -> "GUARDIAN".equals(m.get("type").asString()));

		// verifiers (school administrator, lead teacher) are told
		assertThat(this.jdbc.queryForList("select user_id from notifications where type_code = 'GUARDIAN_LINK_REQUESTED'"
				+ " and source_id = ?::uuid", UUID.class, link.get("id").asString()))
			.containsExactlyInAnyOrder(s.admin().userId(), s.leadId());

		JsonNode queue = get(school(s.schoolId()) + "/guardian-links?status=PENDING", s.lead());
		assertThat(queue.get("items")).hasSize(1);
		assertThat(queue.get("items").get(0).has("guardianPhone")).isFalse();
		JsonNode adminQueue = get(school(s.schoolId()) + "/guardian-links", s.admin());
		assertThat(adminQueue.get("items").get(0).get("contactMatchesInvitation").asBoolean()).isTrue();
		assertThat(adminQueue.get("items").get(0).get("invitedName").asString()).isEqualTo("Almaz");
		assertThat(adminQueue.get("items").get(0).get("guardianPhone").asString()).doesNotContain(phone.substring(5, 9));

		JsonNode approved = post(school(s.schoolId()) + "/guardian-links/" + link.get("id").asString() + "/approve",
				s.lead(), Map.of("note", "Known to the teacher"), 200);
		assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(approved.get("verificationMethod").asString()).isEqualTo("SCHOOL_VERIFIED");
		assertThat(this.jdbc.queryForMap("select verified_by, verified_by_school_id from student_guardians where id = ?::uuid",
				link.get("id").asString()))
			.containsEntry("verified_by", s.leadId())
			.containsEntry("verified_by_school_id", s.schoolId());
		// in the guardian's language (en)
		assertThat(this.jdbc.queryForObject(
				"select title from notifications where user_id = ? and type_code = 'GUARDIAN_LINK_APPROVED'", String.class,
				guardian)).isEqualTo("Link approved");

		children = getWithBearer("/api/v1/me/children", accessToken);
		assertThat(children.get("pending")).isEmpty();
		JsonNode child = children.get("children").get(0);
		assertThat(child.get("studentId").asString()).isEqualTo(s.studentId().toString());
		assertThat(child.get("relationshipType").asString()).isEqualTo("MOTHER");
		JsonNode schoolOfChild = child.get("schools").get(0);
		assertThat(schoolOfChild.get("schoolId").asString()).isEqualTo(s.schoolId().toString());
		assertThat(schoolOfChild.get("classes").get(0).get("name").asString()).isEqualTo("1A");
		assertThat(schoolOfChild.get("classes").get(0).get("teachers").get(0).get("role").asString()).isEqualTo("LEAD");
		assertThat(memberships(adult)).anyMatch((m) -> "GUARDIAN".equals(m.get("type").asString())
				&& s.schoolId().toString().equals(m.get("schoolId").asString()));

		// the guardian sees the class and the child's full record, but never the roster
		get(school(s.schoolId()) + "/classes/" + s.classId(), adult);
		JsonNode record = get(school(s.schoolId()) + "/students/" + s.studentId(), adult);
		assertThat(record.get("accessLevel").asString()).isEqualTo("FULL");
		call(org.springframework.http.HttpMethod.GET, school(s.schoolId()) + "/classes/" + s.classId() + "/students", adult,
				null, 404);

		// consent is requested once the link is ACTIVE (asynchronously, locked type)
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where user_id = ? and type_code = 'CONSENT_REQUESTED'", Integer.class,
				guardian)).isOne());

		// nothing secret reached the audit log
		List<String> audit = this.jdbc.queryForList(
				"select coalesce(metadata::text, '') || coalesce(changes::text, '') from audit_logs where occurred_at >= ?",
				String.class, Timestamp.from(started));
		assertThat(audit).isNotEmpty().noneMatch((row) -> row.contains(phone) || row.contains(token)
				|| row.contains("Known to the teacher"));
	}

	@Test
	void anExistingAdultKeepsTheAccountAndAcceptingTwiceReturnsTheSameLink() throws Exception {
		Setup s = setup("Existing adult school");
		String phone = newPhone();
		UUID existing = user(phone, null, "ACTIVE", true).id();
		this.jdbc.update("update users set preferred_language_code = 'am', display_name = 'Kebede' where id = ?", existing);
		String token = inviteBySms(s, s.admin(), phone);
		String ip = randomIp();
		requestCode(token(token), phone, ip).andExpect((r) -> assertThat(r.getResponse().getStatus()).isEqualTo(202));
		String sms = this.messages.awaitMessage(phone);
		assertThat(sms).contains("ግብዣውን"); // the existing account's language
		String otp = sms.replaceAll("(?s).*?(\\d{6}).*", "$1");
		var response = accept(token(token), phone, otp, ip, Map.of("displayName", "Somebody else")).andReturn()
			.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(this.jdbc.queryForObject("select count(*) from users where phone_e164 = ?", Integer.class, phone)).isOne();
		assertThat(this.jdbc.queryForObject("select display_name from users where id = ?", String.class, existing))
			.isEqualTo("Kebede");
		UUID linkId = UUID.fromString(linkOf(s.studentId(), existing).get("id").asString());

		// a second invitation for the same child, redeemed while signed in: the same link, nothing used up
		String second = inviteBySms(s, s.admin(), newPhone());
		JsonNode again = post("/api/v1/me/guardian-links", as(existing), token(second), 200);
		assertThat(again.get("id").asString()).isEqualTo(linkId.toString());
		assertThat(this.jdbc.queryForObject("select count(*) from student_guardians where student_id = ? and guardian_id = ?",
				Integer.class, s.studentId(), existing)).isOne();
		assertThat(this.jdbc.queryForObject("select use_count from invitations where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
				Integer.class, second)).isZero();
	}

	@Test
	void aForwardedLinkAcceptedWithAnotherPhoneStaysPendingEvenWithAutoVerification() throws Exception {
		Setup s = setup("Forwarded link school");
		setting(s.schoolId(), "guardians.auto_verify_invite", true);
		String invited = newPhone();
		String token = inviteBySms(s, s.admin(), invited);
		String other = newPhone();
		acceptWithCode(token(token), other, Map.of());
		JsonNode link = linkOf(s.studentId(), userByPhone(other));
		assertThat(link.get("status").asString()).isEqualTo("PENDING");
		JsonNode view = get(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.admin()).get(0);
		assertThat(view.get("contactMatchesInvitation").asBoolean()).isFalse();
	}

	@Test
	void autoVerificationActivatesTheLinkWhenTheInvitedPhoneAccepts() throws Exception {
		Setup s = setup("Auto verify school");
		setting(s.schoolId(), "guardians.auto_verify_invite", true);
		String phone = newPhone();
		String token = inviteBySms(s, s.lead(), phone);
		acceptWithCode(token(token), phone, Map.of());
		UUID guardian = userByPhone(phone);
		JsonNode link = linkOf(s.studentId(), guardian);
		assertThat(link.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(link.get("verification_method").asString()).isEqualTo("SCHOOL_INVITE");
		assertThat(this.jdbc.queryForObject("select verified_by from student_guardians where id = ?::uuid", UUID.class,
				link.get("id").asString())).isEqualTo(s.leadId());
		assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'GUARDIAN_LINK_REQUESTED' and source_id = ?::uuid",
				Integer.class, link.get("id").asString())).isZero();
		assertThat(get("/api/v1/me/children", as(guardian)).get("children")).hasSize(1);
	}

	@Test
	void unknownExpiredRevokedAndUsedUpInvitationsAllLookTheSame() throws Exception {
		Setup s = setup("Uniform errors school");
		String ip = randomIp();
		assertThat(errorCode(read(preview(token("x".repeat(43)), ip)))).isEqualTo("invitation.not_found");
		assertThat(errorCode(read(preview(code("ABCDE-FGHJK"), ip)))).isEqualTo("invitation.not_found");
		assertThat(errorCode(read(requestCode(token("x".repeat(43)), newPhone(), ip)))).isEqualTo("invitation.not_found");

		// revoked
		String revokedPhone = newPhone();
		String revoked = inviteBySms(s, s.admin(), revokedPhone);
		UUID revokedId = this.jdbc.queryForObject(
				"select id from invitations where destination_e164 = ?", UUID.class, revokedPhone);
		post(school(s.schoolId()) + "/guardian-invitations/" + revokedId + "/revoke", s.admin(), null, 200);
		assertThat(preview(token(revoked), ip).andReturn().getResponse().getStatus()).isEqualTo(404);

		// used up (single use)
		String usedPhone = newPhone();
		String used = inviteBySms(s, s.admin(), usedPhone);
		acceptWithCode(token(used), usedPhone, Map.of());
		assertThat(errorCode(read(preview(token(used), ip)))).isEqualTo("invitation.not_found");

		// wrong one-time code and no code requested: the same 400
		String livePhone = newPhone();
		String live = inviteBySms(s, s.admin(), livePhone);
		assertThat(errorCode(read(accept(token(live), livePhone, "123456", ip, Map.of())))).isEqualTo("auth.invalid_code");
		requestCode(token(live), livePhone, ip);
		this.messages.awaitCode(livePhone);
		assertThat(errorCode(read(accept(token(live), livePhone, "000000", ip, Map.of())))).isEqualTo("auth.invalid_code");

		// expired after 14 days
		String later = inviteBySms(s, s.admin(), newPhone());
		this.clock.advance(Duration.ofDays(15));
		assertThat(preview(token(later), ip).andReturn().getResponse().getStatus()).isEqualTo(404);
		this.clock.reset();

		// a code is sent whether or not the phone has an account: the same 202
		String withAccount = newPhone();
		user(withAccount, null, "ACTIVE", true);
		assertThat(requestCode(token(live), withAccount, randomIp()).andReturn().getResponse().getStatus()).isEqualTo(202);
		assertThat(requestCode(token(live), newPhone(), randomIp()).andReturn().getResponse().getStatus()).isEqualTo(202);
	}

	@Test
	void invitationsWorkWhileTheSchoolIsOnboardingButNotWhenItIsSuspendedOrArchived() throws Exception {
		Setup s = setup("Onboarding invite school");
		String token = inviteBySms(s, s.admin(), "+251911700001");
		String ip = randomIp();
		for (String status : new String[] { "ONBOARDING", "ACTIVE" }) {
			this.jdbc.update("update schools set status = ? where id = ?", status, s.schoolId());
			assertThat(preview(token(token), ip).andReturn().getResponse().getStatus()).as(status).isEqualTo(200);
		}
		for (String status : new String[] { "SUSPENDED", "ARCHIVED" }) {
			this.jdbc.update("update schools set status = ? where id = ?", status, s.schoolId());
			assertThat(preview(token(token), ip).andReturn().getResponse().getStatus()).as(status).isEqualTo(404);
		}
	}

	@Test
	void guessingAndCodeRequestsAreRateLimited() throws Exception {
		Setup s = setup("Rate limit school");
		String ip = randomIp();
		for (int i = 0; i < 20; i++) {
			assertThat(preview(token(String.format("%043d", i)), ip).andReturn().getResponse().getStatus()).isEqualTo(404);
		}
		assertThat(preview(token("y".repeat(43)), ip).andReturn().getResponse().getStatus()).isEqualTo(429);

		String phone = newPhone();
		String token = inviteBySms(s, s.admin(), phone);
		for (int i = 0; i < 3; i++) {
			assertThat(requestCode(token(token), phone, randomIp()).andReturn().getResponse().getStatus()).isEqualTo(202);
		}
		assertThat(requestCode(token(token), phone, randomIp()).andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void invitationsArePerStudentLimitedAndSentInTheSchoolLanguage() throws Exception {
		Setup s = setup("Limit school");
		String phone = newPhone();
		post(invitations(s), s.admin(), Map.of("phone", phone, "relationshipType", "FATHER"), 201);
		String sms = this.messages.awaitMessage(phone);
		assertThat(sms).contains("http://localhost:3100/am/invite/").contains("Limit school").doesNotContain("Abel");
		for (int i = 1; i < 10; i++) {
			post(invitations(s), s.admin(), Map.of("email", "parent" + i + "@example.com", "relationshipType", "OTHER"), 201);
		}
		JsonNode problem = post(invitations(s), s.admin(), Map.of("phone", newPhone(), "relationshipType", "OTHER"), 409);
		assertThat(errorCode(problem)).isEqualTo("invitation.limit_reached");
		JsonNode list = get(invitations(s), s.admin());
		assertThat(list).hasSize(10);
		assertThat(list.toString()).doesNotContain(phone).doesNotContain("parent1@example.com");
	}

	private List<JsonNode> memberships(Persona persona) throws Exception {
		List<JsonNode> result = new java.util.ArrayList<>();
		get("/api/v1/me", persona).get("memberships").forEach(result::add);
		return result;
	}

	private JsonNode getWithBearer(String url, String accessToken) throws Exception {
		var response = getWithToken(url, accessToken).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		return this.json.readTree(response.getContentAsString());
	}

}
