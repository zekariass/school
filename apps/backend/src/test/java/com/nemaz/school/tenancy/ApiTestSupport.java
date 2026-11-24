package com.nemaz.school.tenancy;

import java.util.Map;

import java.util.function.Function;

import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * HTTP helpers for the platform-admin API tests. The callers are real users with real access
 * tokens (persona harness): each constant is a user whose test-only role holds exactly the
 * listed permissions at platform scope. Scope-limited personas (country, organization,
 * school admins) are exercised in {@code authorization.AuthorizationMatrixTest}.
 */
public abstract class ApiTestSupport extends TenancyTestSupport {

	protected static final RequestPostProcessor ORGANIZATIONS = as(Permissions.PLATFORM_ORGANIZATIONS_MANAGE);

	/** Create schools, change status, read schools and manage campuses. */
	protected static final RequestPostProcessor SCHOOLS = as(Permissions.PLATFORM_SCHOOLS_MANAGE,
			Permissions.SCHOOL_READ, Permissions.SCHOOL_MANAGE);

	protected static final RequestPostProcessor SUBSCRIPTIONS = as(Permissions.PLATFORM_SUBSCRIPTIONS_MANAGE);

	protected static final RequestPostProcessor FEATURES = as(Permissions.PLATFORM_FEATURES_MANAGE);

	protected static final RequestPostProcessor SETTINGS = as(Permissions.PLATFORM_SETTINGS_MANAGE);

	/** Signed in, but with no permission at all. */
	protected static final RequestPostProcessor NOBODY = as();

	@Autowired
	protected MockMvc mvc;

	/** A signed-in user holding exactly {@code permissions} platform-wide (resolved lazily, per request). */
	protected static RequestPostProcessor as(String... permissions) {
		return lazily((personas) -> personas.withPlatformPermissions(permissions));
	}

	protected static RequestPostProcessor lazily(Function<Personas, Persona> persona) {
		return (request) -> persona.apply(Personas.current()).bearer().postProcessRequest(request);
	}

	protected ResultActions httpGet(String url, RequestPostProcessor auth) throws Exception {
		return this.mvc.perform(withAuth(get(url), auth));
	}

	protected ResultActions httpPost(String url, RequestPostProcessor auth, Object body) throws Exception {
		return this.mvc.perform(withAuth(post(url), auth).contentType(MediaType.APPLICATION_JSON).content(write(body)));
	}

	protected ResultActions httpPut(String url, RequestPostProcessor auth, Object body) throws Exception {
		return this.mvc.perform(withAuth(put(url), auth).contentType(MediaType.APPLICATION_JSON).content(write(body)));
	}

	protected ResultActions httpDelete(String url, RequestPostProcessor auth) throws Exception {
		return this.mvc.perform(withAuth(delete(url), auth));
	}

	private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, RequestPostProcessor auth) {
		return (auth != null) ? builder.with(auth) : builder;
	}

	protected String write(Object body) {
		return (body instanceof String text) ? text : this.json.writeValueAsString(body);
	}

	protected JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	protected static Map<String, Object> orgBody(String countryCode, String slug) {
		return Map.of("countryCode", countryCode, "name", "Test organization", "slug", slug);
	}

}
