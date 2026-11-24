package com.nemaz.school.tenancy;

import java.util.ArrayList;
import java.util.List;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lists are filtered on the server to the caller's scopes (spec §100). */
@IntegrationTest
class ScopedAccessTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	Personas personas;

	private List<JsonNode> all(String url, Persona persona) throws Exception {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			String page = url + (url.contains("?") ? "&" : "?") + "limit=100" + ((cursor != null) ? "&cursor=" + cursor : "");
			JsonNode body = this.json.readTree(this.mvc.perform(get(page).with(persona.bearer()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());
			body.get("items").forEach(items::add);
			cursor = body.has("nextCursor") ? body.get("nextCursor").asString() : null;
		}
		while (cursor != null);
		return items;
	}

	private List<String> ids(List<JsonNode> items) {
		return items.stream().map((i) -> i.get("id").asString()).toList();
	}

	@Test
	void schoolListsShowOnlyTheCallersSchools() throws Exception {
		Fixture f = this.personas.fixture();
		String url = "/api/v1/platform/schools";
		List<JsonNode> kenya = all(url, this.personas.get(Name.COUNTRY_ADMIN_KE));
		assertThat(kenya).isNotEmpty().allSatisfy((s) -> assertThat(s.get("countryCode").asString()).isEqualTo("KE"));
		assertThat(ids(kenya)).contains(f.schoolKe().toString());

		List<JsonNode> ethiopia = all(url, this.personas.get(Name.COUNTRY_ADMIN_ET));
		assertThat(ethiopia).allSatisfy((s) -> assertThat(s.get("countryCode").asString()).isEqualTo("ET"));
		assertThat(ids(ethiopia)).contains(f.schoolA().toString(), f.schoolB().toString());

		assertThat(all(url, this.personas.get(Name.ORG_ADMIN)))
			.allSatisfy((s) -> assertThat(s.get("organizationId").asString()).isEqualTo(f.organization().toString()));
		assertThat(ids(all(url, this.personas.get(Name.SCHOOL_ADMIN_A)))).containsExactly(f.schoolA().toString());
		assertThat(ids(all(url, this.personas.get(Name.CAMPUS_ADMIN_A)))).containsExactly(f.schoolA().toString());
		// filters narrow within the caller's scope, never beyond it
		assertThat(all(url + "?countryCode=ET", this.personas.get(Name.COUNTRY_ADMIN_KE))).isEmpty();
		this.mvc.perform(get(url).with(this.personas.plainUser().bearer())).andExpect(status().isForbidden());
	}

	@Test
	void organizationListsShowOnlyTheCallersCountries() throws Exception {
		Fixture f = this.personas.fixture();
		String url = "/api/v1/platform/organizations";
		assertThat(ids(all(url, this.personas.get(Name.COUNTRY_ADMIN_ET)))).contains(f.organization().toString());
		assertThat(all(url, this.personas.get(Name.COUNTRY_ADMIN_KE)))
			.allSatisfy((o) -> assertThat(o.get("countryCode").asString()).isEqualTo("KE"));
		assertThat(ids(all(url, this.personas.get(Name.COUNTRY_ADMIN_KE)))).doesNotContain(f.organization().toString());
	}

}
