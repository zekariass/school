package com.nemaz.school.tenancy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@RecordApplicationEvents
class PlatformConfigurationApiTest extends ApiTestSupport {

	private static final String FEATURE_URL = "/api/v1/platform/feature-overrides";

	private static final String SETTING_URL = "/api/v1/platform/settings";

	@Autowired
	ApplicationEvents events;

	private static Map<String, Object> scope(String type, String key, Object value) {
		Map<String, Object> scope = new HashMap<>();
		scope.put("type", type);
		if (key != null) {
			scope.put(key, value);
		}
		return scope;
	}

	private static Map<String, Object> schoolScope(UUID school) {
		return scope("SCHOOL", "schoolId", school.toString());
	}

	private static String query(String type, String key, Object value) {
		return "scopeType=" + type + ((key != null) ? "&" + key + "=" + value : "");
	}

	// --- feature overrides ------------------------------------------------------------

	@Test
	void featureOverrideEndpointsRequireTheFeaturesAuthority() throws Exception {
		Map<String, Object> body = Map.of("featureKey", "feed", "scope", scope("PLATFORM", null, null), "enabled", false);
		for (var auth : java.util.Arrays.asList(null, NOBODY, SCHOOLS, SETTINGS, ORGANIZATIONS, SUBSCRIPTIONS)) {
			int expected = (auth == null) ? 401 : 403;
			httpGet(FEATURE_URL + "?" + query("PLATFORM", null, null), auth).andExpect(status().is(expected));
			httpPut(FEATURE_URL, auth, body).andExpect(status().is(expected));
			httpDelete(FEATURE_URL + "?featureKey=feed&" + query("PLATFORM", null, null), auth).andExpect(status().is(expected));
		}
	}

	@Test
	void featureOverrideLifecycle() throws Exception {
		UUID school = newSchool();
		Map<String, Object> body = new HashMap<>(Map.of("featureKey", "attendance", "scope", schoolScope(school), "enabled", false));
		body.put("config", Map.of("mode", "PER_PERIOD"));

		httpPut(FEATURE_URL, FEATURES, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.featureKey").value("attendance"))
			.andExpect(jsonPath("$.enabled").value(false))
			.andExpect(jsonPath("$.scope.type").value("SCHOOL"))
			.andExpect(jsonPath("$.scope.schoolId").value(school.toString()))
			.andExpect(jsonPath("$.config.mode").value("PER_PERIOD"));
		httpGet("/api/v1/platform/schools/" + school + "/configuration", FEATURES).andExpect(status().isOk())
			.andExpect(jsonPath("$.features[?(@.key == 'attendance')].enabled").value(false))
			.andExpect(jsonPath("$.features[?(@.key == 'attendance')].flagSource").value("SCHOOL"))
			.andExpect(jsonPath("$.features[?(@.key == 'attendance')].config.mode").value("PER_PERIOD"));

		// replacing is an update, not a second row
		body.put("enabled", true);
		httpPut(FEATURE_URL, FEATURES, body).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
		httpGet(FEATURE_URL + "?" + query("SCHOOL", "schoolId", school), FEATURES).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].enabled").value(true));
		httpGet(FEATURE_URL + "?" + query("PLATFORM", null, null), FEATURES).andExpect(jsonPath("$", hasSize(0)));

		httpDelete(FEATURE_URL + "?featureKey=attendance&" + query("SCHOOL", "schoolId", school), FEATURES)
			.andExpect(status().isNoContent());
		httpDelete(FEATURE_URL + "?featureKey=attendance&" + query("SCHOOL", "schoolId", school), FEATURES)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("config.override_not_found"));
		assertThat(this.events.stream(ConfigurationChanged.class).filter((e) -> school.equals(e.schoolId()))).hasSize(3);
	}

	@Test
	void featureOverrideValidation() throws Exception {
		UUID school = newSchool();
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "nope", "scope", schoolScope(school), "enabled", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("feature.unknown_key"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", schoolScope(UUID.randomUUID()), "enabled", true))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("school.not_found"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", scope("COUNTRY", "countryCode", "ZZ"), "enabled", true))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("country.not_found"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", scope("ORGANIZATION", "organizationId", UUID.randomUUID().toString()),
				"enabled", true))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("organization.not_found"));
		// the scope's keys must match its type
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", scope("SCHOOL", "countryCode", "ET"), "enabled", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("scope.invalid"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", scope("PLATFORM", "schoolId", school.toString()), "enabled", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("scope.invalid"));
		httpGet(FEATURE_URL + "?scopeType=COUNTRY", FEATURES).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("scope.invalid"));
		httpGet(FEATURE_URL + "?scopeType=GALAXY", FEATURES).andExpect(status().isBadRequest());
		httpGet(FEATURE_URL, FEATURES).andExpect(status().isBadRequest());
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "enabled", true)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("scope"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", schoolScope(school))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("enabled"));
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "feed", "scope", schoolScope(school), "enabled", true, "config", "[1]"))
			.andExpect(status().isBadRequest());
	}

	// --- settings ---------------------------------------------------------------------

	@Test
	void settingEndpointsRequireTheSettingsAuthority() throws Exception {
		Map<String, Object> body = Map.of("key", "engagement.badges.enabled", "scope", scope("PLATFORM", null, null), "value", true);
		for (var auth : java.util.Arrays.asList(null, NOBODY, SCHOOLS, FEATURES, ORGANIZATIONS, SUBSCRIPTIONS)) {
			int expected = (auth == null) ? 401 : 403;
			httpGet(SETTING_URL + "?" + query("PLATFORM", null, null), auth).andExpect(status().is(expected));
			httpPut(SETTING_URL, auth, body).andExpect(status().is(expected));
			httpDelete(SETTING_URL + "?key=engagement.badges.enabled&" + query("PLATFORM", null, null), auth)
				.andExpect(status().is(expected));
		}
	}

	@Test
	void settingLifecycleAcrossScopes() throws Exception {
		UUID org = newOrganization("ET");
		UUID school = newSchool(org);
		String key = "feed.parent_contributions.enabled";

		httpPut(SETTING_URL, SETTINGS, Map.of("key", key, "scope", scope("ORGANIZATION", "organizationId", org.toString()), "value", true))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.key").value(key))
			.andExpect(jsonPath("$.value").value(true))
			.andExpect(jsonPath("$.scope.type").value("ORGANIZATION"));
		httpGet("/api/v1/platform/schools/" + school + "/configuration", SETTINGS)
			.andExpect(jsonPath("$.settings[?(@.key == '" + key + "')].value").value(true))
			.andExpect(jsonPath("$.settings[?(@.key == '" + key + "')].source").value("ORGANIZATION"))
			.andExpect(jsonPath("$.settings[?(@.key == 'engagement.needs_work.enabled')].source").value("DEFAULT"))
			.andExpect(jsonPath("$.settings[?(@.key == 'engagement.needs_work.enabled')].value").value(true));

		// school scope wins, and complex values round trip
		httpPut(SETTING_URL, SETTINGS, Map.of("key", key, "scope", schoolScope(school), "value", false)).andExpect(status().isOk());
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "notifications.quiet_hours", "scope", schoolScope(school),
				"value", Map.of("start", "22:00", "end", "05:30")))
			.andExpect(status().isOk());
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "feed.parent_contributions.allowed_media", "scope", schoolScope(school),
				"value", java.util.List.of("IMAGE", "VIDEO")))
			.andExpect(status().isOk());
		httpPut(SETTING_URL, SETTINGS, "{\"key\":\"messaging.student.min_age\",\"scope\":{\"type\":\"SCHOOL\",\"schoolId\":\"" + school
				+ "\"},\"value\":null}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.value").value((Object) null));
		httpGet("/api/v1/platform/schools/" + school + "/configuration", SETTINGS)
			.andExpect(jsonPath("$.settings[?(@.key == '" + key + "')].value").value(false))
			.andExpect(jsonPath("$.settings[?(@.key == '" + key + "')].source").value("SCHOOL"))
			.andExpect(jsonPath("$.settings[?(@.key == 'notifications.quiet_hours')].value.start").value("22:00"))
			.andExpect(jsonPath("$.settings[?(@.key == 'feed.parent_contributions.allowed_media')].value[1]").value("VIDEO"));

		JsonNode all = read(httpGet(SETTING_URL + "?" + query("SCHOOL", "schoolId", school), SETTINGS).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(4))));
		assertThat(all.findValuesAsString("key")).isSorted();
		httpGet(SETTING_URL + "?" + query("SCHOOL", "schoolId", school) + "&key=" + key, SETTINGS).andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].value").value(false));
		httpGet(SETTING_URL + "?" + query("ORGANIZATION", "organizationId", org), SETTINGS).andExpect(jsonPath("$", hasSize(1)));

		httpDelete(SETTING_URL + "?key=" + key + "&" + query("SCHOOL", "schoolId", school), SETTINGS).andExpect(status().isNoContent());
		httpGet("/api/v1/platform/schools/" + school + "/configuration", SETTINGS)
			.andExpect(jsonPath("$.settings[?(@.key == '" + key + "')].source").value("ORGANIZATION"));
		httpDelete(SETTING_URL + "?key=" + key + "&" + query("SCHOOL", "schoolId", school), SETTINGS).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("config.override_not_found"));
	}

	@Test
	void settingValidation() throws Exception {
		UUID school = newSchool();
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "no.such.key", "scope", schoolScope(school), "value", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("setting.unknown_key"));
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "engagement.badges.enabled", "scope", schoolScope(school), "value", "yes"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("setting.invalid_value"))
			.andExpect(jsonPath("$.detail").value("This value is not valid for this setting: $: type"));
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "messaging.student.teacher_direct.initiator", "scope", schoolScope(school), "value", "NOBODY"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("setting.invalid_value"));
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "notifications.quiet_hours", "scope", schoolScope(school), "value", Map.of("start", "9pm", "end", "06:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("This value is not valid for this setting: $.start: pattern"));
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "engagement.badges.enabled", "scope", schoolScope(UUID.randomUUID()), "value", true))
			.andExpect(status().isNotFound());
		httpPut(SETTING_URL, SETTINGS, Map.of("key", "engagement.badges.enabled", "scope", scope("COUNTRY", null, null), "value", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("scope.invalid"));
		httpPut(SETTING_URL, SETTINGS, "{\"key\":\"engagement.badges.enabled\",\"scope\":{\"type\":\"PLATFORM\"}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("value"));
		httpPut(SETTING_URL, SETTINGS, Map.of("key", " ", "scope", schoolScope(school), "value", true)).andExpect(status().isBadRequest());
		httpDelete(SETTING_URL + "?key=no.such.key&" + query("SCHOOL", "schoolId", school), SETTINGS).andExpect(status().isBadRequest());
		httpGet(SETTING_URL + "?" + query("SCHOOL", "schoolId", UUID.randomUUID()), SETTINGS).andExpect(status().isNotFound());
		httpDelete(SETTING_URL + "?" + query("SCHOOL", "schoolId", school), SETTINGS).andExpect(status().isBadRequest()); // key missing

		assertThat(this.jdbc.queryForObject("select count(*) from settings", Integer.class)).isZero();
	}

	@Test
	void settingErrorsAreLocalised() throws Exception {
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(SETTING_URL)
			.with(SETTINGS)
			.header("Accept-Language", "am")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(write(Map.of("key", "no.such.key", "scope", scope("PLATFORM", null, null), "value", true))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("ይህ ቅንብር አይታወቅም።"));
	}

	// --- resolved view ----------------------------------------------------------------

	@Test
	void resolvedViewRequiresAConfigurationAuthority() throws Exception {
		UUID school = newSchool();
		String url = "/api/v1/platform/schools/" + school + "/configuration";
		httpGet(url, null).andExpect(status().isUnauthorized());
		httpGet(url, NOBODY).andExpect(status().isForbidden());
		httpGet(url, ORGANIZATIONS).andExpect(status().isForbidden());
		httpGet(url, SUBSCRIPTIONS).andExpect(status().isForbidden());
		httpGet(url, SCHOOLS).andExpect(status().isOk());
		httpGet(url, FEATURES).andExpect(status().isOk());
		httpGet(url, SETTINGS).andExpect(status().isOk());
		httpGet("/api/v1/platform/schools/" + UUID.randomUUID() + "/configuration", SCHOOLS).andExpect(status().isNotFound());
	}

	@Test
	void resolvedViewListsEveryDefinitionWithEntitlements() throws Exception {
		UUID school = newSchool();
		JsonNode view = read(httpGet("/api/v1/platform/schools/" + school + "/configuration", SCHOOLS).andExpect(status().isOk())
			.andExpect(jsonPath("$.schoolId").value(school.toString()))
			.andExpect(jsonPath("$.planCode").value("SCHOOL_FREE")));
		assertThat(view.get("features").size()).isEqualTo(this.jdbc.queryForObject("select count(*) from feature_definitions", Integer.class));
		assertThat(view.get("settings").size()).isEqualTo(this.jdbc.queryForObject("select count(*) from setting_definitions", Integer.class));
		JsonNode library = view.get("features").valueStream().filter((f) -> f.get("key").asString().equals("library")).findFirst().orElseThrow();
		assertThat(library.get("enabled").asBoolean()).isFalse();
		assertThat(library.get("flagEnabled").asBoolean()).isFalse();
		assertThat(library.get("entitled").asBoolean()).isFalse();
		JsonNode students = view.get("features").valueStream().filter((f) -> f.get("key").asString().equals("max_students")).findFirst().orElseThrow();
		assertThat(students.get("limit").asLong()).isEqualTo(100);

		// entitled by the plan but switched off by a flag, or switched on by a flag but not entitled
		httpPut(FEATURE_URL, FEATURES, Map.of("featureKey", "library", "scope", schoolScope(school), "enabled", true)).andExpect(status().isOk());
		httpGet("/api/v1/platform/schools/" + school + "/configuration", FEATURES)
			.andExpect(jsonPath("$.features[?(@.key == 'library')].enabled").value(false))
			.andExpect(jsonPath("$.features[?(@.key == 'library')].flagEnabled").value(true))
			.andExpect(jsonPath("$.features[?(@.key == 'library')].entitled").value(false));
		httpPut("/api/v1/platform/schools/" + school + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC")).andExpect(status().isOk());
		httpGet("/api/v1/platform/schools/" + school + "/configuration", FEATURES)
			.andExpect(jsonPath("$.features[?(@.key == 'library')].enabled").value(true))
			.andExpect(jsonPath("$.features[?(@.key == 'max_students')].limit").value(500));
	}

}
