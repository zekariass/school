package com.nemaz.school.identity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Administrator provisioning of users: creation, duplicates, exact lookup, audit and sign-in. */
@IntegrationTest
class UserProvisioningApiTest extends AuthTestSupport {

	private static final String URL = "/api/v1/platform/users";

	private ResultActions create(Persona as, Map<String, Object> body) throws Exception {
		return this.mvc.perform(post(URL).with(as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(body)));
	}

	@Test
	void createsAnActiveUserWithAVerifiedPhoneWhoCanSignInWithACode() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String phone = Personas.newPhone();
		String national = "0" + phone.substring(4);
		JsonNode created = this.json.readTree(create(admin,
				Map.of("phone", national, "givenName", "Abebe", "familyName", "Kebede", "preferredLanguage", "am"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.displayName").value("Abebe Kebede"))
			.andExpect(jsonPath("$.maskedPhone").value(phone.substring(0, 5) + "****" + phone.substring(phone.length() - 3)))
			.andReturn()
			.getResponse()
			.getContentAsString());
		UUID id = UUID.fromString(created.get("id").asString());

		Map<String, Object> row = this.jdbc.queryForMap(
				"select phone_e164, phone_verified_at is not null as verified, status, account_type, given_name, family_name, "
						+ "preferred_language_code from users where id = ?", id);
		assertThat(row).containsEntry("phone_e164", phone)
			.containsEntry("verified", true)
			.containsEntry("status", "ACTIVE")
			.containsEntry("account_type", "STANDARD")
			.containsEntry("given_name", "Abebe")
			.containsEntry("family_name", "Kebede")
			.containsEntry("preferred_language_code", "am");

		// the new user can sign in by code: the request is accepted and a challenge exists
		requestOtp(phone).andExpect(status().isAccepted());
		assertThat(this.jdbc.queryForObject("select count(*) from otp_challenges where user_id = ?", Integer.class, id))
			.isEqualTo(1);

		assertThat(this.jdbc.queryForList(
				"select metadata::text from audit_logs where action = 'user.create' and target_id = ?", String.class, id))
			.singleElement()
			.satisfies((m) -> assertThat(m).doesNotContain(phone).contains("****"));
	}

	@Test
	void createsAUserByEmailAndNormalizesIt() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String email = "New.Person." + UUID.randomUUID() + "@Example.com";
		create(admin, Map.of("email", email, "givenName", "Sara", "displayName", "Teacher Sara"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.displayName").value("Teacher Sara"));
		assertThat(this.jdbc.queryForObject(
				"select email_verified_at is not null from users where email = ?", Boolean.class, email.toLowerCase()))
			.isTrue();
	}

	@Test
	void rejectsDuplicatesAndBadInput() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String phone = Personas.newPhone();
		create(admin, Map.of("phone", phone, "givenName", "Dup")).andExpect(status().isCreated());
		create(admin, Map.of("phone", phone, "givenName", "Dup2")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("user.phone_taken"));
		String email = "dup-" + UUID.randomUUID() + "@example.com";
		create(admin, Map.of("email", email, "givenName", "Dup")).andExpect(status().isCreated());
		create(admin, Map.of("email", email.toUpperCase(), "givenName", "Dup2")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("user.email_taken"));

		create(admin, Map.of("givenName", "Nobody")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("user.contact_required"));
		create(admin, Map.of("phone", "12", "givenName", "Bad")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("user.invalid_phone"));
		create(admin, Map.of("email", "not-an-email", "givenName", "Bad")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("user.invalid_email"));
		create(admin, Map.of("phone", Personas.newPhone(), "givenName", "Bad", "preferredLanguage", "zz"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("language.unknown"));
		Map<String, Object> noName = new HashMap<>();
		noName.put("phone", Personas.newPhone());
		create(admin, noName).andExpect(status().isBadRequest());
		// localized message
		this.mvc.perform(post(URL).with(admin.bearer()).header("Accept-Language", "am")
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("phone", phone, "givenName", "Dup"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("በዚህ ስልክ ቁጥር የተመዘገበ መለያ አስቀድሞ አለ።"));
	}

	@Test
	void creationIsIdempotentPerKey() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String body = this.json.writeValueAsString(Map.of("phone", Personas.newPhone(), "givenName", "Once"));
		String key = UUID.randomUUID().toString();
		String first = this.mvc.perform(post(URL).with(admin.bearer()).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		this.mvc.perform(post(URL).with(admin.bearer()).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string("Idempotent-Replayed", "true"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));
	}

	@Test
	void lookupIsAnExactMatchWithMaskedDestination() throws Exception {
		Persona admin = this.personas.platformAdmin();
		Persona target = this.personas.newUser("lookup-target");
		String phone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class,
				target.userId());
		this.mvc.perform(get(URL + "/lookup").param("phone", phone).with(admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(target.userId().toString()))
			.andExpect(jsonPath("$.displayName").value("lookup-target"))
			.andExpect(jsonPath("$.maskedDestination").value(phone.substring(0, 5) + "****" + phone.substring(phone.length() - 3)))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		// national format works too
		this.mvc.perform(get(URL + "/lookup").param("phone", "0" + phone.substring(4)).with(admin.bearer()))
			.andExpect(status().isOk());
		// a prefix is not a match
		this.mvc.perform(get(URL + "/lookup").param("phone", phone.substring(0, phone.length() - 1)).with(admin.bearer()))
			.andExpect(status().is4xxClientError());
		this.mvc.perform(get(URL + "/lookup").param("phone", Personas.newPhone()).with(admin.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("user.not_found"));
		this.mvc.perform(get(URL + "/lookup").with(admin.bearer())).andExpect(status().isBadRequest());
		this.mvc.perform(get(URL + "/lookup").param("phone", phone).param("email", "a@b.co").with(admin.bearer()))
			.andExpect(status().isBadRequest());
		// by email
		String email = "lookup-" + UUID.randomUUID() + "@example.com";
		create(admin, Map.of("email", email, "givenName", "Mail")).andExpect(status().isCreated());
		this.mvc.perform(get(URL + "/lookup").param("email", email.toUpperCase()).with(admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maskedDestination").value("l***" + email.substring(email.indexOf('@'))));
	}

	@Test
	void aUserIsReadableByIdWithAMaskedDestination() throws Exception {
		Persona admin = this.personas.platformAdmin();
		Persona target = this.personas.newUser("by-id");
		this.mvc.perform(get(URL + "/" + target.userId()).with(admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("by-id"))
			.andExpect(jsonPath("$.maskedDestination").value(org.hamcrest.Matchers.containsString("****")));
		this.mvc.perform(get(URL + "/" + UUID.randomUUID()).with(admin.bearer())).andExpect(status().isNotFound());
	}

	@Test
	void aSchoolAdminWithoutRoleManagementCannotCreateOrLookUp() throws Exception {
		Persona schoolAdmin = this.personas.get(Name.SCHOOL_ADMIN_A);
		create(schoolAdmin, Map.of("phone", Personas.newPhone(), "givenName", "No")).andExpect(status().isForbidden());
		this.mvc.perform(get(URL + "/lookup").param("phone", Personas.newPhone()).with(schoolAdmin.bearer()))
			.andExpect(status().isForbidden());
	}

}
