package com.nemaz.school.guardians.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The two secrets that name an invitation.
 * <ul>
 * <li><b>Link token</b> (SMS and email invitations): 256 random bits, base64url (43 characters), in the link
 * {@code {base}/{locale}/invite/{token}}. Only its SHA-256 is stored: the token has full entropy, so no pepper is
 * needed.</li>
 * <li><b>Parent code</b> (printed, ClassDojo-style home connection): the student login-code format (10 characters from a
 * 31-character alphabet without 0 O 1 I L, about 49.5 bits, shown {@code XXXXX-XXXXX}, any case, hyphen optional) in its
 * own HMAC namespace ({@code HMAC-SHA256(pepper, "parent-code:" + code)}), so a parent code never signs a student in and
 * a login code never links a parent. Weaker than a token, so it is short-lived (setting, at most 90 days), multi-use
 * but capped, revoked when reissued, and guessing is rate limited per address and per code prefix.</li>
 * </ul>
 */
final class InvitationKeys {

	static final String CODE_PURPOSE = "parent-code";

	static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	static final int CODE_LENGTH = 10;

	private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	private InvitationKeys() {
	}

	static String newToken(SecureRandom random) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Canonical code: 10 characters, no hyphen. */
	static String newCode(SecureRandom random) {
		StringBuilder code = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}

	static String display(String canonicalCode) {
		return canonicalCode.substring(0, 5) + "-" + canonicalCode.substring(5);
	}

	/** A well-formed token, or empty. */
	static Optional<String> token(String submitted) {
		if (submitted == null) {
			return Optional.empty();
		}
		String trimmed = submitted.trim();
		return TOKEN.matcher(trimmed).matches() ? Optional.of(trimmed) : Optional.empty();
	}

	/** The canonical form of a typed code (any case, spaces and hyphens ignored), or empty. */
	static Optional<String> code(String submitted) {
		if (submitted == null) {
			return Optional.empty();
		}
		String code = submitted.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (code.length() != CODE_LENGTH) {
			return Optional.empty();
		}
		for (int i = 0; i < code.length(); i++) {
			if (ALPHABET.indexOf(code.charAt(i)) < 0) {
				return Optional.empty();
			}
		}
		return Optional.of(code);
	}

	/** The first five letters and digits, upper-cased: one rate-limit key for every spelling of a code. */
	static String prefix(String submitted) {
		StringBuilder out = new StringBuilder(5);
		if (submitted != null) {
			for (int i = 0; i < submitted.length() && out.length() < 5; i++) {
				char c = submitted.charAt(i);
				if (Character.isLetterOrDigit(c)) {
					out.append(Character.toUpperCase(c));
				}
			}
		}
		return out.toString();
	}

	static String sha256Hex(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
