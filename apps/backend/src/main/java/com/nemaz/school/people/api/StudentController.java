package com.nemaz.school.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.people.application.LoginCodeService;
import com.nemaz.school.people.application.LoginCodeService.LoginCodeInfo;
import com.nemaz.school.people.application.StudentService;
import com.nemaz.school.people.application.StudentService.CreateStudent;
import com.nemaz.school.people.application.StudentService.StudentFilter;
import com.nemaz.school.people.application.StudentService.StudentStatus;
import com.nemaz.school.people.application.StudentService.StudentView;
import com.nemaz.school.people.application.StudentService.UpdateStudent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A school's students (school-managed accounts). Responses carry {@code accessLevel}: BASIC (name, avatar) or FULL
 * (adds date of birth, gender, username, number, classes); see {@link StudentService}.
 */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/students")
@Tag(name = "students")
public class StudentController {

	private final StudentService students;

	private final LoginCodeService codes;

	StudentController(StudentService students, LoginCodeService codes) {
		this.students = students;
		this.codes = codes;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createStudent",
			summary = "Create a school-managed student (no phone or email) and enroll them; optionally straight into a class. Needs school.students.manage, or be a teacher of that class")
	public StudentView create(@PathVariable UUID schoolId, @Valid @RequestBody CreateStudentRequest r) {
		return this.students.create(CurrentUser.require(), schoolId, new CreateStudent(r.givenName(), r.middleName(),
				r.familyName(), r.dateOfBirth(), r.gender(), r.studentNumber(), r.campusId(), r.username(),
				r.avatarConfig(), r.classId(), r.admissionOn()));
	}

	@GetMapping
	@Operation(operationId = "listStudents",
			summary = "Students by name (ACTIVE unless status is given); filters classId, gradeLevelId, campusId, status, q. School.students.manage sees all, teachers the students of their classes")
	public CursorPage<StudentView> list(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) UUID gradeLevelId, @RequestParam(required = false) UUID campusId,
			@RequestParam(required = false) StudentStatus status, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.students.list(CurrentUser.require(), schoolId,
				new StudentFilter(classId, gradeLevelId, campusId, status, q), cursor, limit);
	}

	@GetMapping("/{studentId}")
	@Operation(operationId = "getStudent",
			summary = "One student: FULL for managers, the student and their teachers; BASIC (name, avatar) for classmates and class administrators; 404 for everybody else")
	public StudentView get(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.students.get(CurrentUser.require(), schoolId, studentId);
	}

	@PatchMapping("/{studentId}")
	@Operation(operationId = "updateStudent",
			summary = "Change a student's details (school.students.manage); the student themself may change only avatarConfig")
	public StudentView update(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody UpdateStudentRequest r) {
		return this.students.update(CurrentUser.require(), schoolId, studentId, new UpdateStudent(r.givenName(),
				r.middleName(), r.familyName(), r.dateOfBirth(), r.gender(), r.studentNumber(), r.campusId(),
				r.username(), r.avatarConfig(), r.admissionOn()));
	}

	@PostMapping("/{studentId}/withdraw")
	@Operation(operationId = "withdrawStudent",
			summary = "Withdraw a student from the school: class memberships end, login codes are revoked, history stays")
	public StudentView withdraw(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody(required = false) WithdrawRequest r) {
		return this.students.withdraw(CurrentUser.require(), schoolId, studentId, (r != null) ? r.reason() : null);
	}

	@PostMapping("/{studentId}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "setStudentPassword",
			summary = "Set an initial password (the student must change it); only while the school allows password sign-in (409 student.password_login_disabled) and the student has a username")
	public void setPassword(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody SetPasswordRequest r) {
		this.students.setPassword(CurrentUser.require(), schoolId, studentId, r.password());
	}

	@GetMapping("/{studentId}/login-codes")
	@Operation(operationId = "listStudentLoginCodes",
			summary = "Metadata of the student's live login codes (never the codes); managers and teachers of the student")
	public List<LoginCodeInfo> loginCodes(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return this.codes.studentCodes(CurrentUser.require(), schoolId, studentId);
	}

	@PostMapping("/{studentId}/login-codes/revoke")
	@Operation(operationId = "revokeStudentLoginCodes", summary = "Revoke every live login code of the student")
	public RevokedCodes revokeLoginCodes(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		return new RevokedCodes(this.codes.revokeStudentCodes(CurrentUser.require(), schoolId, studentId));
	}

	public record RevokedCodes(@NotNull int revoked) {
	}

	public record CreateStudentRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String givenName,
			@Size(max = 100, message = "validation.tooLong") String middleName,
			@Size(max = 100, message = "validation.tooLong") String familyName, LocalDate dateOfBirth,
			@Size(max = 32, message = "validation.tooLong") String gender,
			@Size(max = 64, message = "validation.tooLong") String studentNumber, UUID campusId,
			@Size(max = 31, message = "validation.tooLong") @Schema(description = "Lower case, starts with a letter, 3-31 of a-z 0-9 . _ - ; unique across the platform") String username,
			@Schema(description = "Avatar builder configuration, a JSON object owned by the clients") Map<String, Object> avatarConfig,
			UUID classId, LocalDate admissionOn) {
	}

	public record UpdateStudentRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String givenName,
			@Size(max = 100, message = "validation.tooLong") String middleName,
			@Size(max = 100, message = "validation.tooLong") String familyName, LocalDate dateOfBirth,
			@Size(max = 32, message = "validation.tooLong") String gender,
			@Size(max = 64, message = "validation.tooLong") String studentNumber, UUID campusId,
			@Size(max = 31, message = "validation.tooLong") String username, Map<String, Object> avatarConfig,
			LocalDate admissionOn) {
	}

	public record WithdrawRequest(@Size(max = 200, message = "validation.tooLong") String reason) {
	}

	public record SetPasswordRequest(
			@NotBlank(message = "validation.required") @Size(max = 128, message = "validation.tooLong") String password) {

		@Override
		public String toString() {
			return "SetPasswordRequest[redacted]";
		}

	}

}
