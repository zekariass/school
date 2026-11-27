package com.nemaz.school.system.openapi;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import com.nemaz.school.system.security.SecurityConfig;
import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at /v3/api-docs. It is the contract for web and mobile: it is
 * exported to packages/api-client/openapi/openapi.json (OpenApiSpecTest) and turned into
 * TypeScript types. Keep it deterministic: no hosts, timestamps or build versions.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String PROBLEM_SCHEMA = "ProblemDetail";

	static final String BEARER_SCHEME = "bearer";

	/** Operations that need no access token (as configured in SecurityConfig). */
	static final List<String> PUBLIC_PATHS = java.util.stream.Stream
		.of(java.util.stream.Stream.of("/api/v1/meta"), java.util.Arrays.stream(SecurityConfig.PUBLIC_AUTH_ENDPOINTS),
				java.util.Arrays.stream(SecurityConfig.PUBLIC_GET_ENDPOINTS))
		.flatMap(java.util.function.Function.identity())
		.toList();

	@Bean
	OpenAPI schoolOpenApi() {
		return new OpenAPI()
			.info(new Info().title("School API")
				.version("v1")
				.description("Multi-tenant school, family and tutor-directory platform API. "
						+ "Errors are RFC 9457 problem details (application/problem+json) with a stable `code`."))
			.servers(List.of(new Server().url("/").description("Same origin")))
			.components(new Components().addSecuritySchemes(BEARER_SCHEME,
					new SecurityScheme().type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Access token from /api/v1/auth/* (15 minutes; renew with /api/v1/auth/refresh)")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

	/**
	 * Every operation documents its error shape as a `default` problem-details response.
	 * The schema is registered here (not on the OpenAPI bean) because springdoc removes
	 * unreferenced schemas before customizers add the references.
	 */
	@Bean
	OpenApiCustomizer problemDetailDefaultResponses() {
		return (openApi) -> {
			if (openApi.getComponents() == null) {
				openApi.setComponents(new Components());
			}
			openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemDetailSchema());
			addDefaultErrorResponses(openApi);
			markPublicOperations(openApi);
		};
	}

	/** Public operations override the global bearer requirement with an empty one. */
	private static void markPublicOperations(OpenAPI openApi) {
		openApi.getPaths().forEach((path, item) -> {
			if (PUBLIC_PATHS.contains(path)) {
				item.readOperations().forEach((operation) -> operation.setSecurity(List.of()));
			}
		});
	}

	private static void addDefaultErrorResponses(OpenAPI openApi) {
		openApi.getPaths()
			.values()
			.forEach((path) -> path.readOperations().forEach((operation) -> {
				if (operation.getResponses() != null && !operation.getResponses().containsKey("default")) {
					operation.getResponses()
						.addApiResponse("default", new ApiResponse().description("Error (RFC 9457 problem details)")
							.content(new Content().addMediaType("application/problem+json",
									new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
				}
			}));
	}

	private static Schema<?> problemDetailSchema() {
		ObjectSchema fieldError = new ObjectSchema();
		fieldError.setProperties(Map.of("field", new StringSchema(), "code", new StringSchema(), "message",
				new StringSchema()));
		fieldError.setRequired(List.of("field", "code", "message"));

		ObjectSchema problem = new ObjectSchema();
		problem.setDescription("RFC 9457 problem details returned by every failing endpoint.");
		problem.addProperty("type", new StringSchema().format("uri"));
		problem.addProperty("title", new StringSchema());
		problem.addProperty("status", new IntegerSchema().format("int32"));
		problem.addProperty("detail", new StringSchema());
		problem.addProperty("instance", new StringSchema());
		problem.addProperty("code", new StringSchema().description("Stable machine-readable error code"));
		problem.addProperty("requestId", new StringSchema().description("Correlates with server logs"));
		problem.addProperty("errors", new ArraySchema().items(fieldError).description("Field errors (validation_failed)"));
		problem.setRequired(List.of("type", "title", "status", "code"));
		return problem;
	}

}
