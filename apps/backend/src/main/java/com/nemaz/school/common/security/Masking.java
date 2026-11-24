package com.nemaz.school.common.security;

/**
 * Masks personal identifiers before they reach logs or the audit log. Never log a full phone
 * number, email address, one-time code or token.
 */
public final class Masking {

	private Masking() {
	}

	/** {@code +251911223567} becomes {@code +2519****567}. */
	public static String phone(String e164) {
		if (e164 == null) {
			return null;
		}
		if (e164.length() < 9) {
			return "****";
		}
		return e164.substring(0, 5) + "****" + e164.substring(e164.length() - 3);
	}

	/** {@code abebe@example.com} becomes {@code a***@example.com}. */
	public static String email(String email) {
		if (email == null) {
			return null;
		}
		int at = email.indexOf('@');
		if (at <= 0) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}

	/** Phone, email or username: masks whichever it looks like. */
	public static String identifier(String identifier) {
		if (identifier == null) {
			return null;
		}
		if (identifier.indexOf('@') >= 0) {
			return email(identifier);
		}
		if (identifier.startsWith("+")) {
			return phone(identifier);
		}
		return (identifier.length() <= 2) ? "***" : identifier.substring(0, 2) + "***";
	}

}
