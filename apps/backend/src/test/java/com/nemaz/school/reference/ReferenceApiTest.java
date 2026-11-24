package com.nemaz.school.reference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class ReferenceApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Test
	void everyReferenceEndpointRequiresSignIn() throws Exception {
		for (String url : List.of("/api/v1/reference/countries", "/api/v1/reference/languages",
				"/api/v1/reference/countries/ET/administrative-areas")) {
			this.mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
		}
	}

	@Test
	@WithMockUser
	void countriesListsOnlyEnabledCountriesWithEverythingAFormNeeds() throws Exception {
		this.mvc.perform(get("/api/v1/reference/countries"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].code").value("ET"))
			.andExpect(jsonPath("$[0].nameI18n.am").value("ኢትዮጵያ"))
			.andExpect(jsonPath("$[0].currency.code").value("ETB"))
			.andExpect(jsonPath("$[0].currency.minorUnits").value(2))
			.andExpect(jsonPath("$[0].defaultLanguageCode").value("am"))
			.andExpect(jsonPath("$[0].defaultTimezone").value("Africa/Addis_Ababa"))
			.andExpect(jsonPath("$[0].defaultCalendarCode").value("ETHIOPIAN"))
			.andExpect(jsonPath("$[0].phoneCountryCode").value("+251"))
			.andExpect(jsonPath("$[0].languages", hasSize(5)))
			.andExpect(jsonPath("$[0].languages[0].code").value("am"))
			.andExpect(jsonPath("$[0].languages[0].nativeName").value("አማርኛ"))
			.andExpect(jsonPath("$[0].languages[0].isDefault").value(true))
			.andExpect(jsonPath("$[0].languages[0].isOfficial").value(true))
			.andExpect(jsonPath("$[0].languages[1].code").value("en"))
			.andExpect(jsonPath("$[0].calendars", hasSize(2)))
			.andExpect(jsonPath("$[0].calendars[0].code").value("ETHIOPIAN"))
			.andExpect(jsonPath("$[0].calendars[0].isDefault").value(true))
			.andExpect(jsonPath("$[0].addressFormat.levels", hasSize(4)))
			.andExpect(jsonPath("$[0].addressFormat.levels[3].labels.en").value("Kebele"))
			.andExpect(jsonPath("$[0].nameFormat.middle_name.en").value("Father's name"))
			.andExpect(jsonPath("$[0].nameFormat.family_name.am").value("የአያት ስም"));
	}

	@Test
	@WithMockUser
	void draftCountriesAreNotListed() throws Exception {
		this.mvc.perform(get("/api/v1/reference/countries")).andExpect(jsonPath("$[?(@.code == 'KE')]").isEmpty());
	}

	@Test
	@WithMockUser
	void languages() throws Exception {
		this.mvc.perform(get("/api/v1/reference/languages"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(6)))
			.andExpect(jsonPath("$[0].code").value("am"))
			.andExpect(jsonPath("$[0].direction").value("LTR"))
			.andExpect(jsonPath("$[0].script").value("Ethi"))
			.andExpect(jsonPath("$[2].nativeName").value("Afaan Oromoo"));
	}

	@Test
	@WithMockUser
	void administrativeAreasByLevelWithKeysetPagination() throws Exception {
		String base = "/api/v1/reference/countries/ET/administrative-areas";
		JsonNode all = read(this.mvc.perform(get(base + "?level=1")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(14)))
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.items[0].nameI18n.en").value("Addis Ababa"))
			.andExpect(jsonPath("$.items[0].nameI18n.am").value("አዲስ አበባ"))
			.andExpect(jsonPath("$.items[0].level").value(1))
			.andExpect(jsonPath("$.items[0].countryCode").value("ET")));

		List<String> collected = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = read(this.mvc.perform(get(base + "?level=1&limit=5" + ((cursor != null) ? "&cursor=" + cursor : "")))
				.andExpect(status().isOk()));
			page.get("items").forEach((item) -> {
				collected.add(item.get("nameI18n").get("en").asString());
				assertThat(seen.add(item.get("id").asString())).isTrue();
			});
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
		}
		while (cursor != null && pages < 10);
		assertThat(pages).isEqualTo(3);
		assertThat(collected).containsExactlyElementsOf(all.get("items").valueStream().map((i) -> i.get("nameI18n").get("en").asString()).toList());

		// lower-case codes work, parent filter and other levels are empty until zones are imported
		this.mvc.perform(get("/api/v1/reference/countries/et/administrative-areas")).andExpect(jsonPath("$.items", hasSize(14)));
		this.mvc.perform(get(base + "?level=2")).andExpect(jsonPath("$.items", hasSize(0)));
		String regionId = all.get("items").get(0).get("id").asString();
		this.mvc.perform(get(base + "?parent=" + regionId)).andExpect(jsonPath("$.items", hasSize(0)));
		this.mvc.perform(get("/api/v1/reference/countries/KE/administrative-areas")).andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	@WithMockUser
	void administrativeAreaErrors() throws Exception {
		String base = "/api/v1/reference/countries/ET/administrative-areas";
		this.mvc.perform(get("/api/v1/reference/countries/ZZ/administrative-areas")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("country.not_found"));
		this.mvc.perform(get(base + "?level=9")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("reference.invalid_level"));
		this.mvc.perform(get(base + "?level=abc")).andExpect(status().isBadRequest());
		this.mvc.perform(get(base + "?parent=not-a-uuid")).andExpect(status().isBadRequest());
		this.mvc.perform(get(base + "?cursor=garbage")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_cursor"));
		ResultActions am = this.mvc.perform(get("/api/v1/reference/countries/ZZ/administrative-areas").header("Accept-Language", "am"));
		am.andExpect(jsonPath("$.detail").value("አገሩ አልተገኘም።"));
	}

	private JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

}
