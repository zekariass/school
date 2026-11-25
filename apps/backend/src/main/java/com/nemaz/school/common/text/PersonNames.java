package com.nemaz.school.common.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Cleaning and comparing people's names, shared by the people module (which keeps a comparison key of each student)
 * and the roster import (which compares file rows with it).
 * <ul>
 * <li>{@link #clean}: what is stored. NFC, the Ethiopic word space (U+1361) and every other space become one plain
 * space, zero-width and bidirectional control characters and punctuation other than apostrophe, hyphen and full
 * stop are dropped.</li>
 * <li>{@link #fold}: what is compared, never stored as a name. Latin letters lose case and accents; the Ethiopic
 * letters that sound the same are folded to one (U+1200 ha, U+1210 Ha, U+1280 Ha; se and Se; a and 'a; tsa and Tsa).
 * Latin and Ethiopic spellings of one name cannot be matched without transliteration, so they stay different.</li>
 * </ul>
 */
public final class PersonNames {

	private PersonNames() {
	}

	/** The name as it is stored: trimmed, normalized, single-spaced; null for null or an empty result. */
	public static String clean(String raw) {
		if (raw == null) {
			return null;
		}
		String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
		StringBuilder out = new StringBuilder(text.length());
		boolean space = false;
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (isInvisible(cp)) {
				continue;
			}
			if (cp == 0x1361 || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				space = true;
				continue;
			}
			if (isDroppedPunctuation(cp)) {
				continue;
			}
			if (space && out.length() > 0) {
				out.append(' ');
			}
			space = false;
			out.appendCodePoint(cp);
		}
		String result = out.toString().strip();
		return result.isEmpty() ? null : result;
	}

	/** The comparison key of one name part (empty for null). */
	public static String foldPart(String name) {
		String cleaned = clean(name);
		if (cleaned == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
		StringBuilder out = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length();) {
			int cp = decomposed.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.getType(cp) == Character.NON_SPACING_MARK && !isEthiopic(cp)) {
				continue;
			}
			if (isEthiopic(cp)) {
				out.appendCodePoint(foldEthiopic(cp));
			}
			else {
				out.appendCodePoint(Character.toLowerCase(cp));
			}
		}
		return Normalizer.normalize(out.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
	}

	/** The comparison key of a full name (given, father's, grandfather's name; absent parts are skipped). */
	public static String fold(String... parts) {
		StringJoiner joined = new StringJoiner(" ");
		for (String part : parts) {
			String folded = foldPart(part);
			if (!folded.isEmpty()) {
				joined.add(folded);
			}
		}
		return joined.toString();
	}

	private static boolean isEthiopic(int cp) {
		return (cp >= 0x1200 && cp <= 0x139F) || (cp >= 0x2D80 && cp <= 0x2DDF) || (cp >= 0xAB00 && cp <= 0xAB2F);
	}

	/** Maps the second letter of each homophone pair onto the first, order by order (the seven basic forms). */
	private static int foldEthiopic(int cp) {
		int[][] families = { { 0x1210, 0x1200 }, { 0x1280, 0x1200 }, { 0x1220, 0x1230 }, { 0x12D0, 0x12A0 },
				{ 0x1340, 0x1338 } };
		for (int[] family : families) {
			if (cp >= family[0] && cp <= family[0] + 6) {
				return family[1] + (cp - family[0]);
			}
		}
		return cp;
	}

	private static boolean isInvisible(int cp) {
		return cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD
				|| cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
				|| cp == 0x061C || Character.getType(cp) == Character.CONTROL;
	}

	private static boolean isDroppedPunctuation(int cp) {
		if (cp == '\'' || cp == '’' || cp == '-' || cp == '.') {
			return false;
		}
		int type = Character.getType(cp);
		return type == Character.OTHER_PUNCTUATION || type == Character.START_PUNCTUATION
				|| type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
				|| type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.CONNECTOR_PUNCTUATION
				|| type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
				|| type == Character.OTHER_SYMBOL || type == Character.DASH_PUNCTUATION && cp != '-';
	}

}
