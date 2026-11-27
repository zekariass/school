package com.nemaz.school.reference;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * A country's configuration as clients read it (the API shape of {@link CountryInfo}): what forms and pickers need to behave
 * for that country. Served by {@code GET /meta} (enabled countries, before sign-in), {@code GET /me} (the country that applies
 * to the user and those of their schools) and {@code GET /schools/by-slug/{slug}} (the school's country).
 */
@Schema(description = "A country's configuration: calling code, default zone, calendars, languages and currency")
public record CountryConfig(@NotNull @Schema(description = "ISO 3166-1 alpha-2 code") String code,
		@NotNull @Schema(description = "Name in the request language, English when there is none") String name,
		@NotNull @Schema(description = "Calling code with its plus sign, for example +254") String phoneCountryCode,
		@NotNull @Schema(description = "IANA zone id, the default for schools and people of this country") String defaultTimezone,
		@NotNull String defaultLanguageCode, @NotNull String defaultCalendarCode,
		@NotNull @Schema(description = "ISO 4217 code") String currencyCode,
		@NotNull @Schema(description = "Languages the country uses (country_languages), alphabetical") List<String> languageCodes,
		@NotNull @Schema(description = "Calendar systems the country uses (country_calendars), alphabetical, for example GREGORIAN, ETHIOPIAN") List<String> calendarCodes) {

	public static CountryConfig of(CountryInfo info, String languageCode) {
		return new CountryConfig(info.code(), info.name(languageCode), info.phoneCountryCode(), info.defaultTimezone(),
				info.defaultLanguageCode(), info.defaultCalendarCode(), info.defaultCurrencyCode(),
				info.languageCodes().stream().sorted().toList(), info.calendarCodes().stream().sorted().toList());
	}

}
