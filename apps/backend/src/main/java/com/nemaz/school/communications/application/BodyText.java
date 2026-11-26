package com.nemaz.school.communications.application;

import com.nemaz.school.common.error.ApiException;

/**
 * Announcement text is <b>plain text only</b> (decision of M10a): no markup is ever interpreted by the server or expected
 * from clients, so there is nothing to sanitize into safety: an angle bracket is just a character. What is cleaned is what
 * would make plain text misleading or unreadable: line ends are normalized to {@code \n}, control characters other than
 * tab and newline are dropped, and so are the invisible bidirectional and zero-width characters that can make a title read
 * differently from what it is (a spoofing trick), and surrounding space is trimmed. Clients must render the text as text
 * (React and React Native do by default) and may turn http and https addresses into links; email and SMS are plain text.
 */
final class BodyText {

	static final int TITLE_MAX = 200;

	static final int BODY_MAX = 5000;

	static final int NOTE_MAX = 500;

	private BodyText() {
	}

	/** The cleaned text; 400 {@code announcement.<field>_required} when blank, {@code ..._too_long} when over {@code max}. */
	static String required(String raw, int max, String field) {
		String text = clean(raw);
		if (text.isEmpty()) {
			throw ApiException.badRequest("announcement." + field + "_required");
		}
		if (text.codePointCount(0, text.length()) > max) {
			throw ApiException.badRequest("announcement." + field + "_too_long");
		}
		return text;
	}

	/** The cleaned text, null when blank; too long is 400. */
	static String optional(String raw, int max, String field) {
		if (raw == null) {
			return null;
		}
		String text = clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		if (text.codePointCount(0, text.length()) > max) {
			throw ApiException.badRequest("announcement." + field + "_too_long");
		}
		return text;
	}

	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder out = new StringBuilder(normalized.length());
		normalized.codePoints().forEach((cp) -> {
			if (cp == '\n' || cp == '\t' || (!Character.isISOControl(cp) && !invisible(cp))) {
				out.appendCodePoint(cp);
			}
		});
		return out.toString().strip();
	}

	/** Bidirectional overrides and isolates, zero-width characters, the byte order mark and other format characters. */
	private static boolean invisible(int cp) {
		return (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x206F)
				|| cp == 0xFEFF || cp == 0x061C || cp == 0x180E;
	}

	/** One line of at most {@code max} characters for SMS and email subjects: whitespace runs become single spaces. */
	static String oneLine(String text, int max) {
		String line = text.replaceAll("\\s+", " ").strip();
		if (line.length() <= max) {
			return line;
		}
		return line.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
	}

}
