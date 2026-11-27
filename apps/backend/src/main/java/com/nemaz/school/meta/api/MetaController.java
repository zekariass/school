package com.nemaz.school.meta.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.nemaz.school.common.i18n.SupportedLocales;
import com.nemaz.school.reference.CountryConfig;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.CountryResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Public service metadata. Clients use {@code serverTime} to detect device clock skew before replaying offline operations
 * (spec §104), {@code apiVersion} for compatibility checks, {@code supportedLocales} for the languages the backend has
 * messages for, and {@code defaultCountry} and {@code countries} to render country-specific forms (phone calling codes,
 * zones, calendars, languages) before anybody has signed in. Countries are data: an ENABLED row in {@code countries} appears
 * here without a deployment.
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "system")
class MetaController {

	private final Clock clock;

	private final String buildVersion;

	private final SupportedLocales supportedLocales;

	private final ReferenceData reference;

	private final CountryResolver countries;

	MetaController(Clock clock, ObjectProvider<BuildProperties> build, SupportedLocales supportedLocales,
			ReferenceData reference, CountryResolver countries) {
		this.clock = clock;
		BuildProperties props = build.getIfAvailable();
		this.buildVersion = (props != null) ? props.getVersion() : "dev";
		this.supportedLocales = supportedLocales;
		this.reference = reference;
		this.countries = countries;
	}

	@GetMapping
	@Operation(operationId = "getMeta", summary = "Service metadata, server time and the enabled countries")
	MetaResponse meta() {
		String language = LocaleContextHolder.getLocale().getLanguage();
		return new MetaResponse("school-backend", "v1", this.buildVersion, Instant.now(this.clock),
				this.supportedLocales.codes(), this.countries.platformDefault().code(),
				this.reference.findEnabledCountries().stream().map((c) -> CountryConfig.of(c, language)).toList());
	}

	public record MetaResponse(@Schema(requiredMode = REQUIRED) String service,
			@Schema(requiredMode = REQUIRED) String apiVersion, @Schema(requiredMode = REQUIRED) String buildVersion,
			@Schema(requiredMode = REQUIRED) Instant serverTime,
			@Schema(requiredMode = REQUIRED, description = "Languages the backend has messages for (English first)") List<String> supportedLocales,
			@Schema(requiredMode = REQUIRED,
					description = "Platform default country code: applies where no school, user or organization country is known (phone sign-in, one-time codes, invitation accept)") String defaultCountry,
			@Schema(requiredMode = REQUIRED, description = "ENABLED countries only, by code") List<CountryConfig> countries) {
	}

}
