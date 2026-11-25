package com.nemaz.school.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.attendance.application.AbsenceReportService;
import com.nemaz.school.attendance.application.AbsenceReportService.AbsenceDecisionRequest;
import com.nemaz.school.attendance.application.AbsenceReportService.AbsenceReportRequest;
import com.nemaz.school.attendance.application.AbsenceReportService.AbsenceReportView;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Guardians report absences ahead of time; teachers acknowledge or decline. See {@link AbsenceReportService}. */
@RestController
@Tag(name = "absence-reports")
public class AbsenceReportController {

	private final AbsenceReportService reports;

	AbsenceReportController(AbsenceReportService reports) {
		this.reports = reports;
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/absence-reports")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "reportAbsence",
			summary = "A guardian (ACTIVE, not custody-restricted link) reports that the child will be away from..to (school-local dates, at most 7 days back, 90 ahead, 61 long) for ILLNESS, FAMILY, APPOINTMENT or OTHER, with an optional note (health related: never shown to the child, other guardians or assistants, never in notifications). The student's teachers are notified without the reason. 404 for anybody who is not the child's guardian; at most 20 a day (429)")
	public AbsenceReportView report(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody AbsenceReportRequest request) {
		return this.reports.create(CurrentUser.require(), schoolId, studentId, request);
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/absence-reports")
	@Operation(operationId = "listStudentAbsenceReports",
			summary = "The absence reports of a student, newest first, keyset paged: a guardian sees the ones they filed (with reason and note); the student's lead, co and subject teachers and school.attendance.manage see all (with reason and note). 403 for other teachers and attendance readers, 404 for everybody else")
	public CursorPage<AbsenceReportView> forStudent(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reports.forStudent(CurrentUser.require(), schoolId, studentId, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/absence-reports")
	@Operation(operationId = "listClassAbsenceReports",
			summary = "Absence reports of the students ACTIVE in the class touching from..to (default a week back to 60 days ahead), SUBMITTED and ACKNOWLEDGED unless status says otherwise, oldest first, keyset paged. Reason and note only for the lead, co and subject teachers and attendance managers. Teachers of the class, class managers, school.attendance.read/manage")
	public CursorPage<AbsenceReportView> forClass(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.reports.forClass(CurrentUser.require(), schoolId, classId, status, from, to, cursor, limit);
	}

	@PostMapping("/api/v1/schools/{schoolId}/absence-reports/{reportId}/acknowledge")
	@Operation(operationId = "acknowledgeAbsenceReport",
			summary = "A lead, co or subject teacher of the student's class (or an attendance manager) acknowledges a SUBMITTED report: the roll shows it for those dates and markAllPresent pre-fills EXCUSED. Audited. 409 absence.already_decided")
	public AbsenceReportView acknowledge(@PathVariable UUID schoolId, @PathVariable UUID reportId,
			@Valid @RequestBody(required = false) AbsenceDecisionRequest request) {
		return this.reports.acknowledge(CurrentUser.require(), schoolId, reportId,
				(request != null) ? request.note() : null);
	}

	@PostMapping("/api/v1/schools/{schoolId}/absence-reports/{reportId}/decline")
	@Operation(operationId = "declineAbsenceReport",
			summary = "Declines a SUBMITTED report (same people as acknowledge), with an optional note for the record. Audited. 409 absence.already_decided")
	public AbsenceReportView decline(@PathVariable UUID schoolId, @PathVariable UUID reportId,
			@Valid @RequestBody(required = false) AbsenceDecisionRequest request) {
		return this.reports.decline(CurrentUser.require(), schoolId, reportId, (request != null) ? request.note() : null);
	}

	@PostMapping("/api/v1/schools/{schoolId}/absence-reports/{reportId}/cancel")
	@Operation(operationId = "cancelAbsenceReport",
			summary = "The guardian who filed a SUBMITTED or ACKNOWLEDGED report withdraws it")
	public AbsenceReportView cancel(@PathVariable UUID schoolId, @PathVariable UUID reportId) {
		return this.reports.cancel(CurrentUser.require(), schoolId, reportId);
	}

}
