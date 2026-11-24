package com.nemaz.school.tenancy.api;

import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.tenancy.application.SchoolService;
import com.nemaz.school.tenancy.domain.School;
import com.nemaz.school.tenancy.domain.SchoolStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a school from its URL slug for the web workspace ({@code /s/{slug}}). A caller
 * without {@code school.read} on the school gets the same 404 as for an unknown slug, so the
 * endpoint cannot be used to find out which slugs exist.
 */
@RestController
@RequestMapping("/api/v1/schools")
@Tag(name = "schools")
public class SchoolLookupController {

	private final SchoolService schools;

	private final AccessControl access;

	SchoolLookupController(SchoolService schools, AccessControl access) {
		this.schools = schools;
		this.access = access;
	}

	@GetMapping("/by-slug/{slug}")
	@Operation(operationId = "getSchoolBySlug",
			summary = "A school's id, name and status from its slug; 404 when unknown or when the caller cannot read it")
	public SchoolSummary bySlug(@PathVariable String slug) {
		CurrentUser user = CurrentUser.require();
		School school = this.schools.findBySlug(slug).orElseThrow(() -> ApiException.notFound("school.not_found"));
		if (!this.access.can(user, Permissions.SCHOOL_READ, AccessScope.school(school.getId()))) {
			throw ApiException.notFound("school.not_found");
		}
		return new SchoolSummary(school.getId(), school.getName(), school.getShortName(), school.getSlug(),
				school.getCountryCode(), school.getStatus());
	}

	public record SchoolSummary(@NotNull UUID id, @NotNull String name, String shortName, @NotNull String slug,
			@NotNull String countryCode, @NotNull SchoolStatus status) {
	}

}
