package com.nemaz.school.imports.infrastructure;

/** What the user may override about reading a file; null means "detect". {@code sheet} is a sheet name (workbooks only). */
public record SourceOptions(String encoding, String delimiter, String sheet) {

	public static final SourceOptions DETECT = new SourceOptions(null, null, null);

}
