package com.nemaz.school.tenancy.api;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.tenancy.application.OrganizationService;
import com.nemaz.school.tenancy.application.OrganizationService.CreateOrganization;
import com.nemaz.school.tenancy.domain.Organization;
import com.nemaz.school.tenancy.domain.OrganizationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/** Platform-admin management of organizations (groups of schools). */
@RestController
@RequestMapping("/api/v1/platform/organizations")
@PreAuthorize(Permissions.CAN_MANAGE_ORGANIZATIONS)
@Tag(name = "platform-organizations")
public class PlatformOrganizationController {

	private final OrganizationService organizations;

	PlatformOrganizationController(OrganizationService organizations) {
		this.organizations = organizations;
	}

	@GetMapping
	@Operation(operationId = "listOrganizations", summary = "List organizations, newest first")
	public CursorPage<OrganizationResponse> list(@RequestParam(required = false) String countryCode,
			@RequestParam(required = false) OrganizationStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.organizations.list(countryCode, status, cursor, limit).map(OrganizationResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "createOrganization", summary = "Create an organization in an enabled country")
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
		return OrganizationResponse.from(this.organizations.create(
				new CreateOrganization(request.countryCode(), request.name(), request.legalName(), request.slug())));
	}

	@GetMapping("/{id}")
	@Operation(operationId = "getOrganization", summary = "Get an organization")
	public OrganizationResponse get(@PathVariable UUID id) {
		return OrganizationResponse.from(this.organizations.get(id));
	}

	@PutMapping("/{id}")
	@Operation(operationId = "updateOrganization", summary = "Update name, legal name and status (the slug is fixed)")
	public OrganizationResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
		return OrganizationResponse.from(
				this.organizations.update(id, request.name(), request.legalName(), request.status()));
	}

	public record CreateOrganizationRequest(
			@NotBlank(message = Validation.REQUIRED) @Pattern(regexp = Validation.COUNTRY_PATTERN,
					message = Validation.INVALID) String countryCode,
			@NotBlank(message = Validation.REQUIRED) @Size(max = 200, message = Validation.TOO_LONG) String name,
			@Size(max = 200, message = Validation.TOO_LONG) String legalName,
			@NotBlank(message = Validation.REQUIRED) @Size(max = 63, message = Validation.SLUG) @Pattern(
					regexp = Validation.SLUG_PATTERN, message = Validation.SLUG) String slug) {
	}

	public record UpdateOrganizationRequest(
			@NotBlank(message = Validation.REQUIRED) @Size(max = 200, message = Validation.TOO_LONG) String name,
			@Size(max = 200, message = Validation.TOO_LONG) String legalName,
			@NotNull(message = Validation.REQUIRED) OrganizationStatus status) {
	}

	public record OrganizationResponse(@NotNull UUID id, @NotNull String countryCode, @NotNull String name,
			String legalName, @NotNull String slug, @NotNull OrganizationStatus status, @NotNull int version,
			@NotNull Instant createdAt, @NotNull Instant updatedAt) {

		static OrganizationResponse from(Organization o) {
			return new OrganizationResponse(o.getId(), o.getCountryCode(), o.getName(), o.getLegalName(), o.getSlug(),
					o.getStatus(), o.getVersion(), o.getCreatedAt(), o.getUpdatedAt());
		}

	}

}
