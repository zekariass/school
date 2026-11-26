package com.nemaz.school.portfolio.application;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.text.PlainText;

/**
 * Portfolio text is <b>plain text only</b> (rule book P1.9), cleaned like announcements: line ends become {@code \n}, control
 * characters other than tab and newline are dropped, and so are the invisible bidirectional and zero-width characters that can
 * make a title read differently from what it is; surrounding space is trimmed. Clients render the text as text. It is bounded and
 * never logged, audited, put in events or in notifications.
 */
final class PortfolioText {

	static final int TITLE_MAX = 120;

	static final int DESCRIPTION_MAX = 1000;

	static final int REFLECTION_MAX = 1000;

	static final int TEXT_MAX = 5000;

	static final int NOTE_MAX = 500;

	static final int COMMENT_MAX = 500;

	static final int REASON_MAX = 200;

	private PortfolioText() {
	}

	/** The cleaned text; 400 {@code portfolio.<field>_required} when blank, {@code portfolio.<field>_too_long} over {@code max}. */
	static String required(String raw, int max, String field) {
		String text = PlainText.clean(raw);
		if (text.isEmpty()) {
			throw ApiException.badRequest("portfolio." + field + "_required");
		}
		if (PlainText.length(text) > max) {
			throw ApiException.badRequest("portfolio." + field + "_too_long");
		}
		return text;
	}

	/** The cleaned text, null when blank; too long is 400. */
	static String optional(String raw, int max, String field) {
		if (raw == null) {
			return null;
		}
		String text = PlainText.clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		if (PlainText.length(text) > max) {
			throw ApiException.badRequest("portfolio." + field + "_too_long");
		}
		return text;
	}

}
