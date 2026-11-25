package com.nemaz.school.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollRequest;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollView;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceWriteResult;
import com.nemaz.school.attendance.application.RollService;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.attendance.application.AttendanceViews;
import com.nemaz.school.attendance.application.AttendanceViews.AttendanceDashboard;
import com.nemaz.school.attendance.application.AttendanceViews.AttendanceHistoryItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rolls, a student's attendance history and the daily dashboard. */
@RestController
@Tag(name = "attendance")
public class RollController {

	private final RollService rolls;

	private final AttendanceViews views;

	RollController(RollService rolls, AttendanceViews views) {
		this.rolls = rolls;
		this.views = views;
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/attendance")
	@Operation(operationId = "getClassAttendance",
			summary = "The roll of a class for a school-local date (default today) and slot (default the school's first): every student who belonged to the class that day with their record, suggestedStatus (EXCUSED for an acknowledged absence report) and the absence-report annotation (reason and note only for the lead, co and subject teachers and attendance managers). ACTIVE teachers of the class, class managers and school.attendance.read/manage (403 for students and guardians who see the class, 404 for everybody else)")
	public AttendanceRollView roll(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) String slot) {
		return this.rolls.roll(CurrentUser.require(), schoolId, classId, date, slot);
	}

	@PutMapping("/api/v1/schools/{schoolId}/classes/{classId}/attendance")
	@Operation(operationId = "takeClassAttendance",
			summary = "Takes or corrects a roll: records are upserted (students left out stay as they are, unmarked stay unmarked), markAllPresent marks every student without a record present (EXCUSED when an acknowledged absence report covers the date). A mark that differs from the stored record is written only when it carries the record's current version (expectedVersion) or the roll's baseVersion is current; otherwise it is a CONFLICT and nothing is overwritten. 200 with per-student outcomes; 409 with the same body when some marks conflicted (the others were written). Refused before writing anything: date in the future (409 attendance.date_in_future), student not in the class that day (409 attendance.student_not_in_class), slot not enabled, locked roll without the right or the reason (409 attendance.session_locked, 400 attendance.override_reason_required), archived class (409 class.archived). Teachers of the class (assistants while the school allows it), class managers, school.attendance.manage")
	public ResponseEntity<AttendanceWriteResult> take(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody AttendanceRollRequest request) {
		AttendanceWriteResult result = this.rolls.write(CurrentUser.require(), schoolId, classId, request);
		return ResponseEntity.status((result.conflicts() > 0) ? HttpStatus.CONFLICT : HttpStatus.OK).body(result);
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/attendance")
	@Operation(operationId = "listStudentAttendance",
			summary = "A student's attendance history, newest first, keyset paged, optionally by class and school-local date range. The student and their ACTIVE guardians with can_view_academics see status, lateness, date and class (no teacher note); teachers see the classes they teach (with notes); school.attendance.read sees everything. 403 for others who can see the student, 404 for the rest")
	public CursorPage<AttendanceHistoryItem> history(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID classId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.views.history(CurrentUser.require(), schoolId, studentId, from, to, classId, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/attendance/dashboard")
	@Operation(operationId = "getAttendanceDashboard",
			summary = "The daily dashboard: per class (keyset paged by name) whether the roll was taken for the date and slot, students, marked, unmarked and the counts per status, plus school totals including the classes that have not taken attendance. school.attendance.read/manage see every class, teachers the classes they teach; students and guardians get 403")
	public AttendanceDashboard dashboard(@PathVariable UUID schoolId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) String slot, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.views.dashboard(CurrentUser.require(), schoolId, date, slot, cursor, limit);
	}

}
