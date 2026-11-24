package com.nemaz.school.authorization.api;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.authorization.application.RoleAssignmentService;
import com.nemaz.school.authorization.domain.AssignmentStatus;
import com.nemaz.school.authorization.domain.RoleAssignment;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative role assignments. Needs {@code platform.roles.manage}; a role can only be
 * granted or revoked at a scope the caller covers, and only if the caller holds every
 * permission of the role there (no escalation).
 */
@RestController
@RequestMapping("/api/v1/platform/role-assignments")
@Tag(name = "platform-roles")
public class RoleAssignmentController {

	private final RoleAssignmentService service;

	RoleAssignmentController(RoleAssignmentService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("@authz.anywhere('" + Permissions.PLATFORM_ROLES_MANAGE + "')")
	@Operation(operationId = "listRoleAssignments",
			summary = "Role assignments of a user, or at exactly one scope; only those the caller may manage")
	public CursorPage<RoleAssignmentResponse> list(@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) ScopeLevel scopeType, @RequestParam(required = false) String countryCode,
			@RequestParam(required = false) UUID organizationId, @RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) UUID campusId, @RequestParam(required = false) AssignmentStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		AccessScope scope = (scopeType != null)
				? new RoleScope(scopeType, countryCode, organizationId, schoolId, campusId).toAccessScope() : null;
		return this.service.list(CurrentUser.require(), userId, scope, status, cursor, limit)
			.map(RoleAssignmentResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@PreAuthorize("@authz.anywhere('" + Permissions.PLATFORM_ROLES_MANAGE + "')")
	@Operation(operationId = "createRoleAssignment",
			summary = "Grant a role to a user at a scope (at or below the caller's own scope, never more than the caller holds)")
	public RoleAssignmentResponse create(@Valid @RequestBody CreateRoleAssignmentRequest r) {
		return RoleAssignmentResponse
			.from(this.service.grant(CurrentUser.require(), r.userId(), r.roleCode(), r.scope().toAccessScope()));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("@authz.anywhere('" + Permissions.PLATFORM_ROLES_MANAGE + "')")
	@Operation(operationId = "revokeRoleAssignment", summary = "Revoke a role assignment (it stays listed as REVOKED)")
	public RoleAssignmentResponse revoke(@PathVariable UUID id) {
		return RoleAssignmentResponse.from(this.service.revoke(CurrentUser.require(), id));
	}

	/** Where a role applies: set exactly the key of {@code type} (a campus may also name its school). */
	@Schema(description = "A role scope. PLATFORM sets no key, COUNTRY sets countryCode, ORGANIZATION sets "
			+ "organizationId, SCHOOL sets schoolId, CAMPUS sets campusId (and optionally its schoolId).")
	public record RoleScope(@NotNull(message = "validation.required") ScopeLevel type,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "validation.invalid") String countryCode,
			UUID organizationId, UUID schoolId, UUID campusId) {

		AccessScope toAccessScope() {
			try {
				if (this.type == ScopeLevel.CAMPUS) {
					return AccessScope.campus(this.schoolId, this.campusId);
				}
				return new AccessScope(this.type, this.countryCode, this.organizationId, this.schoolId, this.campusId);
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("scope.invalid");
			}
		}

		static RoleScope of(AccessScope scope) {
			return new RoleScope(scope.level(), scope.countryCode(), scope.organizationId(), scope.schoolId(),
					scope.campusId());
		}

	}

	public record CreateRoleAssignmentRequest(@NotNull(message = "validation.required") UUID userId,
			@NotBlank(message = "validation.required") @Size(max = 64, message = "validation.tooLong") String roleCode,
			@NotNull(message = "validation.required") @Valid RoleScope scope) {
	}

	public record RoleAssignmentResponse(@NotNull UUID id, @NotNull UUID userId, @NotNull String roleCode,
			@NotNull RoleScope scope, @NotNull AssignmentStatus status, UUID grantedBy, @NotNull Instant grantedAt,
			Instant revokedAt) {

		static RoleAssignmentResponse from(RoleAssignment a) {
			return new RoleAssignmentResponse(a.getId(), a.getUserId(), a.getRoleCode(), RoleScope.of(a.scope()),
					a.getStatus(), a.getGrantedBy(), a.getGrantedAt(), a.getRevokedAt());
		}

	}

}
