package com.nemaz.school.reference;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A country's configuration as stored in the {@code countries} table and its language and calendar links: what a school
 * inherits when it is created and what the platform resolves for a person or a request (see
 * {@code tenancy.CountryResolver}). {@code names} are the localized names keyed by language code.
 */
public record CountryInfo(String code, boolean enabled, String defaultCurrencyCode, String defaultLanguageCode,
		String defaultTimezone, String defaultCalendarCode, Set<String> languageCodes, Set<String> calendarCodes,
		String phoneCountryCode, Map<String, String> names) {

	public CountryInfo {
		languageCodes = Set.copyOf(languageCodes);
		calendarCodes = Set.copyOf(calendarCodes);
		names = (names != null) ? Map.copyOf(names) : Map.of();
	}

	/** The name in {@code languageCode}, falling back to English and then to the code. */
	public String name(String languageCode) {
		String name = (languageCode != null) ? this.names.get(languageCode.toLowerCase(Locale.ROOT)) : null;
		if (name == null) {
			name = this.names.get("en");
		}
		return (name != null) ? name : this.code;
	}

	/** True when the country runs the calendar system (a row in {@code country_calendars}). */
	public boolean hasCalendar(String calendarCode) {
		return this.calendarCodes.contains(calendarCode);
	}

}
