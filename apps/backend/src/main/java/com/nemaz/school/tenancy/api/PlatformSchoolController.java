package com.nemaz.school.tenancy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentActor;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.SubscriptionStatus;
import com.nemaz.school.subscriptions.SubscriptionView;
import com.nemaz.school.tenancy.application.CampusService;
import com.nemaz.school.tenancy.application.SchoolService;
import com.nemaz.school.tenancy.application.SchoolService.CreateSchool;
import com.nemaz.school.tenancy.application.SchoolService.SchoolDetails;
import com.nemaz.school.tenancy.application.SchoolSubscriptionService;
import com.nemaz.school.tenancy.domain.Campus;
import com.nemaz.school.tenancy.domain.CampusStatus;
import com.nemaz.school.tenancy.domain.OwnershipType;
import com.nemaz.school.tenancy.domain.School;
import com.nemaz.school.tenancy.domain.SchoolLanguage;
import com.nemaz.school.tenancy.domain.SchoolStatus;
import com.nemaz.school.tenancy.domain.SchoolType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin management of schools, their campuses and their (manual) subscription.
 * Platform-wide authority only for now; country- and organization-scoped administrators
 * arrive with role assignments in M1b.
 */
@RestController
@RequestMapping("/api/v1/platform/schools")
@Tag(name = "platform-schools")
public class PlatformSchoolController {

	private final SchoolService schools;

	private final CampusService campuses;

	private final SchoolSubscriptionService subscriptions;

	PlatformSchoolController(SchoolService schools, CampusService campuses, SchoolSubscriptionService subscriptions) {
		this.schools = schools;
		this.campuses = campuses;
		this.subscriptions = subscriptions;
	}

	@GetMapping
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "listSchools", summary = "List schools, newest first, filtered by country, organization, status")
	public CursorPage<SchoolResponse> list(@RequestParam(required = false) String countryCode,
			@RequestParam(required = false) UUID organizationId, @RequestParam(required = false) SchoolStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.schools.list(countryCode, organizationId, status, cursor, limit).map(SchoolResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "createSchool",
			summary = "Create a school (ONBOARDING) in an enabled country; unset fields inherit the country's defaults")
	public SchoolResponse create(@Valid @RequestBody CreateSchoolRequest r) {
		return SchoolResponse.from(this.schools.create(new CreateSchool(r.countryCode(), r.organizationId(), r.name(),
				r.shortName(), r.slug(), r.schoolType(), r.ownershipType(), r.defaultLanguageCode(), r.timezone(),
				r.defaultCalendarCode(), r.currencyCode(), r.phoneE164(), r.email(), r.website(), r.brandColor(),
				r.languageCodes())));
	}

	@GetMapping("/{schoolId}")
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "getSchool", summary = "Get a school")
	public SchoolResponse get(@PathVariable UUID schoolId) {
		return SchoolResponse.from(this.schools.get(schoolId));
	}

	@PutMapping("/{schoolId}/status")
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "updateSchoolStatus", summary = "Change a school's status")
	public SchoolResponse updateStatus(@PathVariable UUID schoolId, @Valid @RequestBody UpdateSchoolStatusRequest r) {
		return SchoolResponse.from(this.schools.changeStatus(schoolId, r.status()));
	}

	@GetMapping("/{schoolId}/campuses")
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "listCampuses", summary = "List a school's campuses")
	public CursorPage<CampusResponse> listCampuses(@PathVariable UUID schoolId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.campuses.list(schoolId, cursor, limit).map(CampusResponse::from);
	}

	@PostMapping("/{schoolId}/campuses")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "createCampus", summary = "Add a campus to a school")
	public CampusResponse createCampus(@PathVariable UUID schoolId, @Valid @RequestBody CreateCampusRequest r) {
		return CampusResponse.from(this.campuses.create(schoolId, r.name(), r.code(), r.timezone()));
	}

	@PutMapping("/{schoolId}/campuses/{campusId}")
	@PreAuthorize(Permissions.CAN_MANAGE_SCHOOLS)
	@Operation(operationId = "updateCampus", summary = "Update a campus")
	public CampusResponse updateCampus(@PathVariable UUID schoolId, @PathVariable UUID campusId,
			@Valid @RequestBody UpdateCampusRequest r) {
		return CampusResponse.from(this.campuses.update(schoolId, campusId, r.name(), r.code(), r.timezone(), r.status()));
	}

	@PutMapping("/{schoolId}/subscription")
	@PreAuthorize(Permissions.CAN_MANAGE_SUBSCRIPTIONS)
	@Operation(operationId = "assignSchoolSubscription",
			summary = "Manually assign a plan to a school, ending its current subscription")
	public SubscriptionResponse assignSubscription(@PathVariable UUID schoolId,
			@Valid @RequestBody AssignSubscriptionRequest r) {
		SubscriptionView view = this.subscriptions.assign(schoolId,
				new AssignSubscription(r.planCode(), r.status(), r.currentPeriodEnd()),
				CurrentActor.id().orElse(null));
		return SubscriptionResponse.from(view);
	}

	// --- requests ---------------------------------------------------------------------

	public record CreateSchoolRequest(
			@NotBlank(message = Validation.REQUIRED) @Pattern(regexp = Validation.COUNTRY_PATTERN,
					message = Validation.INVALID) String countryCode,
			UUID organizationId,
			@NotBlank(message = Validation.REQUIRED) @Size(max = 200, message = Validation.TOO_LONG) String name,
			@Size(max = 60, message = Validation.TOO_LONG) String shortName,
			@NotBlank(message = Validation.REQUIRED) @Size(max = 63, message = Validation.SLUG) @Pattern(
					regexp = Validation.SLUG_PATTERN, message = Validation.SLUG) String slug,
			SchoolType schoolType, OwnershipType ownershipType,
			@Pattern(regexp = Validation.LANGUAGE_PATTERN, message = Validation.INVALID) String defaultLanguageCode,
			@Size(max = 64, message = Validation.TOO_LONG) String timezone,
			@Pattern(regexp = Validation.CODE_PATTERN, message = Validation.INVALID) String defaultCalendarCode,
			@Pattern(regexp = Validation.CURRENCY_PATTERN, message = Validation.INVALID) String currencyCode,
			@Pattern(regexp = Validation.PHONE_PATTERN, message = Validation.PHONE) String phoneE164,
			@Email(message = Validation.EMAIL) @Size(max = 254, message = Validation.TOO_LONG) String email,
			@Pattern(regexp = Validation.URL_PATTERN, message = Validation.INVALID) @Size(max = 255,
					message = Validation.TOO_LONG) String website,
			@Pattern(regexp = Validation.COLOR_PATTERN, message = Validation.COLOR) String brandColor,
			@Size(max = 10, message = Validation.TOO_LONG) List<@Pattern(regexp = Validation.LANGUAGE_PATTERN,
					message = Validation.INVALID) String> languageCodes) {
	}

	public record UpdateSchoolStatusRequest(@NotNull(message = Validation.REQUIRED) SchoolStatus status) {
	}

	public record CreateCampusRequest(
			@NotBlank(message = Validation.REQUIRED) @Size(max = 200, message = Validation.TOO_LONG) String name,
			@Size(max = 32, message = Validation.TOO_LONG) String code,
			@Size(max = 64, message = Validation.TOO_LONG) String timezone) {
	}

	public record UpdateCampusRequest(
			@NotBlank(message = Validation.REQUIRED) @Size(max = 200, message = Validation.TOO_LONG) String name,
			@Size(max = 32, message = Validation.TOO_LONG) String code,
			@Size(max = 64, message = Validation.TOO_LONG) String timezone,
			@NotNull(message = Validation.REQUIRED) CampusStatus status) {
	}

	public record AssignSubscriptionRequest(@NotBlank(message = Validation.REQUIRED) String planCode,
			SubscriptionStatus status, Instant currentPeriodEnd) {
	}

	// --- responses --------------------------------------------------------------------

	public record SchoolLanguageResponse(@NotNull String code, @NotNull boolean isDefault) {

		static SchoolLanguageResponse from(SchoolLanguage l) {
			return new SchoolLanguageResponse(l.getLanguageCode(), l.isDefault());
		}

	}

	public record SchoolResponse(@NotNull UUID id, @NotNull String countryCode, UUID organizationId,
			@NotNull String name, String shortName, @NotNull String slug, SchoolType schoolType,
			OwnershipType ownershipType, @NotNull String defaultLanguageCode, @NotNull String timezone,
			@NotNull String defaultCalendarCode, @NotNull String currencyCode, String phoneE164, String email,
			String website, String brandColor, @NotNull SchoolStatus status,
			@NotNull List<SchoolLanguageResponse> languages, @NotNull int version, @NotNull Instant createdAt,
			@NotNull Instant updatedAt) {

		static SchoolResponse from(SchoolDetails d) {
			School s = d.school();
			return new SchoolResponse(s.getId(), s.getCountryCode(), s.getOrganizationId(), s.getName(),
					s.getShortName(), s.getSlug(), s.getSchoolType(), s.getOwnershipType(), s.getDefaultLanguageCode(),
					s.getTimezone(), s.getDefaultCalendarCode(), s.getCurrencyCode(), s.getPhoneE164(), s.getEmail(),
					s.getWebsite(), s.getBrandColor(), s.getStatus(),
					d.languages().stream().map(SchoolLanguageResponse::from).toList(), s.getVersion(), s.getCreatedAt(),
					s.getUpdatedAt());
		}

	}

	public record CampusResponse(@NotNull UUID id, @NotNull UUID schoolId, @NotNull String name, String code,
			String timezone, @NotNull CampusStatus status, @NotNull int version, @NotNull Instant createdAt,
			@NotNull Instant updatedAt) {

		static CampusResponse from(Campus c) {
			return new CampusResponse(c.getId(), c.getSchoolId(), c.getName(), c.getCode(), c.getTimezone(),
					c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
		}

	}

	public record SubscriptionResponse(@NotNull UUID id, @NotNull UUID schoolId, @NotNull String planCode,
			@NotNull SubscriptionStatus status, Instant currentPeriodStart, Instant currentPeriodEnd,
			Instant trialEndsAt, @NotNull Instant createdAt) {

		static SubscriptionResponse from(SubscriptionView v) {
			return new SubscriptionResponse(v.id(), v.schoolId(), v.planCode(), v.status(), v.currentPeriodStart(),
					v.currentPeriodEnd(), v.trialEndsAt(), v.createdAt());
		}

	}

}
