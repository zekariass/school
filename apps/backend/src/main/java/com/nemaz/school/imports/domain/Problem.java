package com.nemaz.school.imports.domain;

import java.util.List;

/**
 * A problem found in a row: the file column it belongs to (the field's machine key, null for the row as a whole),
 * a stable code (the message {@code import.row.<code>} in both languages) and message arguments. Rows store codes
 * and arguments; text is rendered in the reader's language when it is read.
 */
public record Problem(String column, String code, List<String> args) {

	public Problem {
		args = (args == null) ? List.of() : List.copyOf(args);
	}

	public static Problem of(String column, String code, String... args) {
		return new Problem(column, code, List.of(args));
	}

}
