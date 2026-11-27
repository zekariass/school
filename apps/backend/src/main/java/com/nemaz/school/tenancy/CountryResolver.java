package com.nemaz.school.tenancy;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Which country applies. The platform is multi-country and has no single "active country": the country that applies is
 * resolved, in this order, from
 * <ol>
 * <li>the school's country,</li>
 * <li>the user's home country ({@code users.home_country_code}),</li>
 * <li>the organization's country,</li>
 * <li>the platform default country: the platform-scope setting {@code platform.default_country}, which must name an
 * ENABLED country (checked at startup and whenever it is changed; there is no literal fallback in code).</li>
 * </ol>
 * The last step serves flows where no school is known yet (phone sign-in, one-time codes, invitation accept). A phone
 * number written with a {@code +} prefix always uses its own country, whatever this says. Zones, calendars, languages,
 * currency, calling codes and policies all come from the resolved country's rows.
 */
public interface CountryResolver {

	/** The platform-scope setting that names the platform default country. */
	String PLATFORM_DEFAULT_KEY = "platform.default_country";

	/**
	 * The platform default country. Fails with {@link IllegalStateException} (a deployment error, not a request error)
	 * when the setting is missing or does not name an ENABLED country.
	 */
	CountryContext platformDefault();

	/** A country named by code (any status); {@link IllegalArgumentException} when no such country exists. */
	CountryContext forCountry(String countryCode);

	/** School, then its organization, then the platform default. The zone is the school's own. */
	CountryContext forSchool(UUID schoolId);

	/** The user's home country, then the platform default. */
	CountryContext forUser(String homeCountryCode);

	/** The whole order; any argument may be null. */
	CountryContext resolve(UUID schoolId, String userHomeCountryCode, UUID organizationId);

	/** The school's own time zone ({@code schools.timezone}); the resolved country's default when it is unknown or invalid. */
	ZoneId schoolZone(UUID schoolId);

	/** The user's own zone when it is a valid zone id, else the zone of the country resolved for the user. */
	ZoneId userZone(String userTimezone, String homeCountryCode);

}
