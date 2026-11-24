package com.nemaz.school.system.openapi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The committed OpenAPI snapshot (packages/api-client/openapi/openapi.json) must match
 * what the backend serves: the web and mobile clients are generated from it.
 *
 * After changing the API, refresh it with {@code npm run openapi:export -w school-backend}
 * (writes the snapshot), then {@code npm run codegen} (regenerates TypeScript types).
 */
@IntegrationTest
class OpenApiSpecTest {

	static final Path SNAPSHOT = Path.of("../../packages/api-client/openapi/openapi.json");

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Test
	@WithMockUser // api docs are not public outside local dev
	void committedSnapshotMatchesServedSpec() throws Exception {
		String served = this.mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		String rendered = render(this.json.readTree(served));

		if (Boolean.getBoolean("openapi.write")) {
			Files.writeString(SNAPSHOT, rendered);
		}

		assertThat(Files.exists(SNAPSHOT)).as("snapshot %s exists", SNAPSHOT.toAbsolutePath()).isTrue();
		assertThat(Files.readString(SNAPSHOT))
			.as("OpenAPI snapshot is stale: run `npm run openapi:export -w school-backend`, then `npm run codegen`")
			.isEqualTo(rendered);
	}

	private String render(JsonNode spec) {
		return this.json.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
	}

}
