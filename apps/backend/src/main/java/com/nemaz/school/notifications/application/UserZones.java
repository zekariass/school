package com.nemaz.school.notifications.application;

import java.time.ZoneId;

import com.nemaz.school.identity.UserDirectory.UserPreferences;
import com.nemaz.school.tenancy.CountryResolver;

import org.springframework.stereotype.Component;

/**
 * The zone a user's quiet hours are read in: the zone they chose, else the default zone of the country that applies to them
 * (their home country, else the platform default; see {@link CountryResolver}). No zone is named in code.
 */
@Component
class UserZones {

	private final CountryResolver countries;

	UserZones(CountryResolver countries) {
		this.countries = countries;
	}

	ZoneId zone(UserPreferences preferences) {
		return (preferences == null) ? this.countries.forUser(null).zone()
				: this.countries.userZone(preferences.timezone(), preferences.homeCountryCode());
	}

}
