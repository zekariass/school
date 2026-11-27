package com.nemaz.school.tenancy.application;

import java.util.Locale;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.CountryResolver;
import com.nemaz.school.tenancy.SettingChangeGuard;

import org.springframework.stereotype.Component;

/**
 * The platform default country must always be an ENABLED country: a change of {@code platform.default_country} (or a
 * clear, which falls back to the definition's seed) to anything else is refused, so the startup check cannot be broken
 * through the API.
 */
@Component
class PlatformDefaultCountryGuard implements SettingChangeGuard {

	private final ReferenceData reference;

	PlatformDefaultCountryGuard(ReferenceData reference) {
		this.reference = reference;
	}

	@Override
	public void check(Change change) {
		if (!CountryResolver.PLATFORM_DEFAULT_KEY.equals(change.key())) {
			return;
		}
		String code = (change.effectiveAfter() != null && change.effectiveAfter().isString())
				? change.effectiveAfter().asString().trim().toUpperCase(Locale.ROOT) : null;
		CountryInfo country = (code != null) ? this.reference.findCountry(code).orElse(null) : null;
		if (country == null) {
			throw ApiException.badRequest("country.unknown");
		}
		if (!country.enabled()) {
			throw ApiException.badRequest("country.not_enabled");
		}
	}

}
