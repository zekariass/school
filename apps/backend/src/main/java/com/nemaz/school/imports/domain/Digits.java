package com.nemaz.school.imports.domain;

/** Digits written in other scripts (Arabic-Indic, Devanagari, fullwidth, Ethiopic 1-9) as ASCII digits. */
final class Digits {

	private Digits() {
	}

	static String toAscii(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp >= 0x1369 && cp <= 0x1371) {
				out.append((char) ('1' + (cp - 0x1369)));
			}
			else if (cp > 0x7F && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
				out.append((char) ('0' + Character.digit(cp, 10)));
			}
			else if (cp == 0x200E || cp == 0x200F || cp == 0x200B || cp == 0xFEFF || cp == 0x00A0 || cp == 0x202F) {
				out.append((cp == 0x00A0 || cp == 0x202F) ? " " : "");
			}
			else {
				out.appendCodePoint(cp);
			}
		}
		return out.toString();
	}

}
