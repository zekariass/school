package com.nemaz.school.tenancy;

import java.time.ZoneId;
import java.util.Set;

import com.nemaz.school.reference.CountryInfo;

/**
 * The country configuration that applies to a school, a user or a request, as resolved by {@link CountryResolver}, and
 * where it came from. Everything country-specific (phone calling code and parsing region, time zone, calendar, languages,
 * currency, which legal text applies) is read from here, never from a literal.
 *
 * @param country the {@code countries} row with its language and calendar links
 * @param source which step of the resolution order produced it
 * @param zone the zone that applies: the school's own {@code schools.timezone} for a school context, the country's
 * default zone otherwise
 */
public record CountryContext(CountryInfo country, Source source, ZoneId zone) {

	/** The step of the resolution order that decided the country. */
	public enum Source {

		/** The school's country. */
		SCHOOL,
		/** The user's home country ({@code users.home_country_code}). */
		USER,
		/** The organization's country. */
		ORGANIZATION,
		/** The platform default country (setting {@code platform.default_country}). */
		PLATFORM_DEFAULT,
		/** A country named explicitly by the caller (a request field, a {@code +} phone prefix). */
		EXPLICIT

	}

	/** ISO 3166-1 alpha-2 code. */
	public String code() {
		return this.country.code();
	}

	/** The calling code with its plus sign, for example {@code +254}. */
	public String phoneCountryCode() {
		return this.country.phoneCountryCode();
	}

	public String defaultCalendarCode() {
		return this.country.defaultCalendarCode();
	}

	public String defaultLanguageCode() {
		return this.country.defaultLanguageCode();
	}

	public String currencyCode() {
		return this.country.defaultCurrencyCode();
	}

	public Set<String> languageCodes() {
		return this.country.languageCodes();
	}

	public Set<String> calendarCodes() {
		return this.country.calendarCodes();
	}

	public boolean hasCalendar(String calendarCode) {
		return this.country.hasCalendar(calendarCode);
	}

}
