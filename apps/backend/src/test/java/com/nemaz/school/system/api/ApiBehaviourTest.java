package com.nemaz.school.system.api;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting HTTP behaviour: public meta endpoint, deny-by-default security, request
 * ids, and the RFC 9457 error contract (codes, localization, validation details).
 */
@IntegrationTest
@Import(ApiBehaviourTest.ErrorProbeConfig.class)
class ApiBehaviourTest {

	private static final String UUID_V7 = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

	@Autowired
	MockMvc mvc;

	@Test
	void metaIsPublicAndReportsServerTime() throws Exception {
		this.mvc.perform(get("/api/v1/meta"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.service").value("school-backend"))
			.andExpect(jsonPath("$.apiVersion").value("v1"))
			.andExpect(jsonPath("$.serverTime").isString())
			.andExpect(jsonPath("$.supportedLocales[1]").value("am"))
			.andExpect(header().string("X-Request-Id", matchesPattern(UUID_V7)));
	}

	@Test
	void healthIsPublic() throws Exception {
		this.mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void everythingElseRequiresAuthenticationWithProblemDetails() throws Exception {
		this.mvc.perform(get("/api/v1/classes").header("X-Request-Id", "client-req-12345"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("unauthorized"))
			.andExpect(jsonPath("$.type").value("urn:school:problem:unauthorized"))
			.andExpect(jsonPath("$.title").value("Sign-in required"))
			.andExpect(jsonPath("$.requestId").value("client-req-12345"))
			.andExpect(header().string("X-Request-Id", "client-req-12345"));
	}

	@Test
	void unsafeClientRequestIdsAreReplaced() throws Exception {
		this.mvc.perform(get("/api/v1/meta").header("X-Request-Id", "bad id\nwith newline"))
			.andExpect(header().string("X-Request-Id", matchesPattern(UUID_V7)));
	}

	@Test
	void errorsFollowAcceptLanguage() throws Exception {
		this.mvc.perform(get("/api/v1/classes").header("Accept-Language", "am-ET,am;q=0.9"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.title").value("መግባት ያስፈልጋል"))
			.andExpect(jsonPath("$.code").value("unauthorized"));

		this.mvc.perform(get("/api/v1/classes").header("Accept-Language", "fr-FR"))
			.andExpect(jsonPath("$.title").value("Sign-in required"));
	}

	@Test
	@WithMockUser
	void apiExceptionsBecomeProblemsWithTheirCode() throws Exception {
		this.mvc.perform(get("/test/errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("not_found"))
			.andExpect(jsonPath("$.detail").value("We couldn't find what you were looking for."))
			.andExpect(jsonPath("$.requestId").isString());
	}

	@Test
	@WithMockUser
	void validationErrorsListFields() throws Exception {
		this.mvc.perform(post("/test/errors/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[0].code").value("NotBlank"));
	}

	@Test
	@WithMockUser
	void unexpectedErrorsDoNotLeakInternals() throws Exception {
		this.mvc.perform(get("/test/errors/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("internal_error"))
			.andExpect(jsonPath("$.detail", not(matchesPattern(".*secret.*"))));
	}

	@Test
	@WithMockUser
	void accessDeniedInsideControllersIsForbidden() throws Exception {
		this.mvc.perform(get("/test/errors/denied"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("forbidden"));
	}

	@Test
	@WithMockUser
	void frameworkErrorsAreEnriched() throws Exception {
		this.mvc.perform(post("/test/errors/not-found"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("method_not_allowed"))
			.andExpect(jsonPath("$.requestId").isString());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ErrorProbeConfig {

		@RestController
		static class ErrorProbeController {

			@GetMapping("/test/errors/not-found")
			String notFound() {
				throw ApiException.notFound("not_found");
			}

			@PostMapping("/test/errors/validate")
			String validate(@Valid @RequestBody Named body) {
				return body.name();
			}

			@GetMapping("/test/errors/boom")
			String boom() {
				throw new IllegalStateException("secret internal detail");
			}

			@GetMapping("/test/errors/denied")
			String denied() {
				throw new AccessDeniedException("nope");
			}

		}

		record Named(@NotBlank String name) {
		}

	}

}
