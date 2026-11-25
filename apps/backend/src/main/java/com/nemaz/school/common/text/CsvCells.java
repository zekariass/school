package com.nemaz.school.common.text;

/** Helpers for CSV files that people open in spreadsheets. */
public final class CsvCells {

	private CsvCells() {
	}

	/**
	 * Spreadsheet formula injection: a text cell that starts with {@code = + - @}, a tab, a carriage return or a line feed
	 * would be read as a formula by Excel and friends, so it gets a leading apostrophe (shown as text). Numbers are
	 * written as numbers, not through this.
	 */
	public static String safe(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		char first = value.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') {
			return "'" + value;
		}
		return value;
	}

}
