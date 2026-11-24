package com.nemaz.school.reference.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.reference.domain.AdministrativeArea;
import com.nemaz.school.reference.domain.CalendarSystem;
import com.nemaz.school.reference.domain.Country;
import com.nemaz.school.reference.domain.CountryCalendar;
import com.nemaz.school.reference.domain.CountryLanguage;
import com.nemaz.school.reference.domain.CountryStatus;
import com.nemaz.school.reference.domain.Currency;
import com.nemaz.school.reference.domain.Language;
import com.nemaz.school.reference.infrastructure.AdministrativeAreaRepository;
import com.nemaz.school.reference.infrastructure.CalendarSystemRepository;
import com.nemaz.school.reference.infrastructure.CountryCalendarRepository;
import com.nemaz.school.reference.infrastructure.CountryLanguageRepository;
import com.nemaz.school.reference.infrastructure.CountryRepository;
import com.nemaz.school.reference.infrastructure.CurrencyRepository;
import com.nemaz.school.reference.infrastructure.LanguageRepository;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reference data queries: the public {@link ReferenceData} plus what the API lists. */
@Service
@Transactional(readOnly = true)
public class ReferenceService implements ReferenceData {

	private final CountryRepository countries;

	private final CountryLanguageRepository countryLanguages;

	private final CountryCalendarRepository countryCalendars;

	private final LanguageRepository languages;

	private final CurrencyRepository currencies;

	private final CalendarSystemRepository calendars;

	private final AdministrativeAreaRepository areas;

	ReferenceService(CountryRepository countries, CountryLanguageRepository countryLanguages,
			CountryCalendarRepository countryCalendars, LanguageRepository languages, CurrencyRepository currencies,
			CalendarSystemRepository calendars, AdministrativeAreaRepository areas) {
		this.countries = countries;
		this.countryLanguages = countryLanguages;
		this.countryCalendars = countryCalendars;
		this.languages = languages;
		this.currencies = currencies;
		this.calendars = calendars;
		this.areas = areas;
	}

	// --- public API for other modules -------------------------------------------------

	@Override
	public Optional<CountryInfo> findCountry(String countryCode) {
		return this.countries.findById(countryCode).map((country) -> {
			Set<String> languageCodes = new TreeSet<>();
			this.countryLanguages.findByCountryCodeInOrderByCountryCodeAscSortOrderAsc(List.of(countryCode))
				.forEach((cl) -> languageCodes.add(cl.getLanguageCode()));
			Set<String> calendarCodes = new TreeSet<>();
			this.countryCalendars.findByCountryCodeInOrderByCountryCodeAscCalendarCodeAsc(List.of(countryCode))
				.forEach((cc) -> calendarCodes.add(cc.getCalendarCode()));
			return new CountryInfo(country.getCode(), country.isEnabled(), country.getDefaultCurrencyCode(),
					country.getDefaultLanguageCode(), country.getDefaultTimezone(), country.getDefaultCalendarCode(),
					languageCodes, calendarCodes);
		});
	}

	@Override
	public boolean currencyExists(String currencyCode) {
		return this.currencies.existsById(currencyCode);
	}

	@Override
	public boolean languageExists(String languageCode) {
		return this.languages.findById(languageCode).filter(Language::isEnabled).isPresent();
	}

	@Override
	public boolean calendarExists(String calendarCode) {
		return this.calendars.existsById(calendarCode);
	}

	// --- API queries ------------------------------------------------------------------

	/** Enabled countries with everything a client needs to render country-specific forms. */
	public List<CountryDetails> enabledCountries() {
		List<Country> enabled = this.countries.findByStatusOrderByCode(CountryStatus.ENABLED);
		if (enabled.isEmpty()) {
			return List.of();
		}
		List<String> codes = enabled.stream().map(Country::getCode).toList();
		Map<String, CalendarSystem> calendarByCode = new HashMap<>();
		this.calendars.findAll().forEach((c) -> calendarByCode.put(c.getCode(), c));
		Map<String, Language> languageByCode = new HashMap<>();
		this.languages.findAll().forEach((l) -> languageByCode.put(l.getCode(), l));
		Map<String, Currency> currencyByCode = new HashMap<>();
		this.currencies.findAll().forEach((c) -> currencyByCode.put(c.getCode(), c));

		Map<String, List<CountryLanguage>> languagesOf = new HashMap<>();
		this.countryLanguages.findByCountryCodeInOrderByCountryCodeAscSortOrderAsc(codes)
			.forEach((cl) -> languagesOf.computeIfAbsent(cl.getCountryCode(), (k) -> new ArrayList<>()).add(cl));
		Map<String, List<CountryCalendar>> calendarsOf = new HashMap<>();
		this.countryCalendars.findByCountryCodeInOrderByCountryCodeAscCalendarCodeAsc(codes)
			.forEach((cc) -> calendarsOf.computeIfAbsent(cc.getCountryCode(), (k) -> new ArrayList<>()).add(cc));

		return enabled.stream().map((country) -> {
			List<LanguageEntry> languageEntries = languagesOf.getOrDefault(country.getCode(), List.of())
				.stream()
				.filter((cl) -> languageByCode.get(cl.getLanguageCode()).isEnabled())
				.map((cl) -> new LanguageEntry(languageByCode.get(cl.getLanguageCode()), cl.isDefault(),
						cl.isOfficial()))
				.toList();
			List<CalendarEntry> calendarEntries = calendarsOf.getOrDefault(country.getCode(), List.of())
				.stream()
				.map((cc) -> new CalendarEntry(calendarByCode.get(cc.getCalendarCode()), cc.isDefault()))
				.sorted(Comparator.comparing((CalendarEntry e) -> !e.isDefault())
					.thenComparing((e) -> e.calendar().getCode()))
				.toList();
			return new CountryDetails(country, currencyByCode.get(country.getDefaultCurrencyCode()), languageEntries,
					calendarEntries);
		}).toList();
	}

	public List<Language> enabledLanguages() {
		return this.languages.findByEnabledTrueOrderByCode();
	}

	/**
	 * Administrative areas of a country at one level (optionally below a parent), in id
	 * order. Ids are UUIDv7, so the order is stable and doubles as the keyset position.
	 */
	public CursorPage<AdministrativeArea> administrativeAreas(String countryCode, Integer level, UUID parentId,
			String cursor, Integer limit) {
		if (!this.countries.existsById(countryCode)) {
			throw ApiException.notFound("country.not_found");
		}
		if (level != null && (level < 1 || level > 5)) {
			throw ApiException.badRequest("reference.invalid_level");
		}
		Specification<AdministrativeArea> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("countryCode"), countryCode));
			if (level != null) {
				predicates.add(cb.equal(root.get("level"), level.shortValue()));
			}
			if (parentId != null) {
				predicates.add(cb.equal(root.get("parentId"), parentId));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return KeysetPages.page(this.areas, spec, cursor, limit, false);
	}

	public record CountryDetails(Country country, Currency currency, List<LanguageEntry> languages,
			List<CalendarEntry> calendars) {
	}

	public record LanguageEntry(Language language, boolean isDefault, boolean isOfficial) {
	}

	public record CalendarEntry(CalendarSystem calendar, boolean isDefault) {
	}

}
