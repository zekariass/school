package com.nemaz.school.identity.api;

import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.identity.application.UserProvisioningService;
import com.nemaz.school.identity.application.UserProvisioningService.Provision;
import com.nemaz.school.identity.application.UserProvisioningService.UserMatch;
import com.nemaz.school.identity.domain.User;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal user provisioning for administrators. Users are global identities; what a user may
 * do is decided by role assignments, which are scope-checked on their own. Holding
 * {@code platform.roles.manage} at any scope is therefore enough to create or find a user.
 */
@RestController
@RequestMapping("/api/v1/platform/users")
@Tag(name = "platform-users")
public class UserProvisioningController {

	private static final String CAN_MANAGE_ROLES = "@authz.anywhere('"
			+ Permissions.PLATFORM_ROLES_MANAGE + "')";

	private final UserProvisioningService service;

	UserProvisioningController(UserProvisioningService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@PreAuthorize(CAN_MANAGE_ROLES)
	@Operation(operationId = "createUser",
			summary = "Create an ACTIVE user whose phone or email is marked verified (the administrator vouches for it)")
	public CreatedUserResponse create(@Valid @RequestBody CreateUserRequest r) {
		return CreatedUserResponse.from(this.service.create(new Provision(r.phone(), r.email(), r.country(),
				r.givenName(), r.familyName(), r.displayName(), r.preferredLanguage())));
	}

	@GetMapping("/lookup")
	@PreAuthorize(CAN_MANAGE_ROLES)
	@Operation(operationId = "lookupUser",
			summary = "Find a user by an exact phone or email (no listing or search); 404 when there is none")
	public UserMatchResponse lookup(@RequestParam(required = false) String phone,
			@RequestParam(required = false) String email, @RequestParam(required = false) String country) {
		return UserMatchResponse.from(this.service.lookup(CurrentUser.require(), phone, email, country));
	}

	@GetMapping("/{id}")
	@PreAuthorize(CAN_MANAGE_ROLES)
	@Operation(operationId = "getUser", summary = "A user's display name and masked destination, by id")
	public UserMatchResponse get(@PathVariable UUID id) {
		return UserMatchResponse.from(this.service.get(id));
	}

	public record CreateUserRequest(@Size(max = 32, message = "validation.tooLong") String phone,
			@Size(max = 254, message = "validation.tooLong") String email,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "validation.invalid") String country,
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String givenName,
			@Size(max = 100, message = "validation.tooLong") String familyName,
			@Size(max = 100, message = "validation.tooLong") String displayName,
			@Pattern(regexp = "^[a-z]{2,3}$", message = "validation.invalid") String preferredLanguage) {
	}

	public record CreatedUserResponse(@NotNull UUID id, @NotNull String displayName, String maskedPhone,
			String maskedEmail) {

		static CreatedUserResponse from(User u) {
			return new CreatedUserResponse(u.getId(), u.getDisplayName(), Masking.phone(u.getPhoneE164()),
					Masking.email(u.getEmail()));
		}

	}

	public record UserMatchResponse(@NotNull UUID id, @NotNull String displayName, @NotNull String maskedDestination,
			@NotNull String status) {

		static UserMatchResponse from(UserMatch m) {
			return new UserMatchResponse(m.id(), m.displayName(), m.maskedDestination(), m.status());
		}

	}

}
