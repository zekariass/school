package com.nemaz.school.imports.application;

import com.nemaz.school.imports.domain.DateParser.DateSystem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * How a file is read and understood. Everything is optional: absent values are detected (encoding, delimiter) or
 * default (Gregorian dates, invalid rows block the commit).
 *
 * @param encoding UTF-8, UTF-16LE, UTF-16BE or windows-1252 (CSV only)
 * @param delimiter one character, or {@code \t} for tab (CSV only)
 * @param sheet the sheet to read (workbooks only); the first visible sheet by default
 * @param dateSystem how text dates in the file are written; spreadsheet date cells are always Gregorian
 * @param skipInvalidRows commit only: apply the valid rows and leave the invalid ones out
 */
public record ImportOptions(
		@Pattern(regexp = "^(?i)(utf-?8|utf-?16(le|be)?|windows-1252|cp1252|latin1|iso-8859-1)$", message = "validation.invalid") String encoding,
		@Size(max = 3, message = "validation.tooLong") String delimiter,
		@Size(max = 100, message = "validation.tooLong") String sheet,
		@Schema(description = "GREGORIAN (default) or ETHIOPIAN") DateSystem dateSystem,
		@Schema(description = "Commit only: apply the valid rows and skip the invalid ones") Boolean skipInvalidRows) {

	public static final ImportOptions DEFAULT = new ImportOptions(null, null, null, DateSystem.GREGORIAN, false);

	public DateSystem dateSystemOrDefault() {
		return (this.dateSystem != null) ? this.dateSystem : DateSystem.GREGORIAN;
	}

	public boolean skipInvalid() {
		return Boolean.TRUE.equals(this.skipInvalidRows);
	}

	/** The options as saved with the job: what the user chose about reading and dates, never the commit-only flag. */
	public ImportOptions forReading() {
		return new ImportOptions(blank(this.encoding), blank(this.delimiter), blank(this.sheet), dateSystemOrDefault(), null);
	}

	private static String blank(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

}
