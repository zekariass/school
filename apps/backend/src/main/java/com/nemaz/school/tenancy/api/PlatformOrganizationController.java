package com.nemaz.school.tenancy.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.media.MediaSummary;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Management of organizations (groups of schools) with {@code platform.organizations.manage}:
 * platform-wide, or within the countries where the caller holds it (country administrators).
 */
@RestController
@RequestMapping("/api/v1/platform/organizations")
@Tag(name = "platform-organizations")
public class PlatformOrganizationController {

	private static final String MANAGE = Permissions.PLATFORM_ORGANIZATIONS_MANAGE;

	private final OrganizationService organizations;

	private final AccessControl access;

	PlatformOrganizationController(OrganizationService organizations, AccessControl access) {
		this.organizations = organizations;
		this.access = access;
	}

	@GetMapping
	@PreAuthorize("@authz.anywhere('" + MANAGE + "')")
	@Operation(operationId = "listOrganizations",
			summary = "List organizations the caller may manage, newest first")
	public CursorPage<OrganizationResponse> list(@RequestParam(required = false) String countryCode,
			@RequestParam(required = false) OrganizationStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		CursorPage<Organization> page = this.organizations.list(countryCode, status, cursor, limit,
				this.access.accessible(CurrentUser.require(), MANAGE));
		Map<UUID, MediaSummary> logos = this.organizations.logos(page.items());
		return page.map((o) -> OrganizationResponse.from(o, logos.get(o.getId())));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@PreAuthorize("@authz.country('" + MANAGE + "', #request.countryCode())")
	@Operation(operationId = "createOrganization", summary = "Create an organization in an enabled country")
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
		return OrganizationResponse.from(this.organizations.create(
				new CreateOrganization(request.countryCode(), request.name(), request.legalName(), request.slug())), null);
	}

	@GetMapping("/{id}")
	@PreAuthorize("@authz.organization('" + MANAGE + "', #id)")
	@Operation(operationId = "getOrganization", summary = "Get an organization")
	public OrganizationResponse get(@PathVariable UUID id) {
		return withLogo(this.organizations.get(id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("@authz.organization('" + MANAGE + "', #id)")
	@Operation(operationId = "updateOrganization", summary = "Update name, legal name and status (the slug is fixed)")
	public OrganizationResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
		return withLogo(this.organizations.update(id, request.name(), request.legalName(), request.status()));
	}

	@PutMapping("/{id}/logo")
	@PreAuthorize("@authz.organization('" + MANAGE + "', #id)")
	@Operation(operationId = "setOrganizationLogo",
			summary = "Use a READY BRANDING upload made for this organization as its logo (the previous file is deleted)")
	public OrganizationResponse setLogo(@PathVariable UUID id, @Valid @RequestBody SetLogoRequest request) {
		return withLogo(this.organizations.setLogo(id, request.mediaId(), CurrentUser.require()));
	}

	@DeleteMapping("/{id}/logo")
	@PreAuthorize("@authz.organization('" + MANAGE + "', #id)")
	@Operation(operationId = "clearOrganizationLogo", summary = "Remove the organization's logo and delete its file")
	public OrganizationResponse clearLogo(@PathVariable UUID id) {
		return withLogo(this.organizations.clearLogo(id));
	}

	private OrganizationResponse withLogo(Organization organization) {
		return OrganizationResponse.from(organization, this.organizations.logos(java.util.List.of(organization)).get(organization.getId()));
	}

	public record SetLogoRequest(@NotNull(message = Validation.REQUIRED) UUID mediaId) {
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
			String legalName, @NotNull String slug, @NotNull OrganizationStatus status, MediaSummary logo,
			@NotNull int version, @NotNull Instant createdAt, @NotNull Instant updatedAt) {

		static OrganizationResponse from(Organization o, MediaSummary logo) {
			return new OrganizationResponse(o.getId(), o.getCountryCode(), o.getName(), o.getLegalName(), o.getSlug(),
					o.getStatus(), logo, o.getVersion(), o.getCreatedAt(), o.getUpdatedAt());
		}

	}

}
