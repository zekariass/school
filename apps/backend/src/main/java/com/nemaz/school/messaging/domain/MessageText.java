package com.nemaz.school.messaging.domain;

/**
 * Message text is plain text: no markup is interpreted by the server or expected from clients (the same cleaner as
 * announcements): line ends become {@code \n}, control characters other than tab and newline are dropped, and so are the
 * invisible bidirectional and zero-width characters that can disguise a text; surrounding space is trimmed.
 */
public final class MessageText {

	public static final int MAX_LENGTH = 2000;

	private MessageText() {
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

	public static int length(String text) {
		return text.codePointCount(0, text.length());
	}

	private static boolean invisible(int cp) {
		return (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x206F)
				|| cp == 0xFEFF || cp == 0x061C || cp == 0x180E;
	}

}
