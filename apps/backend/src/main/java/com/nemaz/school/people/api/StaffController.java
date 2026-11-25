package com.nemaz.school.people.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.people.application.StaffService;
import com.nemaz.school.people.application.StaffService.AddStaff;
import com.nemaz.school.people.application.StaffService.AdminRole;
import com.nemaz.school.people.application.StaffService.StaffStatus;
import com.nemaz.school.people.application.StaffService.StaffType;
import com.nemaz.school.people.application.StaffService.StaffView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** A school's staff. See {@link StaffService} for who may do what. */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/staff")
@Tag(name = "staff")
public class StaffController {

	private final StaffService staff;

	StaffController(StaffService staff) {
		this.staff = staff;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "addStaff",
			summary = "Add a person to the school's staff by phone or email: finds their account or creates one the administrator vouches for; 409 staff.already_member when they are staff already")
	public StaffView add(@PathVariable UUID schoolId, @Valid @RequestBody AddStaffRequest r) {
		return this.staff.add(CurrentUser.require(), schoolId, new AddStaff(r.phone(), r.email(), r.country(),
				r.givenName(), r.familyName(), r.displayName(), r.staffType(), r.jobTitle(), r.employeeNumber(),
				r.campusId(), r.preferredLanguage(), r.adminRole()));
	}

	@GetMapping
	@Operation(operationId = "listStaff",
			summary = "Staff by name; filters staffType, status, campusId, q. Managers see every status and masked contacts, other staff only ACTIVE colleagues")
	public CursorPage<StaffView> list(@PathVariable UUID schoolId, @RequestParam(required = false) StaffType staffType,
			@RequestParam(required = false) StaffStatus status, @RequestParam(required = false) UUID campusId,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.staff.list(CurrentUser.require(), schoolId, staffType, status, campusId, q, cursor, limit);
	}

	@GetMapping("/{staffId}")
	@Operation(operationId = "getStaff", summary = "One staff member")
	public StaffView get(@PathVariable UUID schoolId, @PathVariable UUID staffId) {
		return this.staff.get(CurrentUser.require(), schoolId, staffId);
	}

	@PatchMapping("/{staffId}")
	@Operation(operationId = "updateStaff", summary = "Change job title, campus or employee number (absent fields stay)")
	public StaffView update(@PathVariable UUID schoolId, @PathVariable UUID staffId,
			@Valid @RequestBody UpdateStaffRequest r) {
		return this.staff.update(CurrentUser.require(), schoolId, staffId, r.jobTitle(), r.campusId(),
				r.employeeNumber());
	}

	@PostMapping("/{staffId}/deactivate")
	@Operation(operationId = "deactivateStaff",
			summary = "Mark the staff member FORMER (ended today) and end their class teaching; administrative roles are not touched")
	public StaffView deactivate(@PathVariable UUID schoolId, @PathVariable UUID staffId) {
		return this.staff.deactivate(CurrentUser.require(), schoolId, staffId);
	}

	@Schema(description = "Set exactly one of phone or email (national phone formats are read in `country`, default ET). adminRole is only for ADMINISTRATOR.")
	public record AddStaffRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "validation.invalid") String country,
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String givenName,
			@Size(max = 100, message = "validation.tooLong") String familyName,
			@Size(max = 100, message = "validation.tooLong") String displayName,
			@NotNull(message = "validation.required") StaffType staffType,
			@Size(max = 100, message = "validation.tooLong") String jobTitle,
			@Size(max = 64, message = "validation.tooLong") String employeeNumber, UUID campusId,
			@Pattern(regexp = "^[a-z]{2,3}$", message = "validation.invalid") String preferredLanguage,
			AdminRole adminRole) {
	}

	public record UpdateStaffRequest(@Size(max = 100, message = "validation.tooLong") String jobTitle, UUID campusId,
			@Size(max = 64, message = "validation.tooLong") String employeeNumber) {
	}

}
