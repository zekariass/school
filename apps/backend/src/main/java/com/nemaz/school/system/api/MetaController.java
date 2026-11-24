package com.nemaz.school.system.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Public service metadata. Clients use {@code serverTime} to detect device clock skew
 * before replaying offline operations (spec §104), and {@code apiVersion} for
 * compatibility checks.
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "system")
class MetaController {

	private final Clock clock;

	private final String buildVersion;

	private final List<String> supportedLocales;

	MetaController(Clock clock, ObjectProvider<BuildProperties> build,
			@Value("${school.i18n.supported-locales:en,am}") List<String> supportedLocales) {
		this.clock = clock;
		BuildProperties props = build.getIfAvailable();
		this.buildVersion = (props != null) ? props.getVersion() : "dev";
		this.supportedLocales = List.copyOf(supportedLocales);
	}

	@GetMapping
	@Operation(operationId = "getMeta", summary = "Service metadata and server time")
	MetaResponse meta() {
		return new MetaResponse("school-backend", "v1", this.buildVersion, Instant.now(this.clock),
				this.supportedLocales);
	}

	public record MetaResponse(@Schema(requiredMode = REQUIRED) String service,
			@Schema(requiredMode = REQUIRED) String apiVersion, @Schema(requiredMode = REQUIRED) String buildVersion,
			@Schema(requiredMode = REQUIRED) Instant serverTime,
			@Schema(requiredMode = REQUIRED) List<String> supportedLocales) {
	}

}
