package com.nemaz.school.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.attendance.application.AttendanceReports;
import com.nemaz.school.attendance.application.AttendanceReports.AttendanceReportRow;
import com.nemaz.school.attendance.application.AttendanceReports.ExportFile;
import com.nemaz.school.attendance.application.AttendanceReports.PeriodReportRow;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance summary per student and class over a date range, as JSON pages and as a CSV file. */
@RestController
@Tag(name = "attendance-reports")
public class AttendanceReportController {

	static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

	private final AttendanceReports reports;

	AttendanceReportController(AttendanceReports reports) {
		this.reports = reports;
	}

	@GetMapping("/api/v1/schools/{schoolId}/attendance/report")
	@Operation(operationId = "getAttendanceReport",
			summary = "Marks counted per student and class over from..to (school-local dates, inclusive, default the last 30 days, at most 400), keyset paged in student name order. With classId: teachers of that class and school.attendance.read/manage; without: school.attendance.read/manage (studentId narrows to one student). Teachers' notes and absence reports are not part of it")
	public CursorPage<AttendanceReportRow> report(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reports.report(CurrentUser.require(), schoolId, classId, studentId, from, to, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/attendance/report.csv")
	@Operation(operationId = "exportAttendanceReport",
			summary = "The same report as a CSV download: UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language, spreadsheet formulas neutralized, sorted by student name. Audited, rate limited (10 per 10 minutes, 429 attendance.export_rate_limited), at most 50000 rows",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> export(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		ExportFile file = this.reports.export(CurrentUser.require(), schoolId, classId, studentId, from, to);
		return csv(file);
	}

	@GetMapping("/api/v1/schools/{schoolId}/attendance/period-report")
	@Operation(operationId = "getAttendancePeriodReport",
			summary = "The per-period summary: marks counted per class, slot and subject over from..to (school-local dates, inclusive, default the last 30 days, at most 400), keyset paged in class name, slot, subject order. slot and subjectId narrow it; rolls taken without a subject have none. With classId: teachers of that class and school.attendance.read/manage; without: school.attendance.read/manage (403 for everybody else who sees the school, 404 for the rest). 400 attendance.invalid_slot")
	public CursorPage<PeriodReportRow> periodReport(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) String slot, @RequestParam(required = false) UUID subjectId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reports.periodReport(CurrentUser.require(), schoolId, classId, slot, subjectId, from, to, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/attendance/period-report.csv")
	@Operation(operationId = "exportAttendancePeriodReport",
			summary = "The per-period summary as a CSV download: UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language, spreadsheet formulas neutralized. Audited (attendance.period_report.export), rate limited with the other attendance exports (10 per 10 minutes, 429 attendance.export_rate_limited), at most 50000 rows",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> exportPeriodReport(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) String slot, @RequestParam(required = false) UUID subjectId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		ExportFile file = this.reports.exportPeriodReport(CurrentUser.require(), schoolId, classId, slot, subjectId, from, to);
		return csv(file);
	}

	static ResponseEntity<byte[]> csv(ExportFile file) {
		return ResponseEntity.ok()
			.contentType(CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.filename()).build().toString())
			.cacheControl(CacheControl.noStore())
			.header("X-Content-Type-Options", "nosniff")
			.body(file.content());
	}

}
