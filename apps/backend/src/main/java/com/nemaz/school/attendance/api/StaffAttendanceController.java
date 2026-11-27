package com.nemaz.school.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.attendance.application.AttendanceReports.ExportFile;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffCorrectionRequest;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDailyReport;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayRequest;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayResult;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayRoll;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffMonthlyReport;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffRecordDetail;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffRecordView;
import com.nemaz.school.attendance.application.StaffAttendanceReports;
import com.nemaz.school.attendance.application.StaffAttendanceService;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff attendance, marked by hand (M7d). Every call needs the school setting {@code attendance.staff.enabled} (403
 * {@code staff_attendance.disabled}) and the feature {@code attendance}; a staff member reads their own records, nobody
 * reads a colleague's without {@code school.staff_attendance.read} or {@code .manage}. See {@link StaffAttendanceService}.
 */
@RestController
@Tag(name = "staff-attendance")
public class StaffAttendanceController {

	private static final String BASE = "/api/v1/schools/{schoolId}/staff-attendance";

	private final StaffAttendanceService service;

	private final StaffAttendanceReports reports;

	StaffAttendanceController(StaffAttendanceService service, StaffAttendanceReports reports) {
		this.service = service;
		this.reports = reports;
	}

	@GetMapping(BASE + "/days/{date}")
	@Operation(operationId = "getStaffAttendanceDay",
			summary = "The staff roll of a school-local day: ACTIVE staff (and anybody who already has a record that day) with the day's record, keyset paged by name, optionally one campus. school.staff_attendance.read/manage (403 staff_attendance.access_denied for other members of the school, 404 for people who cannot see it); 403 staff_attendance.disabled while the school setting attendance.staff.enabled is off")
	public StaffDayRoll day(@PathVariable UUID schoolId,
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) UUID campusId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.service.day(CurrentUser.require(), schoolId, date, campusId, cursor, limit);
	}

	@PutMapping(BASE + "/days/{date}")
	@Operation(operationId = "markStaffAttendanceDay",
			summary = "Marks staff for a school-local day (manual): records lists staff (PRESENT, ABSENT, LATE with minutesLate 1 to 600, HALF_DAY, EXCUSED, an optional note of at most 200 characters), markRestPresent marks every other ACTIVE staff member of the day without a record PRESENT. A staff member who already has a record keeps it (outcome EXISTS): change it with a correction. Everything is validated first: unknown staff 404 staff_attendance.staff_not_found, staff who are not ACTIVE 409 staff_attendance.staff_not_active, a day that has not started in the staff member's campus or school time zone 409 staff_attendance.date_in_future, more than 62 days back 400 staff_attendance.date_too_old, 400 for duplicates, too many records (500) or bad minutes. Audited. school.staff_attendance.manage")
	public StaffDayResult mark(@PathVariable UUID schoolId,
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@Valid @RequestBody StaffDayRequest request) {
		return this.service.mark(CurrentUser.require(), schoolId, date, request);
	}

	@GetMapping(BASE + "/records")
	@Operation(operationId = "listStaffAttendance",
			summary = "Staff attendance records over from..to (school-local dates, inclusive, default the last 30 days, at most 400), newest first, keyset paged; staffId narrows to one staff member. school.staff_attendance.read/manage only: a teacher or any other member gets 403 staff_attendance.access_denied (their own records are under /me)")
	public CursorPage<StaffRecordView> list(@PathVariable UUID schoolId, @RequestParam(required = false) UUID staffId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.service.list(CurrentUser.require(), schoolId, staffId, from, to, cursor, limit);
	}

	@GetMapping(BASE + "/me")
	@Operation(operationId = "listMyStaffAttendance",
			summary = "The caller's own staff attendance records (any ACTIVE staff member of the school), newest first, keyset paged, optionally from..to. 403 staff_attendance.access_denied for members who are not staff, 404 for people who cannot see the school")
	public CursorPage<StaffRecordView> mine(@PathVariable UUID schoolId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.service.mine(CurrentUser.require(), schoolId, from, to, cursor, limit);
	}

	@GetMapping(BASE + "/records/{recordId}")
	@Operation(operationId = "getStaffAttendanceRecord",
			summary = "One record with its corrections: school.staff_attendance.read/manage and the staff member the record belongs to. Anybody else, a colleague included, gets 404 staff_attendance.record_not_found")
	public StaffRecordDetail record(@PathVariable UUID schoolId, @PathVariable UUID recordId) {
		return this.service.record(CurrentUser.require(), schoolId, recordId);
	}

	@PostMapping(BASE + "/records/{recordId}/corrections")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "correctStaffAttendance",
			summary = "Corrects a record: the new status (minutesLate only with LATE) and optionally a new note, with the required reason (at most 200 characters) and the record's version (expectedVersion; 409 staff_attendance.version_conflict when it changed). The correction is appended (never edited or deleted) and audited; 400 staff_attendance.nothing_to_correct when nothing changes. school.staff_attendance.manage")
	public StaffRecordDetail correct(@PathVariable UUID schoolId, @PathVariable UUID recordId,
			@Valid @RequestBody StaffCorrectionRequest request) {
		return this.service.correct(CurrentUser.require(), schoolId, recordId, request);
	}

	@GetMapping(BASE + "/reports/daily")
	@Operation(operationId = "getStaffAttendanceDailyReport",
			summary = "Staff attendance counted per school-local day over from..to (default the last 30 days, at most 93): one row per day that has records. school.staff_attendance.read/manage")
	public StaffDailyReport daily(@PathVariable UUID schoolId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return this.reports.daily(CurrentUser.require(), schoolId, from, to);
	}

	@GetMapping(BASE + "/reports/daily.csv")
	@Operation(operationId = "exportStaffAttendanceDailyReport",
			summary = "The daily summary as a CSV download (UTF-8 with a byte order mark, CRLF, headers in the caller's language). Audited (staff_attendance.report.export), rate limited (10 per 10 minutes, 429 staff_attendance.export_rate_limited). school.staff_attendance.read/manage",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> exportDaily(@PathVariable UUID schoolId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		ExportFile file = this.reports.exportDaily(CurrentUser.require(), schoolId, from, to);
		return AttendanceReportController.csv(file);
	}

	@GetMapping(BASE + "/reports/monthly")
	@Operation(operationId = "getStaffAttendanceMonthlyReport",
			summary = "Staff attendance counted per staff member for a month (month=yyyy-MM, default the school's current month; 400 staff_attendance.invalid_month): ACTIVE staff and anybody with a record that month, keyset paged by name. school.staff_attendance.read/manage")
	public StaffMonthlyReport monthly(@PathVariable UUID schoolId, @RequestParam(required = false) String month,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reports.monthly(CurrentUser.require(), schoolId, month, cursor, limit);
	}

	@GetMapping(BASE + "/reports/monthly.csv")
	@Operation(operationId = "exportStaffAttendanceMonthlyReport",
			summary = "The monthly summary as a CSV download, with the same rules as the daily one (audited, rate limited, at most 50000 rows). school.staff_attendance.read/manage",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> exportMonthly(@PathVariable UUID schoolId, @RequestParam(required = false) String month) {
		ExportFile file = this.reports.exportMonthly(CurrentUser.require(), schoolId, month);
		return AttendanceReportController.csv(file);
	}

}
