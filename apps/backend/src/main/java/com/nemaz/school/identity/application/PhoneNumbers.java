package com.nemaz.school.identity.application;

import java.util.Locale;
import java.util.Optional;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Phone numbers to E.164 with libphonenumber: national formats ({@code 0911 22 33 44}) are
 * read in the given country (Ethiopia by default), international ones ({@code +254...}) as
 * written. Only numbers valid for their region are accepted.
 */
public final class PhoneNumbers {

	public static final String DEFAULT_COUNTRY = "ET";

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	private PhoneNumbers() {
	}

	public static Optional<String> toE164(String raw, String countryCode) {
		if (raw == null || raw.isBlank() || raw.length() > 32) {
			return Optional.empty();
		}
		String region = (countryCode != null && !countryCode.isBlank()) ? countryCode.toUpperCase(Locale.ROOT)
				: DEFAULT_COUNTRY;
		try {
			PhoneNumber number = UTIL.parse(raw.trim(), region);
			if (!UTIL.isValidNumber(number)) {
				return Optional.empty();
			}
			return Optional.of(UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
		}
		catch (NumberParseException ex) {
			return Optional.empty();
		}
	}

}
