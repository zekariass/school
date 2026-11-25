package com.nemaz.school.guardians;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.SchoolApiTestSupport;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;

/** A school with a class, its lead teacher and a student, plus the calls of the invitation flows. */
public abstract class GuardianTestSupport extends SchoolApiTestSupport {

	private static final Pattern LINK = Pattern.compile("/(am|en)/invite/([A-Za-z0-9_-]{43})");

	@Autowired
	protected SettingsService settings;

	/** {@code student} is named "Abel" (given and display name). */
	protected record Setup(UUID schoolId, Persona admin, Persona lead, UUID leadId, UUID classId, UUID studentId) {
	}

	protected Setup setup(String label) {
		UUID school = this.personas.newSchool(label);
		Persona admin = this.personas.schoolAdmin(school, label + "-admin");
		UUID lead = this.personas.newTeacher(school, label + "-lead");
		UUID classId = this.personas.newClass(school, "1A");
		this.personas.addTeacherToClass(school, classId, lead, "LEAD", null);
		UUID student = this.personas.newStudent(school, "Abel");
		this.personas.addStudentToClass(school, classId, student);
		return new Setup(school, admin, this.personas.personaFor(label + "-lead", lead), lead, classId, student);
	}

	protected void setting(UUID schoolId, String key, Object value) {
		this.settings.set(key, SettingScope.school(schoolId), this.json.valueToTree(value),
				this.personas.platformAdmin().userId());
	}

	protected String invitations(Setup s) {
		return school(s.schoolId()) + "/students/" + s.studentId() + "/guardian-invitations";
	}

	/** Invites {@code phone} for the setup's student as {@code as}; returns the link token read from the SMS. */
	protected String inviteBySms(Setup s, Persona as, String phone) throws Exception {
		JsonNode invitation = post(invitations(s), as, Map.of("phone", phone, "relationshipType", "MOTHER",
				"guardianName", "Almaz"), 201);
		assertThat(invitation.get("channel").asString()).isEqualTo("SMS");
		return tokenIn(this.messages.awaitMessage(phone));
	}

	protected static String tokenIn(String message) {
		Matcher matcher = LINK.matcher(message);
		assertThat(matcher.find()).as("invitation link in " + message).isTrue();
		return matcher.group(2);
	}

	protected ResultActions preview(Map<String, String> key, String ip) throws Exception {
		return postJson("/api/v1/invitations/preview", key, from(ip));
	}

	protected ResultActions requestCode(Map<String, String> key, String phone, String ip) throws Exception {
		Map<String, Object> body = new HashMap<>(key);
		body.put("phone", phone);
		return postJson("/api/v1/invitations/otp", body, from(ip));
	}

	protected ResultActions accept(Map<String, String> key, String phone, String code, String ip,
			Map<String, String> extra) throws Exception {
		Map<String, Object> body = new HashMap<>(key);
		body.put("phone", phone);
		body.put("otpCode", code);
		body.putAll(extra);
		return postJson("/api/v1/invitations/accept", body, from(ip));
	}

	/** Requests a code for {@code phone}, reads it from the SMS and accepts; returns the access token. */
	protected String acceptWithCode(Map<String, String> key, String phone, Map<String, String> extra) throws Exception {
		String ip = randomIp();
		requestCode(key, phone, ip).andExpect((r) -> assertThat(r.getResponse().getStatus()).isEqualTo(202));
		String code = this.messages.awaitCode(phone);
		var result = accept(key, phone, code, ip, extra).andReturn().getResponse();
		assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(200);
		return this.json.readTree(result.getContentAsString()).get("accessToken").asString();
	}

	protected UUID userByPhone(String phone) {
		return this.jdbc.queryForObject("select id from users where phone_e164 = ?", UUID.class, phone);
	}

	/** A persona for an existing user (a fresh token through the real TokenService). */
	protected Persona as(UUID userId) {
		return this.personas.personaFor("user-" + userId, userId);
	}

	protected JsonNode linkOf(UUID studentId, UUID guardianId) {
		return this.json.valueToTree(this.jdbc.queryForMap("""
				select id::text as id, status, verification_method, requested_via, custody_restricted
				from student_guardians where student_id = ? and guardian_id = ? order by created_at desc limit 1""",
				studentId, guardianId));
	}

	protected static Map<String, String> token(String token) {
		return Map.of("token", token);
	}

	protected static Map<String, String> code(String code) {
		return Map.of("code", code);
	}

	protected static String newPhone() {
		return Personas.newPhone();
	}

}
