package com.nemaz.school.common.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.nemaz.school.common.error.ApiException;

/**
 * Encodes keyset positions (e.g. [publishedAt, id]) as opaque URL-safe cursors and
 * clamps page sizes. Cursors are not signed: they only position a query that is still
 * authorization-filtered server-side, so a forged cursor cannot reveal extra data.
 */
public final class Cursors {

	public static final int DEFAULT_LIMIT = 20;

	public static final int MAX_LIMIT = 100;

	private static final String SEPARATOR = "\u001F";

	private Cursors() {
	}

	public static String encode(String... parts) {
		for (String part : parts) {
			if (part == null || part.contains(SEPARATOR)) {
				throw new IllegalArgumentException("Invalid cursor part: " + part);
			}
		}
		byte[] raw = String.join(SEPARATOR, parts).getBytes(StandardCharsets.UTF_8);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
	}

	/** Decodes a client cursor; malformed input is a 400 {@code invalid_cursor}. */
	public static List<String> decode(String cursor, int expectedParts) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			List<String> parts = Arrays.asList(raw.split(SEPARATOR, -1));
			if (parts.size() != expectedParts) {
				throw ApiException.badRequest("invalid_cursor");
			}
			return parts;
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

	public static int clampLimit(Integer requested) {
		if (requested == null) {
			return DEFAULT_LIMIT;
		}
		return Math.max(1, Math.min(MAX_LIMIT, requested));
	}

}
