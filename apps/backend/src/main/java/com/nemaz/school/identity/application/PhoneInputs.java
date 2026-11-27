package com.nemaz.school.identity.application;

import java.util.Optional;

import com.nemaz.school.tenancy.CountryResolver;

import org.springframework.stereotype.Component;

/**
 * Reads a phone number a person typed. National formats ({@code 0911 22 33 44}) are read in the request's {@code country}
 * when it names one, otherwise in the platform default country (flows with no school yet: sign-in, one-time codes,
 * invitation accept, administrator provisioning); numbers with a {@code +} prefix always use their own country.
 */
@Component
public class PhoneInputs {

	private final CountryResolver countries;

	PhoneInputs(CountryResolver countries) {
		this.countries = countries;
	}

	/** E.164, or empty when it is not a valid number in that country. */
	public Optional<String> toE164(String raw, String countryCode) {
		return PhoneNumbers.toE164(raw, region(countryCode));
	}

	/** The ISO region to read national numbers in: the given country, else the platform default's code. */
	String region(String countryCode) {
		return (countryCode != null && !countryCode.isBlank()) ? countryCode : this.countries.platformDefault().code();
	}

}
