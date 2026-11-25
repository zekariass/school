package com.nemaz.school.support;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Helpers for the school-structure tests: JSON calls as a persona with the expected status checked. */
public abstract class SchoolApiTestSupport extends AuthTestSupport {

	protected static String school(UUID schoolId) {
		return "/api/v1/schools/" + schoolId;
	}

	protected ResultActions perform(HttpMethod method, String url, Persona as, Object body) throws Exception {
		MockHttpServletRequestBuilder builder = request(method, url);
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
		}
		if (as != null) {
			builder = builder.with(as.bearer());
		}
		return this.mvc.perform(builder);
	}

	/** Calls and asserts the status; returns the parsed body (null when empty). */
	protected JsonNode call(HttpMethod method, String url, Persona as, Object body, int status) throws Exception {
		ResultActions result = perform(method, url, as, body);
		var response = result.andReturn().getResponse();
		assertThat(response.getStatus()).as(method + " " + url + " -> " + response.getContentAsString())
			.isEqualTo(status);
		String content = response.getContentAsString();
		return content.isBlank() ? null : this.json.readTree(content);
	}

	protected JsonNode get(String url, Persona as) throws Exception {
		return call(HttpMethod.GET, url, as, null, 200);
	}

	protected JsonNode post(String url, Persona as, Object body, int status) throws Exception {
		return call(HttpMethod.POST, url, as, body, status);
	}

	protected static UUID uuid(JsonNode node, String field) {
		return UUID.fromString(node.get(field).asString());
	}

	/** The problem-details code of an error response, from its type or code member. */
	protected String errorCode(JsonNode problem) {
		return problem.has("code") ? problem.get("code").asString() : problem.path("type").asString();
	}

}
