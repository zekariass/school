package com.nemaz.school.imports.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.ImportLimits;
import com.nemaz.school.imports.domain.RowSource;
import com.nemaz.school.imports.domain.RowSource.RowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvRowSourceTest {

	@TempDir
	Path dir;

	private List<RowData> read(byte[] content, SourceOptions options, ImportLimits limits) throws IOException {
		Path file = this.dir.resolve("roster.csv");
		Files.write(file, content);
		List<RowData> rows = new ArrayList<>();
		try (CsvRowSource source = new CsvRowSource(file, limits, options)) {
			source.read(rows::add);
			lastInfo = source.info();
		}
		return rows;
	}

	private RowSource.SourceInfo lastInfo;

	private List<RowData> read(byte[] content) throws IOException {
		return read(content, SourceOptions.DETECT, ImportLimits.DEFAULTS);
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void utf8WithByteOrderMarkAndAmharicNames() throws IOException {
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(bom);
		out.write(utf8("given_name,father_name,phone\r\nአበበ,በቀለ,0911234567\r\n\"Almaz, Jr\",Tesfaye,\r\n"));
		List<RowData> rows = read(out.toByteArray());
		assertThat(this.lastInfo.encoding()).isEqualTo("UTF-8");
		assertThat(this.lastInfo.delimiter()).isEqualTo(",");
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).cells()).containsExactly("given_name", "father_name", "phone");
		assertThat(rows.get(1).cells()).containsExactly("አበበ", "በቀለ", "0911234567");
		assertThat(rows.get(2).cells()).containsExactly("Almaz, Jr", "Tesfaye");
		assertThat(rows.get(1).rowNumber()).isEqualTo(2);
	}

	@Test
	void excelUnicodeTextIsUtf16LittleEndianWithTabs() throws IOException {
		String text = "﻿given_name\tfather_name\tdate_of_birth\r\nአበበ\tበቀለ\t2012-05-14\r\n";
		List<RowData> rows = read(text.getBytes(StandardCharsets.UTF_16LE));
		assertThat(this.lastInfo.encoding()).isEqualTo("UTF-16LE");
		assertThat(this.lastInfo.delimiter()).isEqualTo("\t");
		assertThat(rows.get(1).cells()).containsExactly("አበበ", "በቀለ", "2012-05-14");
	}

	@Test
	void utf16WithoutAMarkIsRecognisedByItsZeroBytes() throws IOException {
		List<RowData> rows = read("a,b\r\n1,2\r\n3,4\r\n5,6\r\n".getBytes(StandardCharsets.UTF_16BE));
		assertThat(this.lastInfo.encoding()).isEqualTo("UTF-16BE");
		assertThat(rows).hasSize(4);
	}

	@Test
	void windows1252WhenTheBytesAreNotUtf8() throws IOException {
		byte[] content = "name;city\r\nJosé;Zürich\r\n".getBytes(Charset.forName("windows-1252"));
		List<RowData> rows = read(content);
		assertThat(this.lastInfo.encoding()).isEqualTo("windows-1252");
		assertThat(this.lastInfo.delimiter()).isEqualTo(";");
		assertThat(rows.get(1).cells()).containsExactly("José", "Zürich");
	}

	@Test
	void semicolonAndPipeDelimitersAreDetectedOutsideQuotes() throws IOException {
		List<RowData> semicolon = read(utf8("a;b;c\n\"x;y\";2;3\n4;5;6\n"));
		assertThat(this.lastInfo.delimiter()).isEqualTo(";");
		assertThat(semicolon.get(1).cells()).containsExactly("x;y", "2", "3");
		read(utf8("a|b|c\n1|2|3\n"));
		assertThat(this.lastInfo.delimiter()).isEqualTo("|");
	}

	@Test
	void aSepLineChoosesTheDelimiterAndKeepsRowNumbersOfTheFile() throws IOException {
		List<RowData> rows = read(utf8("sep=;\r\na;b\r\n1;2\r\n"));
		assertThat(this.lastInfo.delimiter()).isEqualTo(";");
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).rowNumber()).as("the sep line is line 1").isEqualTo(2);
		assertThat(rows.get(1).rowNumber()).isEqualTo(3);
	}

	@Test
	void theUserMayOverrideEncodingAndDelimiter() throws IOException {
		List<RowData> rows = read(utf8("a,b;c\n1,2;3\n"), new SourceOptions("UTF-8", ";", null), ImportLimits.DEFAULTS);
		assertThat(rows.get(0).cells()).containsExactly("a,b", "c");
		assertThatThrownBy(() -> read(new byte[] { (byte) 0xE9, ',', 'a', '\n' }, new SourceOptions("UTF-8", null, null),
				ImportLimits.DEFAULTS)).isInstanceOf(ApiException.class)
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.encoding_unreadable");
		assertThatThrownBy(() -> read(utf8("a\n"), new SourceOptions("klingon", null, null), ImportLimits.DEFAULTS))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void blankLinesAreSkippedAndEmbeddedLineBreaksStayInTheCell() throws IOException {
		List<RowData> rows = read(utf8("a,b\n\n1,\"two\nlines\"\n,\n"));
		assertThat(rows).hasSize(2);
		assertThat(rows.get(1).cells()).containsExactly("1", "two\nlines");
	}

	@Test
	void limitsOnRowsColumnsAndCellLength() {
		ImportLimits small = new ImportLimits(1024 * 1024, 3, 4, 10, 100, 1024, 4096, 1000, ImportLimits.DEFAULTS.parseTimeout());
		assertThatThrownBy(() -> read(utf8("a\n1\n2\n3\n4\n"), SourceOptions.DETECT, small)).isInstanceOf(ApiException.class)
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.too_many_rows");
		assertThatThrownBy(() -> read(utf8("a,b,c,d,e\n1,2,3,4,5\n"), SourceOptions.DETECT, small))
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.too_many_columns");
		assertThatThrownBy(() -> read(utf8("a\n0123456789ab\n"), SourceOptions.DETECT, small))
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.cell_too_long");
		// exactly at the limit is fine
		assertThat(catchRows(utf8("a\n1\n2\n3\n"), small)).hasSize(4);
	}

	private List<RowData> catchRows(byte[] content, ImportLimits limits) {
		try {
			return read(content, SourceOptions.DETECT, limits);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
