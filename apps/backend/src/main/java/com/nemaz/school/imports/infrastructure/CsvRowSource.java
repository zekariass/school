package com.nemaz.school.imports.infrastructure;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.ImportLimits;
import com.nemaz.school.imports.domain.RowSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * CSV and delimited text through commons-csv. The file is read into memory (it is at most 10 MB), its encoding and
 * delimiter detected (see {@link CsvDetector}) unless the user chose them, a byte order mark and an Excel
 * {@code sep=;} line removed. The row number is the record number (the line number, unless a quoted cell spans
 * lines), counted from the file's first line.
 */
public final class CsvRowSource implements RowSource {

	private final Path file;

	private final ImportLimits limits;

	private final SourceOptions options;

	private SourceInfo info;

	public CsvRowSource(Path file, ImportLimits limits, SourceOptions options) {
		this.file = file;
		this.limits = limits;
		this.options = options;
	}

	@Override
	public void read(Consumer<RowData> consumer) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(this.file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		CsvDetector.Encoding encoding = CsvDetector.forLabel(this.options.encoding(), bytes);
		if (encoding == null) {
			throw ApiException.badRequest("import.encoding_unreadable");
		}
		String text = decode(bytes, encoding);
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		int lineOffset = 0;
		char delimiter = 0;
		int sepLength = CsvDetector.sepLineLength(text);
		if (sepLength > 0) {
			delimiter = CsvDetector.sepLine(text);
			text = text.substring(sepLength);
			lineOffset = 1;
		}
		String chosen = this.options.delimiter();
		if (chosen != null && !chosen.isEmpty()) {
			delimiter = "\\t".equals(chosen) || "tab".equalsIgnoreCase(chosen) ? '\t' : chosen.charAt(0);
		}
		if (delimiter == 0) {
			delimiter = CsvDetector.detectDelimiter(text);
		}
		this.info = new RowSource.SourceInfo("CSV", encoding.label(), String.valueOf(delimiter), List.of(), null, false, 0,
				0);
		CSVFormat format = CSVFormat.DEFAULT.builder()
			.setDelimiter(delimiter)
			.setQuote('"')
			.setIgnoreEmptyLines(false)
			.setTrim(false)
			.setIgnoreSurroundingSpaces(false)
			.get();
		int dataRows = 0;
		boolean header = true;
		try (CSVParser parser = format.parse(new StringReader(text))) {
			for (CSVRecord record : parser) {
				if (Thread.currentThread().isInterrupted()) {
					throw new IllegalStateException("interrupted");
				}
				int size = record.size();
				if (size > this.limits.maxColumns() + 5 && !allBlank(record)) {
					throw ApiException.badRequest("import.too_many_columns", this.limits.maxColumns());
				}
				if (allBlank(record)) {
					continue;
				}
				List<String> cells = new ArrayList<>(size);
				int last = size;
				while (last > 0 && record.get(last - 1).isBlank()) {
					last--;
				}
				if (last > this.limits.maxColumns()) {
					throw ApiException.badRequest("import.too_many_columns", this.limits.maxColumns());
				}
				for (int i = 0; i < last; i++) {
					String cell = record.get(i);
					if (cell.length() > this.limits.maxCellChars()) {
						throw ApiException.badRequest("import.cell_too_long", this.limits.maxCellChars(),
								(int) record.getRecordNumber() + lineOffset);
					}
					cells.add(cell);
				}
				if (!header && ++dataRows > this.limits.maxRows()) {
					throw ApiException.badRequest("import.too_many_rows", this.limits.maxRows());
				}
				header = false;
				consumer.accept(new RowData((int) record.getRecordNumber() + lineOffset, cells, new BitSet(), List.of()));
			}
		}
		catch (IOException ex) {
			throw ApiException.badRequest("import.csv_malformed");
		}
		catch (UncheckedIOException | IllegalArgumentException ex) {
			throw ApiException.badRequest("import.csv_malformed");
		}
	}

	private static boolean allBlank(CSVRecord record) {
		for (int i = 0; i < record.size(); i++) {
			if (!record.get(i).isBlank()) {
				return false;
			}
		}
		return true;
	}

	private static String decode(byte[] bytes, CsvDetector.Encoding encoding) {
		boolean strict = encoding.charset() != CsvDetector.WINDOWS_1252;
		if (strict && !CsvDetector.decodesStrictly(bytes, encoding.bomLength(), encoding.charset())) {
			throw ApiException.badRequest("import.encoding_unreadable");
		}
		return new String(bytes, encoding.bomLength(), bytes.length - encoding.bomLength(), encoding.charset());
	}

	@Override
	public SourceInfo info() {
		return this.info;
	}

	@Override
	public void close() {
		// nothing held open: the file is read in one go
	}

}
