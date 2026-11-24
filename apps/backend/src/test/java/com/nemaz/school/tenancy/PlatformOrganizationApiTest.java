package com.nemaz.school.tenancy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class PlatformOrganizationApiTest extends ApiTestSupport {

	private static final String URL = "/api/v1/platform/organizations";

	@Test
	void anonymousGets401AndOtherAuthoritiesGet403() throws Exception {
		UUID id = newOrganization("ET");
		httpGet(URL, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
		httpGet(URL + "/" + id, null).andExpect(status().isUnauthorized());
		httpPost(URL, null, orgBody("ET", uniqueSlug("o"))).andExpect(status().isUnauthorized());
		httpPut(URL + "/" + id, null, Map.of("name", "x", "status", "ACTIVE")).andExpect(status().isUnauthorized());

		for (var other : List.of(NOBODY, SCHOOLS, SUBSCRIPTIONS, FEATURES, SETTINGS)) {
			httpGet(URL, other).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
			httpGet(URL + "/" + id, other).andExpect(status().isForbidden());
			httpPost(URL, other, orgBody("ET", uniqueSlug("o"))).andExpect(status().isForbidden());
			httpPut(URL + "/" + id, other, Map.of("name", "x", "status", "ACTIVE")).andExpect(status().isForbidden());
		}
	}

	@Test
	void createGetAndUpdate() throws Exception {
		String slug = uniqueSlug("acme");
		JsonNode created = read(httpPost(URL, ORGANIZATIONS,
				Map.of("countryCode", "et", "name", "Acme Schools", "legalName", "Acme PLC", "slug", slug))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.countryCode").value("ET"))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.createdAt").isString()));
		String id = created.get("id").asString();
		assertThat(UUID.fromString(id).version()).isEqualTo(7);

		httpGet(URL + "/" + id, ORGANIZATIONS).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Acme Schools"))
			.andExpect(jsonPath("$.legalName").value("Acme PLC"));

		httpPut(URL + "/" + id, ORGANIZATIONS, Map.of("name", "Acme Group", "status", "SUSPENDED")).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Acme Group"))
			.andExpect(jsonPath("$.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	void validationErrors() throws Exception {
		httpPost(URL, ORGANIZATIONS, orgBody("ET", "Bad Slug")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"))
			.andExpect(jsonPath("$.errors[0].field").value("slug"))
			.andExpect(jsonPath("$.errors[0].message").value("validation.slug"));
		httpPost(URL, ORGANIZATIONS, orgBody("ET", "-leading")).andExpect(status().isBadRequest());
		httpPost(URL, ORGANIZATIONS, orgBody("ET", "UPPER")).andExpect(status().isBadRequest());
		httpPost(URL, ORGANIZATIONS, orgBody("ET", "a".repeat(64))).andExpect(status().isBadRequest());
		httpPost(URL, ORGANIZATIONS, Map.of("countryCode", "ETH", "name", "x", "slug", "okay")).andExpect(status().isBadRequest());
		httpPost(URL, ORGANIZATIONS, Map.of("countryCode", "ET", "name", " ", "slug", "okay")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[0].message").value("validation.required"));
		httpPost(URL, ORGANIZATIONS, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(3)));
		httpPut(URL + "/" + newOrganization("ET"), ORGANIZATIONS, Map.of("name", "x", "status", "NOPE"))
			.andExpect(status().isBadRequest());
		httpPut(URL + "/" + newOrganization("ET"), ORGANIZATIONS, Map.of("name", "x")).andExpect(status().isBadRequest());
		httpGet(URL + "?limit=5&status=NOPE", ORGANIZATIONS).andExpect(status().isBadRequest());
		httpGet(URL + "?cursor=%%%", ORGANIZATIONS).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_cursor"));
	}

	@Test
	void notFoundAndConflicts() throws Exception {
		UUID missing = UUID.randomUUID();
		httpGet(URL + "/" + missing, ORGANIZATIONS).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("organization.not_found"));
		httpPut(URL + "/" + missing, ORGANIZATIONS, Map.of("name", "x", "status", "ACTIVE")).andExpect(status().isNotFound());

		String slug = uniqueSlug("dup");
		httpPost(URL, ORGANIZATIONS, orgBody("ET", slug)).andExpect(status().isCreated());
		httpPost(URL, ORGANIZATIONS, orgBody("ET", slug)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("organization.slug_taken"))
			.andExpect(jsonPath("$.detail").value("This organization identifier is already taken."));
		httpPost(URL, ORGANIZATIONS, orgBody("ET", slug.toUpperCase())).andExpect(status().isBadRequest());

		httpPost(URL, ORGANIZATIONS, orgBody("KE", uniqueSlug("ke"))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("country.not_enabled"));
		httpPost(URL, ORGANIZATIONS, orgBody("ZZ", uniqueSlug("zz"))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("country.unknown"));
	}

	@Test
	void errorsAreLocalised() throws Exception {
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL + "/" + UUID.randomUUID())
			.with(ORGANIZATIONS)
			.header("Accept-Language", "am"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("ድርጅቱ አልተገኘም።"));
	}

	@Test
	void listsNewestFirstWithKeysetPaginationAndFilters() throws Exception {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ids.add(read(httpPost(URL, ORGANIZATIONS, orgBody("ET", uniqueSlug("page"))).andExpect(status().isCreated()))
				.get("id").asString());
		}
		Set<String> seen = new HashSet<>();
		List<String> order = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = read(httpGet(URL + "?countryCode=et&status=ACTIVE&limit=2" + ((cursor != null) ? "&cursor=" + cursor : ""),
					ORGANIZATIONS)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray()));
			assertThat(page.get("items").size()).isLessThanOrEqualTo(2);
			for (JsonNode item : page.get("items")) {
				assertThat(seen.add(item.get("id").asString())).as("no duplicates across pages").isTrue();
				order.add(item.get("id").asString());
			}
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
		}
		while (cursor != null && pages < 100);
		assertThat(seen).containsAll(ids);
		// newest first: the creation order reversed (UUIDv7 ids)
		List<String> ours = order.stream().filter(ids::contains).toList();
		assertThat(ours).containsExactlyElementsOf(ids.reversed());
		httpGet(URL + "?countryCode=ZZ", ORGANIZATIONS).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
	}

}
