package com.nemaz.school.engagement.api;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.engagement.application.PointReports;
import com.nemaz.school.engagement.application.PointReports.ExportFile;
import com.nemaz.school.engagement.application.PointReports.ReportRow;
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

/** Points per student and category over a date range, as JSON pages and as a CSV file. */
@RestController
@Tag(name = "points-reports")
public class PointReportController {

	private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

	private final PointReports reports;

	PointReportController(PointReports reports) {
		this.reports = reports;
	}

	@GetMapping("/api/v1/schools/{schoolId}/points/report")
	@Operation(operationId = "getPointsReport",
			summary = "Net points per student and category for CLASS and SCHOOL points (home points are never included), over from..to (school-local dates, inclusive), keyset paged in student then category id order. With classId: teachers of that class and school.points.read; without: school.points.read")
	public CursorPage<ReportRow> report(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.reports.report(CurrentUser.require(), schoolId, classId, from, to, categoryId, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/points/report.csv")
	@Operation(operationId = "exportPointsReport",
			summary = "The same report as a CSV download: UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language, spreadsheet formulas neutralized, sorted by student name. Audited, rate limited (10 per 10 minutes, 429 points.export_rate_limited), at most 50000 rows",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> export(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID categoryId) {
		ExportFile file = this.reports.export(CurrentUser.require(), schoolId, classId, from, to, categoryId);
		return ResponseEntity.ok()
			.contentType(CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.filename()).build().toString())
			.cacheControl(CacheControl.noStore())
			.header("X-Content-Type-Options", "nosniff")
			.body(file.content());
	}

}
