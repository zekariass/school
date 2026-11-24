package com.nemaz.school.reference;

import java.util.Optional;

/**
 * Read access to reference data for other modules (the module's public API). Codes are
 * the natural keys: ISO 3166-1 alpha-2 countries, ISO 4217 currencies, BCP 47 languages.
 */
public interface ReferenceData {

	Optional<CountryInfo> findCountry(String countryCode);

	boolean currencyExists(String currencyCode);

	boolean languageExists(String languageCode);

	boolean calendarExists(String calendarCode);

	/** The country's name in {@code languageCode}, falling back to English. */
	Optional<String> countryName(String countryCode, String languageCode);

}
