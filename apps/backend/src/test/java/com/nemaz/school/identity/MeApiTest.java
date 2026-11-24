package com.nemaz.school.identity;

import java.time.Duration;
import java.util.Map;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class MeApiTest extends AuthTestSupport {

	@Test
	void meListsRolesScopesAndPermissions() throws Exception {
		Personas.Fixture f = this.personas.fixture();
		Persona admin = this.personas.get(Name.SCHOOL_ADMIN_A);
		this.mvc.perform(get("/api/v1/me").with(admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(admin.userId().toString()))
			.andExpect(jsonPath("$.roleAssignments", hasSize(1)))
			.andExpect(jsonPath("$.roleAssignments[0].roleCode").value("SCHOOL_ADMIN"))
			.andExpect(jsonPath("$.roleAssignments[0].roleName").value("School administrator"))
			.andExpect(jsonPath("$.roleAssignments[0].scope.type").value("SCHOOL"))
			.andExpect(jsonPath("$.roleAssignments[0].scope.schoolId").value(f.schoolA().toString()))
			.andExpect(jsonPath("$.roleAssignments[0].scopeName").value("Persona School A"))
			.andExpect(jsonPath("$.scopes[0].permissions", hasItem("school.settings.manage")))
			.andExpect(jsonPath("$.scopes[0].roleCodes[0]").value("SCHOOL_ADMIN"))
			.andExpect(jsonPath("$.hasPassword").value(false));
		this.mvc.perform(get("/api/v1/me").with(this.personas.get(Name.COUNTRY_ADMIN_ET).bearer())
			.header("Accept-Language", "am"))
			.andExpect(jsonPath("$.roleAssignments[0].roleName").value("የአገር አስተዳዳሪ"))
			.andExpect(jsonPath("$.roleAssignments[0].scope.countryCode").value("ET"));
		this.mvc.perform(get("/api/v1/me").with(this.personas.get(Name.CAMPUS_ADMIN_A).bearer()))
			.andExpect(jsonPath("$.roleAssignments[0].scope.campusId").value(f.campusA().toString()))
			.andExpect(jsonPath("$.roleAssignments[0].scope.schoolId").value(f.schoolA().toString()))
			.andExpect(jsonPath("$.roleAssignments[0].scopeName").value("Campus A"));
		this.mvc.perform(get("/api/v1/me").with(this.personas.plainUser().bearer()))
			.andExpect(jsonPath("$.roleAssignments", hasSize(0)))
			.andExpect(jsonPath("$.scopes", hasSize(0)));
		this.mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void profileUpdatesAreValidatedAndAudited() throws Exception {
		Persona me = this.personas.newUser("profile-user");
		this.mvc.perform(patch("/api/v1/me").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\" Abebe B. \",\"preferredLanguageCode\":\"am\",\"timezone\":\"Africa/Addis_Ababa\",\"calendarPreference\":\"ETHIOPIAN\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Abebe B."))
			.andExpect(jsonPath("$.preferredLanguageCode").value("am"))
			.andExpect(jsonPath("$.timezone").value("Africa/Addis_Ababa"))
			.andExpect(jsonPath("$.calendarPreference").value("ETHIOPIAN"));
		this.mvc.perform(patch("/api/v1/me").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"timezone\":\"Nowhere/City\"}")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("timezone.invalid"));
		this.mvc.perform(patch("/api/v1/me").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"preferredLanguageCode\":\"xx\"}")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("language.unknown"));
		this.mvc.perform(patch("/api/v1/me").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"calendarPreference\":\"MAYAN\"}")).andExpect(status().isBadRequest());
		this.mvc.perform(patch("/api/v1/me").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\" \"}")).andExpect(status().isBadRequest());
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'identity.profile.update' and target_id = ?", Integer.class,
				me.userId())).isEqualTo(1);
	}

	@Test
	void passwordCanBeSetAfterAFreshSignInAndChangedWithTheCurrentOne() throws Exception {
		TestUser user = userWithPhone();
		TokenPair stale = session(user.id());
		this.clock.advance(Duration.ofMinutes(11));
		TokenPair fresh = session(user.id());
		this.mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + stale.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"newPassword\":\"a long enough password\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("auth.reauthentication_required"));
		this.mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + fresh.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"newPassword\":\"short\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("password.too_weak"));
		this.mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + fresh.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"newPassword\":\"a long enough password\"}"))
			.andExpect(status().isNoContent());
		// the other session was signed out
		getWithToken("/api/v1/me", stale.accessToken()).andExpect(status().isUnauthorized());
		getWithToken("/api/v1/me", fresh.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.hasPassword").value(true));
		postJson("/api/v1/auth/password", Map.of("identifier", user.phone(), "password", "a long enough password"))
			.andExpect(status().isOk());

		this.mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + fresh.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"wrong\",\"newPassword\":\"another long password\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("password.current_invalid"));
		this.mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + fresh.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"a long enough password\",\"newPassword\":\"another long password\"}"))
			.andExpect(status().isNoContent());
		postJson("/api/v1/auth/password", Map.of("identifier", user.phone(), "password", "another long password"))
			.andExpect(status().isOk());
	}

}
