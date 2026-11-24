package com.nemaz.school.identity.application;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Emails are compared and stored lower-cased and trimmed. */
public final class Emails {

	private static final Pattern SHAPE = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,253}\\.[^\\s@]{2,}$");

	private Emails() {
	}

	public static Optional<String> normalize(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String email = raw.trim().toLowerCase(Locale.ROOT);
		return (email.length() <= 254 && SHAPE.matcher(email).matches()) ? Optional.of(email) : Optional.empty();
	}

}
