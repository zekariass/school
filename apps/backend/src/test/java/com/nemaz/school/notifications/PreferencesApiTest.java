package com.nemaz.school.notifications;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class PreferencesApiTest extends NotificationTestSupport {

	private static String change(String type, String channel, boolean enabled) {
		return "{\"changes\":[{\"typeCode\":\"" + type + "\",\"channel\":\"" + channel + "\",\"enabled\":" + enabled + "}]}";
	}

	@Test
	void listsEveryTypeByCategoryWithDefaultsAndLocks() throws Exception {
		Persona user = this.personas.newUser("prefs-list");
		this.mvc.perform(get("/api/v1/me/notification-preferences").with(user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories[?(@.code=='CLASSROOM')].label").value("Classroom"))
			.andExpect(jsonPath("$.categories[?(@.code=='CLASSROOM')].types[?(@.code=='NEW_POST')].label").value("Class posts"))
			// push on by default, IN_APP locked on
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_POST')].channels[?(@.channel=='PUSH')].enabled").value(true))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_POST')].channels[?(@.channel=='PUSH')].locked").value(false))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_POST')].channels[?(@.channel=='IN_APP')].locked").value(true))
			// IN_APP only by default
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_STORY')].channels[?(@.channel=='PUSH')].enabled").value(false))
			// locked type
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='CONSENT_REQUESTED')].userCanDisable").value(false))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='CONSENT_REQUESTED')].channels[?(@.channel=='PUSH')].locked").value(true))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='CONSENT_REQUESTED')].channels[?(@.channel=='PUSH')].enabled").value(true));
	}

	@Test
	void labelsFollowTheCallersLanguage() throws Exception {
		Persona user = this.personas.newUser("prefs-lang");
		this.mvc.perform(get("/api/v1/me/notification-preferences").header("Accept-Language", "am").with(user.bearer()))
			.andExpect(jsonPath("$.categories[?(@.code=='CLASSROOM')].label").value("ክፍል"));
	}

	@Test
	void storesChoicesAndShowsThemBack() throws Exception {
		Persona user = this.personas.newUser("prefs-store");
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("NEW_POST", "PUSH", false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_POST')].channels[?(@.channel=='PUSH')].enabled").value(false));
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("NEW_STORY", "PUSH", true)))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_STORY')].channels[?(@.channel=='PUSH')].enabled").value(true));
		this.mvc.perform(get("/api/v1/me/notification-preferences").with(user.bearer()))
			.andExpect(jsonPath("$.categories[*].types[?(@.code=='NEW_POST')].channels[?(@.channel=='PUSH')].enabled").value(false));
	}

	@Test
	void lockedTypesAndInAppCannotBeSwitchedOff() throws Exception {
		Persona user = this.personas.newUser("prefs-locked");
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("CONSENT_REQUESTED", "PUSH", false)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.preference_locked"));
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("NEW_POST", "IN_APP", false)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.preference_locked"));
		// switching a locked one "on" is harmless
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("CONSENT_REQUESTED", "PUSH", true)))
			.andExpect(status().isOk());
		// a bad entry changes nothing, even next to a good one
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"changes\":[{\"typeCode\":\"NEW_POST\",\"channel\":\"PUSH\",\"enabled\":false},"
					+ "{\"typeCode\":\"CONSENT_REQUESTED\",\"channel\":\"PUSH\",\"enabled\":false}]}"))
			.andExpect(status().isBadRequest());
		assertThat(this.jdbc.queryForObject("select count(*) from notification_preferences where user_id = ? and type_code = 'NEW_POST'",
				Integer.class, user.userId())).isZero();
	}

	@Test
	void unknownTypesAndChannelsAreRejected() throws Exception {
		Persona user = this.personas.newUser("prefs-unknown");
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("NOPE", "PUSH", false)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.type_unknown"));
		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(change("NEW_POST", "SMS", false)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.channel_unsupported"));
	}

	@Test
	void quietHoursRoundTripAndValidation() throws Exception {
		Persona user = this.personas.newUser("settings");
		this.mvc.perform(get("/api/v1/me/notification-settings").with(user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quietHoursStart").doesNotExist())
			.andExpect(jsonPath("$.digestMode").value("OFF"))
			.andExpect(jsonPath("$.timezone").value("Africa/Addis_Ababa"));

		this.mvc.perform(put("/api/v1/me/notification-settings").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"quietHoursStart\":\"22:00\",\"quietHoursEnd\":\"06:30\",\"digestMode\":\"OFF\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quietHoursStart").value("22:00"))
			.andExpect(jsonPath("$.quietHoursEnd").value("06:30"));
		this.mvc.perform(get("/api/v1/me/notification-settings").with(user.bearer()))
			.andExpect(jsonPath("$.quietHoursEnd").value("06:30"));

		// one time alone, a bad time, an unsupported digest mode
		this.mvc.perform(put("/api/v1/me/notification-settings").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"quietHoursStart\":\"22:00\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.quiet_hours_incomplete"));
		this.mvc.perform(put("/api/v1/me/notification-settings").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"quietHoursStart\":\"25:00\",\"quietHoursEnd\":\"06:00\"}"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(put("/api/v1/me/notification-settings").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"digestMode\":\"DAILY\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.digest_unsupported"));

		// clearing
		this.mvc.perform(put("/api/v1/me/notification-settings").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quietHoursStart").doesNotExist());
	}

}
