package com.nemaz.school.reference.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.reference.application.ReferenceService;
import com.nemaz.school.reference.application.ReferenceService.CalendarEntry;
import com.nemaz.school.reference.application.ReferenceService.CountryDetails;
import com.nemaz.school.reference.application.ReferenceService.LanguageEntry;
import com.nemaz.school.reference.domain.AdministrativeArea;
import com.nemaz.school.reference.domain.Currency;
import com.nemaz.school.reference.domain.Language;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference data for forms and pickers. Readable by any authenticated user; there is
 * nothing tenant-specific here.
 */
@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "reference")
class ReferenceController {

	private final ReferenceService reference;

	ReferenceController(ReferenceService reference) {
		this.reference = reference;
	}

	@GetMapping("/countries")
	@Operation(operationId = "listCountries",
			summary = "Enabled countries with languages, calendars, currency, address and name formats")
	List<CountryResponse> countries() {
		return this.reference.enabledCountries().stream().map(CountryResponse::from).toList();
	}

	@GetMapping("/countries/{code}/administrative-areas")
	@Operation(operationId = "listAdministrativeAreas",
			summary = "Administrative areas of a country (region, zone, woreda ...), by level and parent")
	CursorPage<AdministrativeAreaResponse> administrativeAreas(@PathVariable String code,
			@Parameter(description = "Administrative level, 1 (top) to 5") @RequestParam(required = false) Integer level,
			@Parameter(description = "Only children of this area") @RequestParam(required = false) UUID parent,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reference.administrativeAreas(code.toUpperCase(Locale.ROOT), level, parent, cursor, limit)
			.map(AdministrativeAreaResponse::from);
	}

	@GetMapping("/languages")
	@Operation(operationId = "listLanguages", summary = "Enabled languages")
	List<LanguageResponse> languages() {
		return this.reference.enabledLanguages().stream().map(LanguageResponse::from).toList();
	}

	public record LanguageResponse(@NotNull String code, @NotNull String name, @NotNull String nativeName,
			String script, @NotNull @Schema(allowableValues = { "LTR", "RTL" }) String direction) {

		static LanguageResponse from(Language l) {
			return new LanguageResponse(l.getCode(), l.getName(), l.getNativeName(), l.getScript(), l.getDirection());
		}

	}

	public record CountryLanguageResponse(@NotNull String code, @NotNull String name, @NotNull String nativeName,
			String script, @NotNull String direction, @NotNull boolean isDefault, @NotNull boolean isOfficial) {

		static CountryLanguageResponse from(LanguageEntry e) {
			Language l = e.language();
			return new CountryLanguageResponse(l.getCode(), l.getName(), l.getNativeName(), l.getScript(),
					l.getDirection(), e.isDefault(), e.isOfficial());
		}

	}

	public record CountryCalendarResponse(@NotNull String code, @NotNull String name, @NotNull boolean isDefault) {

		static CountryCalendarResponse from(CalendarEntry e) {
			return new CountryCalendarResponse(e.calendar().getCode(), e.calendar().getName(), e.isDefault());
		}

	}

	public record CurrencyResponse(@NotNull String code, @NotNull String name, String symbol,
			@NotNull int minorUnits) {

		static CurrencyResponse from(Currency c) {
			return new CurrencyResponse(c.getCode(), c.getName(), c.getSymbol(), c.getMinorUnits());
		}

	}

	public record CountryResponse(@NotNull String code,
			@NotNull @Schema(description = "Country name by language code") Map<String, String> nameI18n,
			@NotNull CurrencyResponse currency, @NotNull String defaultLanguageCode, @NotNull String defaultTimezone,
			@NotNull String defaultCalendarCode, @NotNull String phoneCountryCode,
			@NotNull List<CountryLanguageResponse> languages, @NotNull List<CountryCalendarResponse> calendars,
			@NotNull @Schema(description = "Labels per administrative level and language, required levels, postal rules") JsonNode addressFormat,
			@Schema(description = "Labels for given_name / middle_name / family_name per language") JsonNode nameFormat) {

		static CountryResponse from(CountryDetails d) {
			return new CountryResponse(d.country().getCode(), d.country().getNameI18n(),
					CurrencyResponse.from(d.currency()), d.country().getDefaultLanguageCode(),
					d.country().getDefaultTimezone(), d.country().getDefaultCalendarCode(),
					d.country().getPhoneCountryCode(), d.languages().stream().map(CountryLanguageResponse::from).toList(),
					d.calendars().stream().map(CountryCalendarResponse::from).toList(), d.country().getAddressFormat(),
					d.country().getNameFormat());
		}

	}

	public record AdministrativeAreaResponse(@NotNull UUID id, @NotNull String countryCode, UUID parentId,
			@NotNull int level, String code, @NotNull Map<String, String> nameI18n) {

		static AdministrativeAreaResponse from(AdministrativeArea a) {
			return new AdministrativeAreaResponse(a.getId(), a.getCountryCode(), a.getParentId(), a.getLevel(),
					a.getCode(), a.getNameI18n());
		}

	}

}
