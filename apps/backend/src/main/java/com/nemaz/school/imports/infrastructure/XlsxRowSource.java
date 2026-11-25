package com.nemaz.school.imports.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.ImportLimits;
import com.nemaz.school.imports.domain.Problem;
import com.nemaz.school.imports.domain.RowSource;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads an XLSX workbook with POI's event (SAX) API: nothing is loaded as a {@code XSSFWorkbook}. The first visible
 * sheet is read unless the user chose one; hidden sheets and hidden rows are skipped; a merged range gives its
 * value to the top-left cell only (the other cells are empty) and a warning on the rows it touches; formulas are
 * never evaluated, the cached result is read and the row gets a warning. Numbers come out as the user sees them:
 * date cells as ISO dates (flagged in {@link RowData#nativeDates}), numbers formatted with their own format
 * ({@code 001234}), long numbers without scientific notation ({@code 911234567}, not {@code 9.11234567E8}).
 * <p>
 * Safety: the archive is checked before POI opens it (entry count, declared sizes, macro part, OLE2 binary
 * format) and POI's {@link ZipSecureFile} limits (inflate ratio, entry size, text size) guard the actual
 * decompression; XML is parsed with POI's hardened reader (no external entities).
 */
public final class XlsxRowSource implements RowSource {

	static {
		// Process-wide POI limits (the research note): a zip bomb is refused while it is being read.
		ZipSecureFile.setMinInflateRatio(0.01);
		ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
		ZipSecureFile.setMaxTextSize(10_000_000L);
		ZipSecureFile.setMaxFileCount(1_000L);
	}

	private static final Pattern CELL_REF = Pattern.compile("^([A-Z]+)([0-9]+)$");

	private final Path file;

	private final ImportLimits limits;

	private final SourceOptions options;

	private OPCPackage pkg;

	private SourceInfo info;

	public XlsxRowSource(Path file, ImportLimits limits, SourceOptions options) {
		this.file = file;
		this.limits = limits;
		this.options = options;
	}

	/** Checks the archive's structure; every refusal is a 400 {@code import.unsupported_file} or {@code import.zip_limits}. */
	static void preflight(Path file, ImportLimits limits) {
		try (ZipFile zip = new ZipFile(file.toFile())) {
			int entries = 0;
			long total = 0;
			boolean contentTypes = false;
			boolean workbook = false;
			Enumeration<? extends ZipEntry> all = zip.entries();
			while (all.hasMoreElements()) {
				ZipEntry entry = all.nextElement();
				entries++;
				long size = entry.getSize();
				if (entries > limits.maxZipEntries() || size > limits.maxZipEntryBytes()
						|| (total += Math.max(0, size)) > limits.maxZipTotalBytes()) {
					throw ApiException.badRequest("import.zip_limits");
				}
				String name = entry.getName().toLowerCase(Locale.ROOT);
				if (name.endsWith("vbaproject.bin") || name.contains("/macros/") || name.endsWith(".bin") && name.startsWith("xl/")
						&& name.contains("vba")) {
					throw ApiException.badRequest("import.unsupported_file");
				}
				contentTypes |= name.equals("[content_types].xml");
				workbook |= name.equals("xl/workbook.xml");
			}
			if (!contentTypes || !workbook) {
				throw ApiException.badRequest("import.unsupported_file");
			}
		}
		catch (IOException ex) {
			throw ApiException.badRequest("import.unsupported_file");
		}
	}

	@Override
	public void read(Consumer<RowData> consumer) {
		preflight(this.file, this.limits);
		try {
			this.pkg = OPCPackage.open(this.file.toFile(), PackageAccess.READ);
			if (!this.pkg.getPartsByContentType("application/vnd.ms-excel.sheet.macroEnabled.main+xml").isEmpty()
					|| !this.pkg.getPartsByContentType("application/vnd.ms-office.vbaProject").isEmpty()) {
				throw ApiException.badRequest("import.unsupported_file");
			}
			XSSFReader reader = new XSSFReader(this.pkg);
			Workbook workbook = readWorkbook(reader);
			Sheet chosen = chooseSheet(workbook);
			ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(this.pkg, false);
			StylesTable styles = reader.getStylesTable();
			Map<Integer, List<String>> merged = mergedRanges(reader, chosen.name());
			SheetHandler handler = new SheetHandler(strings, styles, workbook.date1904(), merged, consumer);
			try (InputStream sheet = openSheet(reader, chosen.name())) {
				XMLReader xml = xmlReader();
				xml.setContentHandler(handler);
				xml.parse(new InputSource(sheet));
			}
			this.info = new SourceInfo("XLSX", null, null, workbook.visibleNames(), chosen.name(), workbook.date1904(),
					handler.formulaCells, merged.size());
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (CellTooLong ex) {
			throw ApiException.badRequest("import.cell_too_long", this.limits.maxCellChars(), ex.row());
		}
		catch (SAXException ex) {
			if (ex.getCause() instanceof ApiException api) {
				throw api;
			}
			if (ex.getCause() instanceof CellTooLong tooLong) {
				throw ApiException.badRequest("import.cell_too_long", this.limits.maxCellChars(), tooLong.row());
			}
			if (ex.getCause() instanceof IllegalStateException interrupted) {
				throw interrupted;
			}
			throw ApiException.badRequest("import.unsupported_file");
		}
		catch (OpenXML4JException | IOException | RuntimeException ex) {
			if (ex instanceof IllegalStateException && "interrupted".equals(ex.getMessage())) {
				throw (IllegalStateException) ex;
			}
			String message = String.valueOf(ex.getMessage());
			throw ApiException.badRequest(message.toLowerCase(Locale.ROOT).contains("zip bomb")
					|| message.toLowerCase(Locale.ROOT).contains("limit") ? "import.zip_limits" : "import.unsupported_file");
		}
	}

	@Override
	public SourceInfo info() {
		return this.info;
	}

	@Override
	public void close() {
		if (this.pkg != null) {
			this.pkg.revert();
			this.pkg = null;
		}
	}

	// --- workbook ---------------------------------------------------------------------------

	private record Sheet(String name, boolean hidden) {
	}

	private record Workbook(List<Sheet> sheets, boolean date1904) {

		List<String> visibleNames() {
			return this.sheets.stream().filter((s) -> !s.hidden()).map(Sheet::name).toList();
		}

	}

	private Workbook readWorkbook(XSSFReader reader) throws IOException, SAXException, InvalidFormatException {
		List<Sheet> sheets = new ArrayList<>();
		boolean[] date1904 = new boolean[1];
		try (InputStream in = reader.getWorkbookData()) {
			XMLReader xml = xmlReader();
			xml.setContentHandler(new DefaultHandler() {
				@Override
				public void startElement(String uri, String local, String qName, Attributes attrs) {
					if (local.equals("workbookPr")) {
						String flag = attrs.getValue("date1904");
						date1904[0] = "1".equals(flag) || "true".equalsIgnoreCase(flag);
					}
					else if (local.equals("sheet")) {
						String state = attrs.getValue("state");
						sheets.add(new Sheet(attrs.getValue("name"), state != null && !"visible".equals(state)));
					}
				}
			});
			xml.parse(new InputSource(in));
		}
		if (sheets.isEmpty()) {
			throw ApiException.badRequest("import.unsupported_file");
		}
		return new Workbook(sheets, date1904[0]);
	}

	private Sheet chooseSheet(Workbook workbook) {
		String wanted = this.options.sheet();
		if (wanted != null && !wanted.isBlank()) {
			return workbook.sheets()
				.stream()
				.filter((s) -> s.name().equalsIgnoreCase(wanted.strip()))
				.findFirst()
				.orElseThrow(() -> ApiException.badRequest("import.sheet_not_found"));
		}
		return workbook.sheets()
			.stream()
			.filter((s) -> !s.hidden())
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("import.unsupported_file"));
	}

	private InputStream openSheet(XSSFReader reader, String name) throws IOException, InvalidFormatException {
		XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
		while (sheets.hasNext()) {
			InputStream in = sheets.next();
			if (sheets.getSheetName().equals(name)) {
				return in;
			}
			in.close();
		}
		throw ApiException.badRequest("import.sheet_not_found");
	}

	/** Row number to the merged ranges touching that row ("A1:C1"), from a first light pass over the sheet. */
	private Map<Integer, List<String>> mergedRanges(XSSFReader reader, String sheetName)
			throws IOException, SAXException, InvalidFormatException {
		Map<Integer, List<String>> byRow = new HashMap<>();
		try (InputStream in = openSheet(reader, sheetName)) {
			XMLReader xml = xmlReader();
			xml.setContentHandler(new DefaultHandler() {
				@Override
				public void startElement(String uri, String local, String qName, Attributes attrs) {
					if (local.equals("mergeCell") && attrs.getValue("ref") != null) {
						String ref = attrs.getValue("ref");
						String[] ends = ref.split(":");
						if (ends.length == 2) {
							Matcher a = CELL_REF.matcher(ends[0]);
							Matcher b = CELL_REF.matcher(ends[1]);
							if (a.matches() && b.matches()) {
								int from = Integer.parseInt(a.group(2));
								int to = Math.min(Integer.parseInt(b.group(2)), from + 1_000);
								for (int row = from; row <= to; row++) {
									byRow.computeIfAbsent(row, (r) -> new ArrayList<>()).add(ref);
								}
							}
						}
					}
				}
			});
			xml.parse(new InputSource(in));
		}
		return byRow;
	}

	// --- sheet ------------------------------------------------------------------------------

	private final class SheetHandler extends DefaultHandler {

		private final ReadOnlySharedStringsTable strings;

		private final StylesTable styles;

		private final boolean date1904;

		private final Map<Integer, List<String>> merged;

		private final Consumer<RowData> consumer;

		private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

		int formulaCells;

		private int rowNumber;

		private boolean hiddenRow;

		private List<String> cells = new ArrayList<>();

		private BitSet nativeDates = new BitSet();

		private List<Problem> warnings = new ArrayList<>();

		private int dataRows;

		private boolean sawHeader;

		// current cell
		private int column;

		private String cellType;

		private int styleIndex;

		private boolean hasFormula;

		private final StringBuilder value = new StringBuilder();

		private boolean inValue;

		private boolean inInlineText;

		private boolean inPhonetic;

		SheetHandler(ReadOnlySharedStringsTable strings, StylesTable styles, boolean date1904,
				Map<Integer, List<String>> merged, Consumer<RowData> consumer) {
			this.strings = strings;
			this.styles = styles;
			this.date1904 = date1904;
			this.merged = merged;
			this.consumer = consumer;
		}

		@Override
		public void startElement(String uri, String local, String qName, Attributes attrs) throws SAXException {
			switch (local) {
				case "row" -> {
					String r = attrs.getValue("r");
					this.rowNumber = (r != null) ? Integer.parseInt(r) : this.rowNumber + 1;
					String hidden = attrs.getValue("hidden");
					this.hiddenRow = "1".equals(hidden) || "true".equalsIgnoreCase(hidden);
					this.cells = new ArrayList<>();
					this.nativeDates = new BitSet();
					this.warnings = new ArrayList<>();
				}
				case "c" -> {
					Matcher m = CELL_REF.matcher(attrs.getValue("r") == null ? "" : attrs.getValue("r"));
					this.column = m.matches() ? columnIndex(m.group(1)) : this.cells.size();
					this.cellType = attrs.getValue("t");
					String s = attrs.getValue("s");
					this.styleIndex = (s != null) ? Integer.parseInt(s) : 0;
					this.hasFormula = false;
					this.value.setLength(0);
				}
				case "f" -> this.hasFormula = true;
				case "v" -> {
					this.inValue = true;
					this.value.setLength(0);
				}
				case "is" -> {
					this.inInlineText = true;
					this.value.setLength(0);
				}
				case "rPh" -> this.inPhonetic = true;
				default -> {
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			if ((this.inValue || this.inInlineText) && !this.inPhonetic) {
				this.value.append(ch, start, length);
				if (this.value.length() > XlsxRowSource.this.limits.maxCellChars() * 4) {
					throw new CellTooLong(this.rowNumber);
				}
			}
		}

		@Override
		public void endElement(String uri, String local, String qName) throws SAXException {
			switch (local) {
				case "v" -> this.inValue = false;
				case "is" -> this.inInlineText = false;
				case "rPh" -> this.inPhonetic = false;
				case "c" -> endCell();
				case "row" -> endRow();
				default -> {
				}
			}
		}

		private void endCell() {
			if (this.column >= XlsxRowSource.this.limits.maxColumns() + 5) {
				return;
			}
			String text = cellText();
			if (this.hasFormula) {
				this.formulaCells++;
				this.warnings.add(Problem.of(null, "formula_cell", cellRef()));
			}
			if (text.length() > XlsxRowSource.this.limits.maxCellChars()) {
				throw new CellTooLong(this.rowNumber);
			}
			if (!text.isBlank() || this.column < this.cells.size()) {
				while (this.cells.size() <= this.column) {
					this.cells.add("");
				}
				this.cells.set(this.column, text);
			}
		}

		private String cellRef() {
			return columnLetters(this.column) + this.rowNumber;
		}

		private String cellText() {
			String raw = this.value.toString();
			String type = (this.cellType == null) ? "n" : this.cellType;
			switch (type) {
				case "s" -> {
					try {
						return this.strings.getItemAt(Integer.parseInt(raw.strip())).getString();
					}
					catch (RuntimeException ex) {
						return "";
					}
				}
				case "inlineStr", "str" -> {
					return raw;
				}
				case "b" -> {
					return "1".equals(raw.strip()) ? "TRUE" : "FALSE";
				}
				case "e" -> {
					this.warnings.add(Problem.of(null, "cell_error", cellRef()));
					return "";
				}
				case "d" -> {
					this.nativeDates.set(this.column);
					return raw.strip();
				}
				default -> {
					return number(raw.strip());
				}
			}
		}

		private String number(String raw) {
			if (raw.isEmpty()) {
				return "";
			}
			double numeric;
			try {
				numeric = Double.parseDouble(raw);
			}
			catch (NumberFormatException ex) {
				return raw;
			}
			XSSFCellStyle style = (this.styleIndex < this.styles.getNumCellStyles()) ? this.styles.getStyleAt(this.styleIndex)
					: null;
			short format = (style != null) ? style.getDataFormat() : 0;
			String formatString = (style != null) ? style.getDataFormatString() : "General";
			if (formatString != null && DateUtil.isADateFormat(format, formatString) && DateUtil.isValidExcelDate(numeric)) {
				this.nativeDates.set(this.column);
				LocalDateTime when = DateUtil.getLocalDateTime(numeric, this.date1904);
				return (when.toLocalTime().toSecondOfDay() == 0) ? when.toLocalDate().toString()
						: when.toLocalDate() + "T" + when.toLocalTime().withNano(0);
			}
			String plain = plain(raw);
			if (formatString == null || "General".equalsIgnoreCase(formatString) || "@".equals(formatString)) {
				return plain;
			}
			try {
				String shown = this.formatter.formatRawCellContents(numeric, format, formatString).strip();
				// A format that falls back to scientific notation or a symbol would damage ids and phone numbers.
				return (shown.isEmpty() || shown.matches(".*[eE][+-]?[0-9]+.*") || shown.contains("#")) ? plain : shown;
			}
			catch (RuntimeException ex) {
				return plain;
			}
		}

		private void endRow() throws SAXException {
			if (Thread.currentThread().isInterrupted()) {
				throw new SAXException("interrupted", new IllegalStateException("interrupted"));
			}
			if (this.hiddenRow) {
				return;
			}
			int last = this.cells.size();
			while (last > 0 && this.cells.get(last - 1).isBlank()) {
				last--;
			}
			if (last == 0) {
				return;
			}
			if (last > XlsxRowSource.this.limits.maxColumns()) {
				throw new SAXException(ApiException.badRequest("import.too_many_columns", XlsxRowSource.this.limits.maxColumns()));
			}
			if (!this.sawHeader) {
				this.sawHeader = true;
			}
			else if (++this.dataRows > XlsxRowSource.this.limits.maxRows()) {
				throw new SAXException(ApiException.badRequest("import.too_many_rows", XlsxRowSource.this.limits.maxRows()));
			}
			List<Problem> rowWarnings = new ArrayList<>(this.warnings);
			List<String> ranges = this.merged.get(this.rowNumber);
			if (ranges != null) {
				rowWarnings.add(Problem.of(null, "merged_cell", String.join(", ", ranges)));
			}
			this.consumer.accept(new RowData(this.rowNumber, this.cells.subList(0, last), this.nativeDates, dedupe(rowWarnings)));
		}

		@Override
		public void endDocument() {
			// nothing to flush: rows are emitted when they end
		}

	}

	/** One warning per code and argument. */
	private static List<Problem> dedupe(List<Problem> problems) {
		return List.copyOf(new java.util.LinkedHashSet<>(problems));
	}

	/** Thrown from inside the SAX callbacks (which cannot declare it); mapped to the API error below. */
	private static final class CellTooLong extends RuntimeException {

		private final int row;

		CellTooLong(int row) {
			super("import.cell_too_long");
			this.row = row;
		}

		int row() {
			return this.row;
		}

	}

	/** POI's hardened XML reader: no external entities, no DTDs. */
	private static XMLReader xmlReader() throws SAXException {
		try {
			return XMLHelper.newXMLReader();
		}
		catch (javax.xml.parsers.ParserConfigurationException ex) {
			throw new SAXException(ex);
		}
	}

	/** Plain decimal text of a stored number: no exponent, no trailing zeros ("9.11234567E8" is "911234567"). */
	static String plain(String raw) {
		try {
			BigDecimal value = new BigDecimal(raw);
			return (value.signum() == 0) ? "0" : value.stripTrailingZeros().toPlainString();
		}
		catch (NumberFormatException ex) {
			return raw;
		}
	}

	static int columnIndex(String letters) {
		int index = 0;
		for (int i = 0; i < letters.length(); i++) {
			index = index * 26 + (letters.charAt(i) - 'A' + 1);
		}
		return index - 1;
	}

	static String columnLetters(int index) {
		StringBuilder out = new StringBuilder();
		for (int n = index + 1; n > 0; n = (n - 1) / 26) {
			out.insert(0, (char) ('A' + (n - 1) % 26));
		}
		return out.toString();
	}

}
