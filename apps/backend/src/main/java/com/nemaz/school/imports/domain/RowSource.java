package com.nemaz.school.imports.domain;

import java.io.Closeable;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Rows of an uploaded file as strings, whatever the format. Rows come in file order with the number the file shows
 * (1-based). Cells hold displayed or cached values, never a formula (spec section 125: no formula
 * is ever evaluated).
 */
public interface RowSource extends Closeable {

	/** Reads the file once, handing every non-empty row (header included) to {@code consumer}. */
	void read(Consumer<RowData> consumer);

	/** What was detected while reading (encoding, delimiter, sheets, workbook flags); complete after {@link #read}. */
	SourceInfo info();

	/**
	 * One row. {@code nativeDates} marks the columns holding a real spreadsheet date (already converted to ISO text,
	 * always Gregorian). {@code warnings} are row-level notes of the reader (a merged cell, a formula cell).
	 */
	record RowData(int rowNumber, List<String> cells, BitSet nativeDates, List<Problem> warnings) {

		public RowData {
			cells = List.copyOf(cells);
			warnings = List.copyOf(warnings);
		}

		public boolean blank() {
			return this.cells.stream().allMatch((c) -> c == null || c.isBlank());
		}

	}

	/** {@code delimiter} and {@code encoding} are null for workbooks; {@code sheetNames} is empty for CSV. */
	record SourceInfo(String format, String encoding, String delimiter, List<String> sheetNames, String sheet,
			boolean date1904, int formulaCells, int mergedRegions) {

		public SourceInfo {
			sheetNames = List.copyOf(sheetNames);
		}

	}

}
