package com.nemaz.school.imports.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.ImportLimits;
import com.nemaz.school.imports.domain.RowSource.RowData;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxRowSourceTest {

	@TempDir
	Path dir;

	private List<RowData> read(Path file, SourceOptions options) {
		List<RowData> rows = new ArrayList<>();
		try (XlsxRowSource source = new XlsxRowSource(file, ImportLimits.DEFAULTS, options)) {
			source.read(rows::add);
			this.info = source.info();
		}
		return rows;
	}

	private com.nemaz.school.imports.domain.RowSource.SourceInfo info;

	private Path write(XSSFWorkbook workbook) throws IOException {
		Path file = this.dir.resolve("roster.xlsx");
		try (OutputStream out = Files.newOutputStream(file)) {
			workbook.write(out);
		}
		workbook.close();
		return file;
	}

	@Test
	void readsTextNumbersDatesAndKeepsPhoneNumbersIntact() throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		CreationHelper helper = wb.getCreationHelper();
		CellStyle date = wb.createCellStyle();
		date.setDataFormat(helper.createDataFormat().getFormat("m/d/yy"));
		CellStyle padded = wb.createCellStyle();
		padded.setDataFormat(helper.createDataFormat().getFormat("000000"));
		Sheet sheet = wb.createSheet("Roster");
		Row header = sheet.createRow(0);
		String[] names = { "given_name", "phone", "date_of_birth", "student_number", "scientific", "text_phone" };
		for (int i = 0; i < names.length; i++) {
			header.createCell(i).setCellValue(names[i]);
		}
		Row row = sheet.createRow(1);
		row.createCell(0).setCellValue("አበበ");
		row.createCell(1).setCellValue(911234567d);
		var dob = row.createCell(2);
		dob.setCellValue(java.sql.Date.valueOf(LocalDate.of(2012, 5, 14)));
		dob.setCellStyle(date);
		var number = row.createCell(3);
		number.setCellValue(1234d);
		number.setCellStyle(padded);
		row.createCell(4).setCellValue(9.11234567E8);
		row.createCell(5).setCellValue("0911234567");
		List<RowData> rows = read(write(wb), SourceOptions.DETECT);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).cells()).containsExactly(names);
		RowData data = rows.get(1);
		assertThat(data.rowNumber()).isEqualTo(2);
		assertThat(data.cells()).containsExactly("አበበ", "911234567", "2012-05-14", "001234", "911234567", "0911234567");
		assertThat(data.nativeDates().get(2)).as("the date cell is flagged as a real date").isTrue();
		assertThat(data.nativeDates().get(1)).isFalse();
		assertThat(this.info.sheet()).isEqualTo("Roster");
	}

	@Test
	void neverEvaluatesFormulasAndWarns() throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("Roster");
		sheet.createRow(0).createCell(0).setCellValue("name");
		Row row = sheet.createRow(1);
		row.createCell(0).setCellFormula("1+1");
		row.createCell(1).setCellValue("=cmd|' /C calc'!A0");
		wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
		List<RowData> rows = read(write(wb), SourceOptions.DETECT);
		assertThat(rows.get(1).cells()).containsExactly("2", "=cmd|' /C calc'!A0");
		assertThat(rows.get(1).warnings()).extracting((p) -> p.code()).containsExactly("formula_cell");
		assertThat(this.info.formulaCells()).isEqualTo(1);
	}

	@Test
	void mergedCellsGiveTheirValueToTheTopLeftCellOnlyAndWarn() throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet("Roster");
		sheet.createRow(0).createCell(0).setCellValue("class");
		sheet.getRow(0).createCell(1).setCellValue("name");
		Row row = sheet.createRow(1);
		row.createCell(0).setCellValue("8A");
		row.createCell(1).setCellValue("Abebe");
		Row next = sheet.createRow(2);
		next.createCell(1).setCellValue("Almaz");
		sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
		List<RowData> rows = read(write(wb), SourceOptions.DETECT);
		assertThat(rows.get(1).cells()).containsExactly("8A", "Abebe");
		assertThat(rows.get(2).cells()).containsExactly("", "Almaz");
		assertThat(rows.get(1).warnings()).extracting((p) -> p.code()).containsExactly("merged_cell");
		assertThat(rows.get(2).warnings()).extracting((p) -> p.code()).containsExactly("merged_cell");
		assertThat(this.info.mergedRegions()).isGreaterThan(0);
	}

	@Test
	void readsTheFirstVisibleSheetAndSkipsHiddenRows() throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		Sheet hidden = wb.createSheet("Notes");
		hidden.createRow(0).createCell(0).setCellValue("not the roster");
		Sheet roster = wb.createSheet("Roster");
		roster.createRow(0).createCell(0).setCellValue("name");
		roster.createRow(1).createCell(0).setCellValue("kept");
		roster.createRow(2).createCell(0).setCellValue("hidden row");
		roster.getRow(2).setZeroHeight(true);
		roster.createRow(3).createCell(0).setCellValue("also kept");
		wb.createSheet("Other").createRow(0).createCell(0).setCellValue("other");
		wb.setSheetHidden(0, true);
		Path file = write(wb);
		List<RowData> rows = read(file, SourceOptions.DETECT);
		assertThat(rows).extracting((r) -> r.cells().getFirst()).containsExactly("name", "kept", "also kept");
		assertThat(rows.get(2).rowNumber()).isEqualTo(4);
		assertThat(this.info.sheetNames()).containsExactly("Roster", "Other");
		// the user may pick another sheet, by name
		assertThat(read(file, new SourceOptions(null, null, "Other"))).extracting((r) -> r.cells().getFirst())
			.containsExactly("other");
		assertThatThrownBy(() -> read(file, new SourceOptions(null, null, "Missing"))).extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.sheet_not_found");
	}

	@Test
	void theDate1904FlagIsReported() throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		wb.getCTWorkbook().addNewWorkbookPr().setDate1904(true);
		Sheet sheet = wb.createSheet("Roster");
		sheet.createRow(0).createCell(0).setCellValue("dob");
		CellStyle date = wb.createCellStyle();
		date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
		var cell = sheet.createRow(1).createCell(0);
		cell.setCellValue(40000d);
		cell.setCellStyle(date);
		List<RowData> rows = read(write(wb), SourceOptions.DETECT);
		assertThat(this.info.date1904()).isTrue();
		// 40000 days after 1 January 1904 (not after 30 December 1899)
		assertThat(rows.get(1).cells().getFirst()).isEqualTo(LocalDate.of(1904, 1, 1).plusDays(40000).toString());
	}

	@Test
	void anOldBinaryWorkbookAndAMacroWorkbookAreRefused() throws IOException {
		Path xls = this.dir.resolve("old.xls");
		Files.write(xls, new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0 });
		assertThatThrownBy(() -> RowSources.open(xls, "old.xlsx", ImportLimits.DEFAULTS, SourceOptions.DETECT))
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.unsupported_file");
		assertThatThrownBy(() -> RowSources.open(xls, "old.xls", ImportLimits.DEFAULTS, SourceOptions.DETECT))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> RowSources.open(xls, "macros.xlsm", ImportLimits.DEFAULTS, SourceOptions.DETECT))
			.isInstanceOf(ApiException.class);

		XSSFWorkbook wb = new XSSFWorkbook();
		wb.createSheet("Roster").createRow(0).createCell(0).setCellValue("name");
		Path plain = write(wb);
		Path macro = this.dir.resolve("macro.xlsx");
		try (ZipFile in = new ZipFile(plain.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(macro))) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				out.putNextEntry(new ZipEntry(e.getName()));
				in.getInputStream(e).transferTo(out);
				out.closeEntry();
			}
			out.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
			out.write(new byte[] { 1, 2, 3 });
			out.closeEntry();
		}
		assertThatThrownBy(() -> read(macro, SourceOptions.DETECT)).extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.unsupported_file");
	}

	@Test
	void zipBombsAreStoppedBeforeAndWhileReading() throws IOException {
		// (a) an entry that declares more than the limit is refused before POI opens the file
		Path declared = bomb("declared.xlsx", 120L * 1024 * 1024);
		assertThatThrownBy(() -> read(declared, SourceOptions.DETECT)).extracting((e) -> ((ApiException) e).code())
			.isEqualTo("import.zip_limits");
		// (b) an entry under the size limit that inflates absurdly (ratio far below 1%) is stopped while inflating
		Path ratio = bomb("ratio.xlsx", 60L * 1024 * 1024);
		assertThatThrownBy(() -> read(ratio, SourceOptions.DETECT)).isInstanceOf(ApiException.class)
			.extracting((e) -> ((ApiException) e).code())
			.isIn("import.zip_limits", "import.unsupported_file");
	}

	/** A workbook whose sheet XML is {@code size} bytes of one repeated cell: a few kilobytes on disk. */
	private Path bomb(String name, long size) throws IOException {
		XSSFWorkbook wb = new XSSFWorkbook();
		wb.createSheet("Roster").createRow(0).createCell(0).setCellValue("name");
		Path plain = write(wb);
		Path file = this.dir.resolve(name);
		try (ZipFile in = new ZipFile(plain.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				out.putNextEntry(new ZipEntry(e.getName()));
				if (e.getName().equals("xl/worksheets/sheet1.xml")) {
					byte[] head = "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>"
						.getBytes();
					byte[] chunk = new byte[1024 * 1024];
					java.util.Arrays.fill(chunk, (byte) 'a');
					out.write(head);
					long written = head.length;
					while (written < size) {
						out.write(chunk);
						written += chunk.length;
					}
					out.write("</t></is></c></row></sheetData></worksheet>".getBytes());
				}
				else {
					in.getInputStream(e).transferTo(out);
				}
				out.closeEntry();
			}
		}
		assertThat(Files.size(file)).as("a bomb is small on disk").isLessThan(1024 * 1024);
		return file;
	}

}
