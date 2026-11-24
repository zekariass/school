package com.nemaz.school.reference;

import java.util.Set;

/** Country defaults a school inherits when it is created. */
public record CountryInfo(String code, boolean enabled, String defaultCurrencyCode, String defaultLanguageCode,
		String defaultTimezone, String defaultCalendarCode, Set<String> languageCodes, Set<String> calendarCodes) {

	public CountryInfo {
		languageCodes = Set.copyOf(languageCodes);
		calendarCodes = Set.copyOf(calendarCodes);
	}

}
