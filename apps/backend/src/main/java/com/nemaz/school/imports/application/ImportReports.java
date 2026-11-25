package com.nemaz.school.imports.application;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.ImportModel.Status;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The downloadable error report: the file's own columns followed by {@code row_number}, {@code severity}
 * ({@code error} or {@code warning}), {@code field} and a message in the reader's language, one line per problem.
 * UTF-8 with a byte order mark and CRLF line ends so that Excel shows Amharic and keeps the rows apart. Cells that a
 * spreadsheet would run as a formula (they start with {@code = + - @ tab CR}) get a leading apostrophe.
 */
@Service
@Transactional
public class ImportReports {

	private static final int PAGE = 500;

	private final ImportService jobs;

	private final ImportStore store;

	private final ProblemMessages messages;

	private final SchoolGate gate;

	private final AuditLog audit;

	ImportReports(ImportService jobs, ImportStore store, ProblemMessages messages, SchoolGate gate, AuditLog audit) {
		this.jobs = jobs;
		this.store = store;
		this.messages = messages;
		this.gate = gate;
		this.audit = audit;
	}

	public record Report(String filename, byte[] content) {
	}

	public Report errorReport(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = this.jobs.load(schoolId, jobId);
		if (job.rowsPurgedAt() != null) {
			throw ApiException.conflict("import.rows_purged");
		}
		if (job.status() == Status.UPLOADED || job.status() == Status.PREVIEWED || job.status() == Status.VALIDATING) {
			throw ApiException.conflict("import.invalid_state");
		}
		Locale locale = LocaleContextHolder.getLocale();
		StringWriter out = new StringWriter();
		int lines = 0;
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get())) {
			List<String> header = new ArrayList<>(job.headers());
			header.add("row_number");
			header.add("severity");
			header.add("field");
			header.add("message");
			printer.printRecord(header.stream().map(ImportReports::neutralize).toList());
			int after = 0;
			List<Row> page;
			do {
				page = this.store.rowsWithProblems(jobId, after, PAGE);
				for (Row row : page) {
					lines += print(printer, job, row, locale);
					after = row.rowNumber();
				}
			}
			while (page.size() == PAGE);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		this.audit.record(AuditEntry.of("import.error_report", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("lines", lines));
		byte[] body = ("﻿" + out).getBytes(StandardCharsets.UTF_8);
		return new Report(job.type().name().toLowerCase(Locale.ROOT).replace('_', '-') + "-import-report.csv", body);
	}

	private int print(CSVPrinter printer, Job job, Row row, Locale locale) throws IOException {
		int lines = 0;
		for (Map<String, Object> p : row.errors()) {
			printLine(printer, job, row, "error", p, locale);
			lines++;
		}
		for (Map<String, Object> p : row.warnings()) {
			printLine(printer, job, row, "warning", p, locale);
			lines++;
		}
		if (row.status() == ImportModel.RowStatus.DUPLICATE && row.resolution() == null) {
			printLine(printer, job, row, "warning",
					Map.of("code", "possible_duplicate", "args", List.of(String.valueOf(row.candidates().size()))), locale);
			lines++;
		}
		return lines;
	}

	@SuppressWarnings("unchecked")
	private void printLine(CSVPrinter printer, Job job, Row row, String severity, Map<String, Object> problem, Locale locale)
			throws IOException {
		List<String> record = new ArrayList<>();
		List<String> cells = (row.raw() != null) ? row.raw() : List.of();
		for (int i = 0; i < job.headers().size(); i++) {
			record.add(neutralize((i < cells.size()) ? cells.get(i) : ""));
		}
		String code = String.valueOf(problem.get("code"));
		List<Object> args = (problem.get("args") instanceof List<?> l) ? (List<Object>) l : List.of();
		record.add(String.valueOf(row.rowNumber()));
		record.add(severity);
		record.add(neutralize((String) problem.get("column")));
		record.add(neutralize(this.messages.row(code, args, locale)));
		printer.printRecord(record);
	}

	/** A cell a spreadsheet would run as a formula gets a leading apostrophe (OWASP CSV injection). */
	static String neutralize(String cell) {
		if (cell == null) {
			return "";
		}
		if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
			return "'" + cell;
		}
		return cell;
	}

}
