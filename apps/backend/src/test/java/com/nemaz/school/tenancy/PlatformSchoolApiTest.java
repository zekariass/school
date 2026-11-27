package com.nemaz.school.tenancy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.support.CountryPolicies;
import com.nemaz.school.IntegrationTest;
import com.nemaz.school.subscriptions.SubscriptionAssigned;
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
class PlatformSchoolApiTest extends ApiTestSupport {

	private static final String URL = "/api/v1/platform/schools";

	@Autowired
	ApplicationEvents events;

	private Map<String, Object> schoolBody(String slug) {
		Map<String, Object> body = new HashMap<>();
		body.put("countryCode", "ET");
		body.put("name", "Bole Academy");
		body.put("slug", slug);
		return body;
	}

	private String createSchool() throws Exception {
		return read(httpPost(URL, SCHOOLS, schoolBody(uniqueSlug("s"))).andExpect(status().isCreated())).get("id").asString();
	}

	@Test
	void everyEndpointDeniesAnonymousAndOtherAuthorities() throws Exception {
		String id = createSchool();
		String campus = read(httpPost(URL + "/" + id + "/campuses", SCHOOLS, Map.of("name", "Main")).andExpect(status().isCreated()))
			.get("id").asString();
		Map<String, Object> assign = Map.of("planCode", "SCHOOL_BASIC");
		for (var auth : java.util.Arrays.asList(null, NOBODY, ORGANIZATIONS, FEATURES, SETTINGS)) {
			int expected = (auth == null) ? 401 : 403;
			httpGet(URL, auth).andExpect(status().is(expected));
			httpPost(URL, auth, schoolBody(uniqueSlug("x"))).andExpect(status().is(expected));
			httpGet(URL + "/" + id, auth).andExpect(status().is(expected));
			httpPut(URL + "/" + id + "/status", auth, Map.of("status", "ACTIVE")).andExpect(status().is(expected));
			httpGet(URL + "/" + id + "/campuses", auth).andExpect(status().is(expected));
			httpPost(URL + "/" + id + "/campuses", auth, Map.of("name", "x")).andExpect(status().is(expected));
			httpPut(URL + "/" + id + "/campuses/" + campus, auth, Map.of("name", "x", "status", "ACTIVE"))
				.andExpect(status().is(expected));
			httpPut(URL + "/" + id + "/subscription", auth, assign).andExpect(status().is(expected));
		}
		// schools.manage does not grant subscription assignment, and the reverse
		httpPut(URL + "/" + id + "/subscription", SCHOOLS, assign).andExpect(status().isForbidden());
		httpGet(URL + "/" + id, SUBSCRIPTIONS).andExpect(status().isForbidden());
		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, assign).andExpect(status().isOk());
	}

	@Test
	void createInheritsCountryDefaultsAndRecordsLanguages() throws Exception {
		String slug = uniqueSlug("bole");
		httpPost(URL, SCHOOLS, schoolBody(slug)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.countryCode").value("ET"))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.status").value("ONBOARDING"))
			.andExpect(jsonPath("$.defaultLanguageCode").value("am"))
			.andExpect(jsonPath("$.timezone").value("Africa/Addis_Ababa"))
			.andExpect(jsonPath("$.defaultCalendarCode").value("ETHIOPIAN"))
			.andExpect(jsonPath("$.currencyCode").value("ETB"))
			.andExpect(jsonPath("$.languages", hasSize(2)))
			.andExpect(jsonPath("$.languages[0].code").value("am"))
			.andExpect(jsonPath("$.languages[0].isDefault").value(true))
			.andExpect(jsonPath("$.languages[1].code").value("en"))
			.andExpect(jsonPath("$.languages[1].isDefault").value(false))
			.andExpect(jsonPath("$.organizationId").doesNotExist());
		assertThat(this.events.stream(SchoolCreated.class).filter((e) -> e.slug().equals(slug))).hasSize(1);
	}

	@Test
	void createWithExplicitProfile() throws Exception {
		UUID org = newOrganization("ET");
		Map<String, Object> body = schoolBody(uniqueSlug("explicit"));
		body.putAll(Map.of("organizationId", org.toString(), "shortName", "Bole", "schoolType", "K12",
				"ownershipType", "PRIVATE", "defaultLanguageCode", "en", "timezone", "Africa/Nairobi",
				"defaultCalendarCode", "GREGORIAN", "currencyCode", "USD"));
		body.putAll(Map.of("phoneE164", "+251911223344", "email", "Info@Bole.example", "website",
				"https://bole.example", "brandColor", "#1A2B3C", "languageCodes", List.of("am", "om")));
		httpPost(URL, SCHOOLS, body).andExpect(status().isCreated())
			.andExpect(jsonPath("$.organizationId").value(org.toString()))
			.andExpect(jsonPath("$.schoolType").value("K12"))
			.andExpect(jsonPath("$.ownershipType").value("PRIVATE"))
			.andExpect(jsonPath("$.defaultLanguageCode").value("en"))
			.andExpect(jsonPath("$.timezone").value("Africa/Nairobi"))
			.andExpect(jsonPath("$.defaultCalendarCode").value("GREGORIAN"))
			.andExpect(jsonPath("$.currencyCode").value("USD"))
			.andExpect(jsonPath("$.phoneE164").value("+251911223344"))
			.andExpect(jsonPath("$.brandColor").value("#1A2B3C"))
			// the default language is always one of the school's languages
			.andExpect(jsonPath("$.languages", hasSize(3)))
			.andExpect(jsonPath("$.languages[?(@.isDefault == true)].code").value("en"));
	}

	@Test
	void createValidatesCountryAndReferenceData() throws Exception {
		Map<String, Object> body = schoolBody(uniqueSlug("v"));
		httpPost(URL, SCHOOLS, withCountry(body, "KE")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("country.not_enabled"));
		httpPost(URL, SCHOOLS, withCountry(body, "ZZ")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("country.unknown"));
		httpPost(URL, SCHOOLS, with(body, "defaultLanguageCode", "sw")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("school.default_language_not_in_country"));
		httpPost(URL, SCHOOLS, with(body, "defaultLanguageCode", "xx")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("language.unknown"));
		httpPost(URL, SCHOOLS, with(body, "languageCodes", List.of("sw"))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("school.language_not_in_country"));
		httpPost(URL, SCHOOLS, with(body, "currencyCode", "XXX")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("currency.unknown"));
		httpPost(URL, SCHOOLS, with(body, "defaultCalendarCode", "MAYAN")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("calendar.unknown"));
		httpPost(URL, SCHOOLS, with(body, "timezone", "Mars/Olympus")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("timezone.invalid"));
		httpPost(URL, SCHOOLS, with(body, "organizationId", UUID.randomUUID().toString())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("organization.unknown"));
	}

	@Test
	void organizationMustBelongToTheSchoolsCountry() throws Exception {
		CountryPolicies.enable(this.jdbc, "KE");
		try {
			UUID kenyanOrg = newOrganization("KE");
			httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("mismatch")), "organizationId", kenyanOrg.toString()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("organization.country_mismatch"));
			// and a Kenyan school gets Kenya's defaults once the country is enabled
			httpPost(URL, SCHOOLS, with(withCountry(schoolBody(uniqueSlug("ke-school")), "KE"), "organizationId", kenyanOrg.toString()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.defaultLanguageCode").value("en"))
				.andExpect(jsonPath("$.defaultCalendarCode").value("GREGORIAN"))
				.andExpect(jsonPath("$.currencyCode").value("KES"))
				.andExpect(jsonPath("$.timezone").value("Africa/Nairobi"))
				.andExpect(jsonPath("$.languages", hasSize(1)));
		}
		finally {
			CountryPolicies.disable(this.jdbc, "KE");
		}
	}

	@Test
	void slugRulesAndConflicts() throws Exception {
		for (String bad : List.of("Has Space", "UPPER", "-lead", "trail-", "double--dash", "under_score", "a".repeat(64), "")) {
			httpPost(URL, SCHOOLS, with(schoolBody("x"), "slug", bad)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_failed"));
		}
		httpPost(URL, SCHOOLS, with(schoolBody("x"), "slug", "a".repeat(63))).andExpect(status().isCreated());
		String slug = uniqueSlug("taken");
		httpPost(URL, SCHOOLS, schoolBody(slug)).andExpect(status().isCreated());
		httpPost(URL, SCHOOLS, schoolBody(slug)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school.slug_taken"))
			.andExpect(jsonPath("$.detail").value("This school identifier is already taken."));
		// failed creations leave nothing behind
		assertThat(this.jdbc.queryForObject("select count(*) from schools where slug = ?", Integer.class, slug)).isEqualTo(1);
	}

	@Test
	void requestValidation() throws Exception {
		httpPost(URL, SCHOOLS, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(3)));
		httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("v")), "email", "not-an-email")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("email"));
		httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("v")), "phoneE164", "0911")).andExpect(status().isBadRequest());
		httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("v")), "brandColor", "red")).andExpect(status().isBadRequest());
		httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("v")), "schoolType", "ACADEMY")).andExpect(status().isBadRequest());
		httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("v")), "website", "javascript:alert(1)")).andExpect(status().isBadRequest());
		httpPut(URL + "/" + createSchool() + "/status", SCHOOLS, Map.of("status", "DELETED")).andExpect(status().isBadRequest());
		httpPut(URL + "/" + createSchool() + "/status", SCHOOLS, "{}").andExpect(status().isBadRequest());
	}

	@Test
	void getAndNotFound() throws Exception {
		String id = createSchool();
		httpGet(URL + "/" + id, SCHOOLS).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		UUID missing = UUID.randomUUID();
		httpGet(URL + "/" + missing, SCHOOLS).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("school.not_found"));
		httpPut(URL + "/" + missing + "/status", SCHOOLS, Map.of("status", "ACTIVE")).andExpect(status().isNotFound());
		httpGet(URL + "/" + missing + "/campuses", SCHOOLS).andExpect(status().isNotFound());
		httpPost(URL + "/" + missing + "/campuses", SCHOOLS, Map.of("name", "x")).andExpect(status().isNotFound());
		httpPut(URL + "/" + missing + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("school.not_found"));
		httpGet(URL + "/not-a-uuid", SCHOOLS).andExpect(status().isBadRequest());
	}

	@Test
	void statusTransitionsPublishEvents() throws Exception {
		String id = createSchool();
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "ACTIVE")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.version").value(1));
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "SUSPENDED")).andExpect(status().isOk());
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "SUSPENDED")).andExpect(status().isOk()); // no-op
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "ONBOARDING")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school.invalid_status_transition"));
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "ARCHIVED")).andExpect(status().isOk());
		httpPut(URL + "/" + id + "/status", SCHOOLS, Map.of("status", "ACTIVE")).andExpect(status().isConflict());

		assertThat(this.events.stream(SchoolStatusChanged.class).filter((e) -> e.schoolId().toString().equals(id)))
			.extracting((e) -> e.previousStatus() + ">" + e.newStatus())
			.containsExactly("ONBOARDING>ACTIVE", "ACTIVE>SUSPENDED", "SUSPENDED>ARCHIVED");
	}

	@Test
	void listFiltersAndPaginates() throws Exception {
		UUID orgA = newOrganization("ET");
		UUID orgB = newOrganization("ET");
		List<String> inA = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			inA.add(read(httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("a")), "organizationId", orgA.toString()))
				.andExpect(status().isCreated())).get("id").asString());
		}
		String inB = read(httpPost(URL, SCHOOLS, with(schoolBody(uniqueSlug("b")), "organizationId", orgB.toString()))
			.andExpect(status().isCreated())).get("id").asString();
		httpPut(URL + "/" + inA.get(0) + "/status", SCHOOLS, Map.of("status", "ACTIVE")).andExpect(status().isOk());

		List<String> collected = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = read(httpGet(URL + "?organizationId=" + orgA + "&limit=2" + ((cursor != null) ? "&cursor=" + cursor : ""), SCHOOLS)
				.andExpect(status().isOk()));
			page.get("items").forEach((item) -> collected.add(item.get("id").asString()));
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
		}
		while (cursor != null && pages < 10);
		assertThat(pages).isEqualTo(3);
		assertThat(collected).containsExactlyElementsOf(inA.reversed()); // newest first, no gaps or repeats

		httpGet(URL + "?organizationId=" + orgA + "&status=ACTIVE", SCHOOLS).andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(inA.get(0)))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		httpGet(URL + "?organizationId=" + orgB + "&countryCode=et", SCHOOLS).andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(inB))
			.andExpect(jsonPath("$.items[0].languages", hasSize(2)));
		httpGet(URL + "?organizationId=" + orgB + "&countryCode=KE", SCHOOLS).andExpect(jsonPath("$.items", hasSize(0)));
		httpGet(URL + "?organizationId=" + orgB + "&limit=1000", SCHOOLS).andExpect(status().isOk());
	}

	@Test
	void campuses() throws Exception {
		String school = createSchool();
		String other = createSchool();
		String base = URL + "/" + school + "/campuses";
		String main = read(httpPost(base, SCHOOLS, Map.of("name", "Main campus", "code", "MAIN"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.schoolId").value(school))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.timezone").doesNotExist())).get("id").asString();
		httpPost(base, SCHOOLS, Map.of("name", "North", "code", "N", "timezone", "Africa/Nairobi")).andExpect(status().isCreated());
		httpPost(base, SCHOOLS, Map.of("name", "No code")).andExpect(status().isCreated());
		httpPost(base, SCHOOLS, Map.of("name", "Another no code")).andExpect(status().isCreated()); // null codes never clash

		httpPost(base, SCHOOLS, Map.of("name", "Dup", "code", "MAIN")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("campus.code_taken"));
		httpPost(URL + "/" + other + "/campuses", SCHOOLS, Map.of("name", "Same code elsewhere", "code", "MAIN"))
			.andExpect(status().isCreated());
		httpPost(base, SCHOOLS, Map.of("name", " ")).andExpect(status().isBadRequest());
		httpPost(base, SCHOOLS, Map.of("name", "Tz", "timezone", "Nowhere/City")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("timezone.invalid"));

		httpPut(base + "/" + main, SCHOOLS, Map.of("name", "Main (renamed)", "code", "MAIN", "status", "ARCHIVED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Main (renamed)"))
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
		httpPut(base + "/" + main, SCHOOLS, Map.of("name", "x", "code", "N", "status", "ACTIVE")).andExpect(status().isConflict());
		httpPut(base + "/" + main, SCHOOLS, Map.of("name", "x")).andExpect(status().isBadRequest());
		// a campus is only reachable through its own school
		httpPut(URL + "/" + other + "/campuses/" + main, SCHOOLS, Map.of("name", "x", "status", "ACTIVE"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("campus.not_found"));
		httpPut(base + "/" + UUID.randomUUID(), SCHOOLS, Map.of("name", "x", "status", "ACTIVE")).andExpect(status().isNotFound());

		JsonNode first = read(httpGet(base + "?limit=3", SCHOOLS).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(3))));
		JsonNode second = read(httpGet(base + "?limit=3&cursor=" + first.get("nextCursor").asString(), SCHOOLS)
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.nextCursor").doesNotExist()));
		assertThat(first.get("items").get(0).get("name").asString()).isEqualTo("Main (renamed)"); // creation order
		assertThat(second.get("items").get(0).get("name").asString()).isEqualTo("Another no code");
	}

	@Test
	void assigningASubscriptionChangesThePlanAndPublishesAnEvent() throws Exception {
		String id = createSchool();
		httpGet(URL + "/" + id + "/configuration", SCHOOLS).andExpect(jsonPath("$.planCode").value("SCHOOL_FREE"))
			.andExpect(jsonPath("$.planIsFreeFallback").value(true));

		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_STANDARD")).andExpect(status().isOk())
			.andExpect(jsonPath("$.planCode").value("SCHOOL_STANDARD"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.schoolId").value(id))
			.andExpect(jsonPath("$.currentPeriodStart").isString());
		httpGet(URL + "/" + id + "/configuration", SCHOOLS).andExpect(jsonPath("$.planCode").value("SCHOOL_STANDARD"))
			.andExpect(jsonPath("$.planIsFreeFallback").value(false));

		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS,
				Map.of("planCode", "SCHOOL_PREMIUM", "status", "TRIALING", "currentPeriodEnd", "2099-01-01T00:00:00Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("TRIALING"))
			.andExpect(jsonPath("$.trialEndsAt").value("2099-01-01T00:00:00Z"));
		assertThat(this.jdbc.queryForList("select status from subscriptions where school_id = ?::uuid order by created_at", String.class, id))
			.containsExactly("CANCELLED", "TRIALING");
		assertThat(this.events.stream(SubscriptionAssigned.class).filter((e) -> e.schoolId().toString().equals(id)))
			.extracting(SubscriptionAssigned::planCode)
			.containsExactly("SCHOOL_STANDARD", "SCHOOL_PREMIUM");

		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "NOPE")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("subscription.plan_not_found"));
		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC", "status", "CANCELLED"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("subscription.invalid_status"));
		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC", "currentPeriodEnd", "2001-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("subscription.invalid_period"));
		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", " ")).andExpect(status().isBadRequest());
		httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC", "status", "MAYBE"))
			.andExpect(status().isBadRequest());
		this.jdbc.update("update subscription_plans set audience = 'TUTOR' where code = 'SCHOOL_BASIC'");
		try {
			httpPut(URL + "/" + id + "/subscription", SUBSCRIPTIONS, Map.of("planCode", "SCHOOL_BASIC")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("subscription.plan_not_applicable"));
		}
		finally {
			this.jdbc.update("update subscription_plans set audience = 'SCHOOL' where code = 'SCHOOL_BASIC'");
		}
	}

	private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
		Map<String, Object> copy = new HashMap<>(body);
		copy.put(key, value);
		return copy;
	}

	private static Map<String, Object> withCountry(Map<String, Object> body, String country) {
		return with(body, "countryCode", country);
	}

}
