package com.nemaz.school.common.text;

/**
 * Plain text cleaning for text people write (event titles, descriptions and the like): the server never interprets markup,
 * so an angle bracket is just a character. What is removed is what makes plain text misleading or unreadable: control
 * characters other than tab and newline, and the invisible bidirectional and zero-width characters that can make a title
 * read differently from what it is (a spoofing trick). Line ends become {@code \n} and surrounding space is trimmed. Clients
 * must render the text as text and may turn http and https addresses into links.
 */
public final class PlainText {

	private PlainText() {
	}

	public static String clean(String raw) {
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

	/** Length in characters (code points). */
	public static int length(String text) {
		return text.codePointCount(0, text.length());
	}

}
