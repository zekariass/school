package com.nemaz.school.people.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The two shapes of a student login code (decision D6).
 * <ul>
 * <li><b>QR token:</b> 128 random bits, base64url (22 characters). Only ever shown inside a QR code.</li>
 * <li><b>Text code:</b> 10 characters drawn uniformly from a 31-character alphabet without the look-alikes
 * 0, O, 1, I and L (about 49.5 bits), shown as {@code XXXXX-XXXXX}. Typed by children, so it is accepted in any
 * case, with or without the hyphen; the weaker entropy is compensated by rate limits per client address and
 * per code prefix, a validity of at most two years and revocation on re-issue.</li>
 * </ul>
 * Only {@code HMAC-SHA256(pepper, canonical form)} is stored.
 */
final class LoginCodeFormat {

	static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	static final int TEXT_LENGTH = 10;

	private static final Pattern QR = Pattern.compile("^[A-Za-z0-9_-]{22}$");

	private LoginCodeFormat() {
	}

	enum Kind {

		QR, TEXT

	}

	/** A recognised code: its kind and the canonical string that is hashed. */
	record Parsed(Kind kind, String canonical) {
	}

	static String newQrToken(SecureRandom random) {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** The canonical text code: 10 characters, no hyphen. */
	static String newText(SecureRandom random) {
		StringBuilder text = new StringBuilder(TEXT_LENGTH);
		for (int i = 0; i < TEXT_LENGTH; i++) {
			text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return text.toString();
	}

	/** {@code K7M2P9XQ4T} becomes {@code K7M2P-9XQ4T}. */
	static String display(String canonicalText) {
		return canonicalText.substring(0, 5) + "-" + canonicalText.substring(5);
	}

	/** Reads whatever a client submitted; empty when it cannot be a code. */
	static Optional<Parsed> parse(String submitted) {
		if (submitted == null) {
			return Optional.empty();
		}
		String trimmed = submitted.trim();
		if (QR.matcher(trimmed).matches()) {
			return Optional.of(new Parsed(Kind.QR, trimmed));
		}
		String text = trimmed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (text.length() != TEXT_LENGTH) {
			return Optional.empty();
		}
		for (int i = 0; i < text.length(); i++) {
			if (ALPHABET.indexOf(text.charAt(i)) < 0) {
				return Optional.empty();
			}
		}
		return Optional.of(new Parsed(Kind.TEXT, text));
	}

}
