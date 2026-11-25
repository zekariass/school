package com.nemaz.school.imports.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Cleaning of single cells and of the keys used to look things up by name. */
public final class Cells {

	private Cells() {
	}

	/**
	 * A cell as a value: NFC, zero-width and bidirectional marks removed, no-break spaces plain, trimmed; null when
	 * nothing is left.
	 */
	public static String text(String raw) {
		if (raw == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(raw.length());
		String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
		for (int i = 0; i < nfc.length();) {
			int cp = nfc.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x200E || cp == 0x200F
					|| (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x00AD) {
				continue;
			}
			if (cp == 0x00A0 || cp == 0x202F || cp == 0x2007) {
				out.append(' ');
			}
			else if (Character.getType(cp) == Character.CONTROL && cp != '\n' && cp != '\t') {
				continue;
			}
			else {
				out.appendCodePoint(cp);
			}
		}
		String result = out.toString().strip();
		return result.isEmpty() ? null : result;
	}

	/** An identifier (student number, external id): trimmed text, without the {@code .0} a spreadsheet may add. */
	public static String identifier(String raw) {
		String text = text(raw);
		if (text == null) {
			return null;
		}
		text = text.replaceAll("\\s+", " ");
		return text.matches("^[0-9]+\\.0+$") ? text.substring(0, text.indexOf('.')) : text;
	}

	/** The key under which names of classes, grades, subjects, campuses and years are compared. */
	public static String key(String name) {
		return (name == null) ? "" : HeaderSynonyms.normalize(name);
	}

	/** Lower-cased e-mail address, or null when the text is not one. */
	public static String email(String raw) {
		String text = text(raw);
		if (text == null) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return (lower.length() <= 254 && lower.matches("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$")) ? lower : null;
	}

}
