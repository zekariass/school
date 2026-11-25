package com.nemaz.school.identity;

import java.util.Optional;

import com.nemaz.school.identity.application.Emails;
import com.nemaz.school.identity.application.PhoneNumbers;

/**
 * Normalization of phone numbers and email addresses for other modules (invitations store destinations in the same
 * form as accounts, so that "the accepting user's verified phone equals the invited phone" can be compared).
 */
public final class Contacts {

	private Contacts() {
	}

	/** E.164, reading national formats in {@code countryCode} (default ET); empty when it is not a valid number. */
	public static Optional<String> phone(String raw, String countryCode) {
		return PhoneNumbers.toE164(raw, countryCode);
	}

	/** Trimmed and lower-cased; empty when it is not a plausible address. */
	public static Optional<String> email(String raw) {
		return Emails.normalize(raw);
	}

}
