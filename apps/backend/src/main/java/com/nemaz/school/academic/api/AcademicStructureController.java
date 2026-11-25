package com.nemaz.school.academic.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.application.AcademicStructureService;
import com.nemaz.school.academic.application.AcademicStructureService.AcademicPeriodView;
import com.nemaz.school.academic.application.AcademicStructureService.AcademicYearView;
import com.nemaz.school.academic.application.AcademicStructureService.GradeLevelView;
import com.nemaz.school.academic.application.AcademicStructureService.PeriodType;
import com.nemaz.school.academic.application.AcademicStructureService.SubjectView;
import com.nemaz.school.academic.application.AcademicStructureService.YearStatus;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Academic years, periods, grade levels and subjects of a school. Reading is open to the school's members
 * (staff, students, administrators); writing needs {@code school.academic.manage}. A caller who cannot see the
 * school gets 404, a member without the permission 403.
 */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}")
@Tag(name = "academic-structure")
public class AcademicStructureController {

	private static final String HEX_COLOR = "^#[0-9a-fA-F]{6}$";

	private final AcademicStructureService service;

	private final SchoolGate gate;

	AcademicStructureController(AcademicStructureService service, SchoolGate gate) {
		this.service = service;
		this.gate = gate;
	}

	// --- academic years ---------------------------------------------------------------------

	@GetMapping("/academic-years")
	@Operation(operationId = "listAcademicYears", summary = "The school's academic years, newest first (a short list, no paging)")
	public List<AcademicYearView> years(@PathVariable UUID schoolId) {
		this.gate.requireVisible(CurrentUser.require(), schoolId);
		return this.service.years(schoolId);
	}

	@PostMapping("/academic-years")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createAcademicYear",
			summary = "Create an academic year (PLANNED unless stated); makeCurrent also makes it the school's current year")
	public AcademicYearView createYear(@PathVariable UUID schoolId, @Valid @RequestBody CreateYearRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.createYear(schoolId, r.name(), r.startsOn(), r.endsOn(), r.status(),
				Boolean.TRUE.equals(r.makeCurrent()));
	}

	@GetMapping("/academic-years/{yearId}")
	@Operation(operationId = "getAcademicYear", summary = "One academic year")
	public AcademicYearView year(@PathVariable UUID schoolId, @PathVariable UUID yearId) {
		this.gate.requireVisible(CurrentUser.require(), schoolId);
		return this.service.year(schoolId, yearId);
	}

	@PatchMapping("/academic-years/{yearId}")
	@Operation(operationId = "updateAcademicYear",
			summary = "Change name, dates or status (absent fields stay); 409 when the new dates cut off a period or the current year would be closed")
	public AcademicYearView updateYear(@PathVariable UUID schoolId, @PathVariable UUID yearId,
			@Valid @RequestBody UpdateYearRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.updateYear(schoolId, yearId, r.name(), r.startsOn(), r.endsOn(), r.status());
	}

	@PostMapping("/academic-years/{yearId}/make-current")
	@Operation(operationId = "makeAcademicYearCurrent",
			summary = "Make this the school's only current year (the previous current year stays as it is; a CLOSED year cannot be current)")
	public AcademicYearView makeCurrent(@PathVariable UUID schoolId, @PathVariable UUID yearId) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.makeCurrent(schoolId, yearId);
	}

	@DeleteMapping("/academic-years/{yearId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteAcademicYear", summary = "Delete a year with its periods; 409 when it is current or classes use it")
	public void deleteYear(@PathVariable UUID schoolId, @PathVariable UUID yearId) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		this.service.deleteYear(schoolId, yearId);
	}

	// --- periods ----------------------------------------------------------------------------

	@GetMapping("/academic-years/{yearId}/periods")
	@Operation(operationId = "listAcademicPeriods", summary = "The year's periods in sequence order")
	public List<AcademicPeriodView> periods(@PathVariable UUID schoolId, @PathVariable UUID yearId) {
		this.gate.requireVisible(CurrentUser.require(), schoolId);
		return this.service.periods(schoolId, yearId);
	}

	@PostMapping("/academic-years/{yearId}/periods")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createAcademicPeriod",
			summary = "Add a period: within the year, no overlap with other periods (409 academic.period_overlap), unique sequence (defaults to the next)")
	public AcademicPeriodView createPeriod(@PathVariable UUID schoolId, @PathVariable UUID yearId,
			@Valid @RequestBody CreatePeriodRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.createPeriod(schoolId, yearId, r.name(), r.periodType(), r.sequence(), r.startsOn(),
				r.endsOn());
	}

	@PatchMapping("/academic-years/{yearId}/periods/{periodId}")
	@Operation(operationId = "updateAcademicPeriod", summary = "Change a period (same rules as creating one)")
	public AcademicPeriodView updatePeriod(@PathVariable UUID schoolId, @PathVariable UUID yearId,
			@PathVariable UUID periodId, @Valid @RequestBody UpdatePeriodRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.updatePeriod(schoolId, yearId, periodId, r.name(), r.periodType(), r.sequence(),
				r.startsOn(), r.endsOn());
	}

	@DeleteMapping("/academic-years/{yearId}/periods/{periodId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteAcademicPeriod", summary = "Delete a period")
	public void deletePeriod(@PathVariable UUID schoolId, @PathVariable UUID yearId, @PathVariable UUID periodId) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		this.service.deletePeriod(schoolId, yearId, periodId);
	}

	// --- grade levels -----------------------------------------------------------------------

	@GetMapping("/grade-levels")
	@Operation(operationId = "listGradeLevels", summary = "The school's grade levels in sequence order")
	public List<GradeLevelView> grades(@PathVariable UUID schoolId) {
		this.gate.requireVisible(CurrentUser.require(), schoolId);
		return this.service.grades(schoolId);
	}

	@PostMapping("/grade-levels")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createGradeLevel", summary = "Add a grade level (sequence defaults to the next; unique per school)")
	public GradeLevelView createGrade(@PathVariable UUID schoolId, @Valid @RequestBody CreateGradeRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.createGrade(schoolId, r.name(), r.code(), r.sequence());
	}

	@PatchMapping("/grade-levels/{gradeId}")
	@Operation(operationId = "updateGradeLevel", summary = "Change a grade level, or deactivate it")
	public GradeLevelView updateGrade(@PathVariable UUID schoolId, @PathVariable UUID gradeId,
			@Valid @RequestBody UpdateGradeRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.updateGrade(schoolId, gradeId, r.name(), r.code(), r.sequence(), r.active());
	}

	@DeleteMapping("/grade-levels/{gradeId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteGradeLevel", summary = "Delete a grade level; 409 when classes use it (deactivate it instead)")
	public void deleteGrade(@PathVariable UUID schoolId, @PathVariable UUID gradeId) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		this.service.deleteGrade(schoolId, gradeId);
	}

	// --- subjects ---------------------------------------------------------------------------

	@GetMapping("/subjects")
	@Operation(operationId = "listSubjects", summary = "The school's subjects by name")
	public List<SubjectView> subjects(@PathVariable UUID schoolId) {
		this.gate.requireVisible(CurrentUser.require(), schoolId);
		return this.service.subjects(schoolId);
	}

	@PostMapping("/subjects")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createSubject", summary = "Add a subject (code unique per school, case-insensitive)")
	public SubjectView createSubject(@PathVariable UUID schoolId, @Valid @RequestBody CreateSubjectRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.createSubject(schoolId, r.name(), r.code(), r.color());
	}

	@PatchMapping("/subjects/{subjectId}")
	@Operation(operationId = "updateSubject", summary = "Change a subject, or deactivate it")
	public SubjectView updateSubject(@PathVariable UUID schoolId, @PathVariable UUID subjectId,
			@Valid @RequestBody UpdateSubjectRequest r) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		return this.service.updateSubject(schoolId, subjectId, r.name(), r.code(), r.color(), r.active());
	}

	@DeleteMapping("/subjects/{subjectId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteSubject", summary = "Delete a subject; 409 when classes use it (deactivate it instead)")
	public void deleteSubject(@PathVariable UUID schoolId, @PathVariable UUID subjectId) {
		this.gate.require(CurrentUser.require(), schoolId, Permissions.SCHOOL_ACADEMIC_MANAGE);
		this.service.deleteSubject(schoolId, subjectId);
	}

	// --- requests ---------------------------------------------------------------------------

	public record CreateYearRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotNull(message = "validation.required") LocalDate startsOn,
			@NotNull(message = "validation.required") LocalDate endsOn, YearStatus status, Boolean makeCurrent) {
	}

	public record UpdateYearRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			LocalDate startsOn, LocalDate endsOn, YearStatus status) {
	}

	public record CreatePeriodRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotNull(message = "validation.required") PeriodType periodType,
			@Min(value = 1, message = "validation.invalid") @Max(value = 100, message = "validation.invalid") Integer sequence,
			@NotNull(message = "validation.required") LocalDate startsOn,
			@NotNull(message = "validation.required") LocalDate endsOn) {
	}

	public record UpdatePeriodRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			PeriodType periodType,
			@Min(value = 1, message = "validation.invalid") @Max(value = 100, message = "validation.invalid") Integer sequence,
			LocalDate startsOn, LocalDate endsOn) {
	}

	public record CreateGradeRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Min(value = 0, message = "validation.invalid") @Max(value = 1000, message = "validation.invalid") Integer sequence) {
	}

	public record UpdateGradeRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Min(value = 0, message = "validation.invalid") @Max(value = 1000, message = "validation.invalid") Integer sequence,
			Boolean active) {
	}

	public record CreateSubjectRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Pattern(regexp = HEX_COLOR, message = "validation.invalid") String color) {
	}

	public record UpdateSubjectRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			@Size(max = 32, message = "validation.tooLong") String code,
			@Pattern(regexp = HEX_COLOR, message = "validation.invalid") String color, Boolean active) {
	}

}
