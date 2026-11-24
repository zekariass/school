package com.nemaz.school.tenancy;

import java.util.Map;

import com.nemaz.school.common.security.Permissions;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** HTTP helpers for the platform-admin API tests. */
public abstract class ApiTestSupport extends TenancyTestSupport {

	protected static final RequestPostProcessor ORGANIZATIONS = as(Permissions.PLATFORM_ORGANIZATIONS_MANAGE);

	protected static final RequestPostProcessor SCHOOLS = as(Permissions.PLATFORM_SCHOOLS_MANAGE);

	protected static final RequestPostProcessor SUBSCRIPTIONS = as(Permissions.PLATFORM_SUBSCRIPTIONS_MANAGE);

	protected static final RequestPostProcessor FEATURES = as(Permissions.PLATFORM_FEATURES_MANAGE);

	protected static final RequestPostProcessor SETTINGS = as(Permissions.PLATFORM_SETTINGS_MANAGE);

	/** Signed in, but with no permission at all. */
	protected static final RequestPostProcessor NOBODY = as();

	@Autowired
	protected MockMvc mvc;

	protected static RequestPostProcessor as(String... authorities) {
		return SecurityMockMvcRequestPostProcessors.user("tester").authorities(AuthorityUtils.createAuthorityList(authorities));
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
