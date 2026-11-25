package com.nemaz.school.imports.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.tenancy.TenantDirectory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Downloadable templates, one per import type. The header row holds the machine keys (stable, English); the
 * instructions are on a separate sheet in English and Amharic (a row of instructions above the header gets edited
 * or deleted); the example row starts with {@code #}, which the importer skips. In the workbook phone, id and date
 * columns are text (a spreadsheet would drop leading zeros and rewrite dates), lists limit gender, staff type,
 * class type and relationship, and the names of the school's existing classes, grade levels and campuses are offered
 * where a file refers to them.
 */
@Service
@Transactional(readOnly = true)
public class ImportTemplates {

	public record Template(String filename, String contentType, byte[] content) {
	}

	private static final int ROWS = 2000;

	private static final Locale EN = Locale.ENGLISH;

	private static final Locale AM = Locale.forLanguageTag("am");

	private static final Set<String> TEXT_FIELDS = Set.of("phone", "lead_teacher_phone", "student_number", "external_id", "code",
			"date_of_birth", "guardian1_phone", "guardian2_phone", "sequence", "year", "username");

	private static final Map<String, List<String>> FIXED_LISTS = Map.of("gender", List.of("MALE", "FEMALE"), "staff_type",
			List.of("TEACHER", "ADMINISTRATOR", "SUPPORT_STAFF"), "class_type", List.of("HOMEROOM", "SUBJECT", "CLUB", "OTHER"),
			"guardian1_relationship", List.of("MOTHER", "FATHER", "GRANDPARENT", "SIBLING", "AUNT_UNCLE", "LEGAL_GUARDIAN", "OTHER"),
			"guardian2_relationship", List.of("MOTHER", "FATHER", "GRANDPARENT", "SIBLING", "AUNT_UNCLE", "LEGAL_GUARDIAN", "OTHER"));

	private final SchoolGate gate;

	private final ProblemMessages messages;

	private final StructureImports structure;

	private final TenantDirectory directory;

	ImportTemplates(SchoolGate gate, ProblemMessages messages, StructureImports structure, TenantDirectory directory) {
		this.gate = gate;
		this.messages = messages;
		this.structure = structure;
		this.directory = directory;
	}

	public Template template(CurrentUser user, UUID schoolId, ImportType type, String format) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		String base = type.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-template";
		return "xlsx".equalsIgnoreCase(format)
				? new Template(base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
						xlsx(schoolId, type))
				: new Template(base + ".csv", "text/csv; charset=UTF-8", csv(type));
	}

	byte[] csv(ImportType type) {
		StringWriter out = new StringWriter();
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get())) {
			printer.printRecord(type.fields().stream().map(ImportType.Field::key).toList());
			printer.printRecord(example(type));
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		return ("﻿" + out).getBytes(StandardCharsets.UTF_8);
	}

	byte[] xlsx(UUID schoolId, ImportType type) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet data = workbook.createSheet("Data");
			Sheet instructions = workbook.createSheet("Instructions");
			CellStyle header = headerStyle(workbook);
			CellStyle text = workbook.createCellStyle();
			text.setDataFormat(workbook.createDataFormat().getFormat("@"));
			CellStyle exampleStyle = workbook.createCellStyle();
			exampleStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
			Font italic = workbook.createFont();
			italic.setItalic(true);
			italic.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
			exampleStyle.setFont(italic);
			List<ImportType.Field> fields = type.fields();
			Row head = data.createRow(0);
			Row example = data.createRow(1);
			List<String> sample = example(type);
			for (int i = 0; i < fields.size(); i++) {
				String key = fields.get(i).key();
				if (TEXT_FIELDS.contains(key)) {
					data.setDefaultColumnStyle(i, text);
				}
				var cell = head.createCell(i);
				cell.setCellValue(key);
				cell.setCellStyle(header);
				var sampleCell = example.createCell(i);
				sampleCell.setCellValue(sample.get(i));
				sampleCell.setCellStyle(exampleStyle);
				data.setColumnWidth(i, Math.min(40, Math.max(16, key.length() + 4)) * 256);
			}
			data.createFreezePane(0, 1);
			addLists(workbook, data, schoolId, type, fields);
			writeInstructions(instructions, type, header);
			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private void addLists(XSSFWorkbook workbook, Sheet data, UUID schoolId, ImportType type, List<ImportType.Field> fields) {
		DataValidationHelper helper = data.getDataValidationHelper();
		Sheet lists = null;
		int listColumn = 0;
		for (int i = 0; i < fields.size(); i++) {
			String key = fields.get(i).key();
			List<String> values = FIXED_LISTS.get(key);
			DataValidationConstraint constraint = null;
			if (values != null) {
				constraint = helper.createExplicitListConstraint(values.toArray(String[]::new));
			}
			else {
				List<String> names = switch (key) {
					case "class", "class_name" -> this.structure.classes(schoolId)
						.stream()
						.filter((c) -> "ACTIVE".equals(c.status()))
						.map(StructureImports.ClassInfo::name)
						.distinct()
						.toList();
					case "grade_level" -> this.structure.gradeLevels(schoolId).stream().map(StructureImports.GradeInfo::name).toList();
					case "campus" -> this.directory.campusesOf(schoolId).stream()
						.filter((c) -> "ACTIVE".equals(c.status())).map(TenantDirectory.CampusEntry::name).toList();
					default -> List.of();
				};
				if (!names.isEmpty()) {
					if (lists == null) {
						lists = workbook.createSheet("Lists");
					}
					for (int r = 0; r < names.size(); r++) {
						Row row = (lists.getRow(r) != null) ? lists.getRow(r) : lists.createRow(r);
						row.createCell(listColumn).setCellValue(names.get(r));
					}
					String column = org.apache.poi.ss.util.CellReference.convertNumToColString(listColumn);
					constraint = helper.createFormulaListConstraint("Lists!$" + column + "$1:$" + column + "$" + names.size());
					listColumn++;
				}
			}
			if (constraint != null) {
				DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(2, ROWS, i, i));
				validation.setShowErrorBox(false);
				validation.setSuppressDropDownArrow(false);
				data.addValidationData(validation);
			}
		}
		if (lists != null) {
			workbook.setSheetHidden(workbook.getSheetIndex(lists), true);
		}
	}

	private void writeInstructions(Sheet sheet, ImportType type, CellStyle header) {
		int row = 0;
		sheet.setColumnWidth(0, 28 * 256);
		sheet.setColumnWidth(1, 70 * 256);
		sheet.setColumnWidth(2, 70 * 256);
		for (Locale locale : List.of(EN, AM)) {
			Row title = sheet.createRow(row++);
			var cell = title.createCell(0);
			cell.setCellValue(this.messages.text("import.template.title", locale) + " - " + this.messages.text(
					"import.type." + type.name().toLowerCase(Locale.ROOT), locale));
			cell.setCellStyle(header);
			for (String key : lines(type)) {
				sheet.createRow(row++).createCell(0).setCellValue(this.messages.text(key, locale));
			}
			row++;
			Row columns = sheet.createRow(row++);
			columns.createCell(0).setCellValue("key");
			columns.createCell(1).setCellValue(this.messages.text("import.template.column", locale));
			columns.createCell(2).setCellValue(this.messages.text("import.template.needed", locale));
			for (var cellRef : List.of(columns.getCell(0), columns.getCell(1), columns.getCell(2))) {
				cellRef.setCellStyle(header);
			}
			for (ImportType.Field field : type.fields()) {
				Row r = sheet.createRow(row++);
				r.createCell(0).setCellValue(field.key());
				r.createCell(1).setCellValue(this.messages.text("import.field." + field.key(), locale));
				r.createCell(2).setCellValue(this.messages.text(field.required() ? "import.template.required_mark"
						: (field.oneOf() != null ? "import.template.one_of_mark" : "import.template.optional_mark"), locale));
			}
			row += 2;
		}
	}

	private static List<String> lines(ImportType type) {
		List<String> lines = new ArrayList<>(List.of("import.template.intro", "import.template.example", "import.template.required",
				"import.template.phone", "import.template.dates", "import.template.limits", "import.template.order"));
		if (type == ImportType.STUDENTS) {
			lines.add("import.template.guardians");
		}
		if (type == ImportType.STAFF) {
			lines.add("import.template.admin");
		}
		return lines;
	}

	/** One sample row, aligned with the fields; its first cell starts with # so the importer skips it. */
	static List<String> example(ImportType type) {
		List<String> values = switch (type) {
			case GRADE_LEVELS -> List.of("#Grade 1", "G1", "1");
			case SUBJECTS -> List.of("#Mathematics", "MATH", "#1e88e5");
			case CLASSES -> List.of("#8A", "Grade 8", "", "", "HOMEROOM", "0911000001", "");
			case STAFF -> List.of("#Abebe", "Kebede", "Tadesse", "0911000001", "", "TEACHER", "Mathematics teacher", "", "EMP-001");
			case STUDENTS -> List.of("#Almaz", "Tesfaye", "Bekele", "2012-05-14", "FEMALE", "S-0001", "EXT-0001", "8A", "", "",
					"Tesfaye Bekele", "0911000002", "", "FATHER", "", "", "", "");
			case ENROLLMENTS -> List.of("#S-0001", "", "8A");
		};
		return values;
	}

	private static CellStyle headerStyle(XSSFWorkbook workbook) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		style.setBorderBottom(BorderStyle.THIN);
		return style;
	}

}
