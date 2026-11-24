package com.nemaz.school.identity.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import com.nemaz.school.authorization.RoleAssignments.AssignmentView;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.identity.application.MeService;
import com.nemaz.school.identity.application.MeService.Me;
import com.nemaz.school.identity.application.MeService.ProfileChange;
import com.nemaz.school.identity.domain.AccountType;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.domain.UserStatus;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account. {@code GET} returns the profile, the ACTIVE role
 * assignments with localized names, and the permissions flattened per scope: web and mobile
 * build navigation from it (the server still checks every request).
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "me")
public class MeController {

	private final MeService me;

	MeController(MeService me) {
		this.me = me;
	}

	@GetMapping
	@Operation(operationId = "getMe", summary = "The signed-in user, their roles and permissions per scope")
	public MeResponse get() {
		return MeResponse.from(this.me.get(CurrentUser.require(), LocaleContextHolder.getLocale()));
	}

	@PatchMapping
	@Operation(operationId = "updateMe",
			summary = "Change display name, preferred language, timezone or calendar; absent fields stay unchanged")
	public MeResponse update(@Valid @RequestBody UpdateMeRequest r) {
		return MeResponse.from(this.me.update(CurrentUser.require(),
				new ProfileChange(r.displayName(), r.preferredLanguageCode(), r.timezone(), r.calendarPreference()),
				LocaleContextHolder.getLocale()));
	}

	@PutMapping("/avatar")
	@Operation(operationId = "setMyAvatar",
			summary = "Use a READY AVATAR upload of mine as my avatar (the previous file is deleted)")
	public MeResponse setAvatar(@Valid @RequestBody SetAvatarRequest r) {
		return MeResponse.from(this.me.setAvatar(CurrentUser.require(), r.mediaId(), LocaleContextHolder.getLocale()));
	}

	@DeleteMapping("/avatar")
	@Operation(operationId = "clearMyAvatar", summary = "Remove my avatar and delete its file")
	public MeResponse clearAvatar() {
		return MeResponse.from(this.me.clearAvatar(CurrentUser.require(), LocaleContextHolder.getLocale()));
	}

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "changeMyPassword",
			summary = "Set or change the password; other sessions are signed out. A first password needs a recent sign-in")
	public void changePassword(@Valid @RequestBody ChangePasswordRequest r) {
		this.me.changePassword(CurrentUser.require(), r.currentPassword(), r.newPassword());
	}

	public record UpdateMeRequest(
			@Size(min = 1, max = 100, message = "validation.tooLong") String displayName,
			@Pattern(regexp = "^[a-z]{2,3}$", message = "validation.invalid") String preferredLanguageCode,
			@Size(max = 64, message = "validation.tooLong") String timezone,
			@Pattern(regexp = "^[A-Z0-9_]{1,32}$", message = "validation.invalid") String calendarPreference) {
	}

	public record SetAvatarRequest(@NotNull(message = "validation.required") UUID mediaId) {
	}

	public record ChangePasswordRequest(@Size(max = 128, message = "validation.tooLong") String currentPassword,
			@NotBlank(message = "validation.required") @Size(max = 128, message = "validation.tooLong") String newPassword) {

		@Override
		public String toString() {
			return "ChangePasswordRequest[redacted]";
		}

	}

	@Schema(description = "A level of the tenancy hierarchy; exactly the key of its type is set (a campus also has its school).")
	public record UserScope(@NotNull ScopeLevel type, String countryCode, UUID organizationId, UUID schoolId,
			UUID campusId) {

		static UserScope of(AccessScope s) {
			return new UserScope(s.level(), s.countryCode(), s.organizationId(), s.schoolId(), s.campusId());
		}

	}

	public record RoleAssignmentSummary(@NotNull UUID id, @NotNull String roleCode, @NotNull String roleName,
			@NotNull UserScope scope, @Schema(description = "Display name of the scope; absent for PLATFORM") String scopeName,
			@Schema(description = "Slug of the school (SCHOOL and CAMPUS scopes), for /s/{slug} URLs") String schoolSlug,
			@Schema(description = "Name of the school (SCHOOL and CAMPUS scopes)") String schoolName,
			@NotNull List<String> permissions, @NotNull Instant grantedAt) {

		static RoleAssignmentSummary from(AssignmentView v) {
			return new RoleAssignmentSummary(v.id(), v.roleCode(), v.roleName(), UserScope.of(v.scope()), v.scopeName(),
					v.schoolSlug(), v.schoolName(), v.permissions(), v.grantedAt());
		}

	}

	public record ScopePermissions(@NotNull UserScope scope, String scopeName, String schoolSlug, String schoolName,
			@NotNull List<String> roleCodes, @NotNull List<String> permissions) {
	}

	public record MeResponse(@NotNull UUID id, @NotNull AccountType accountType, @NotNull String displayName,
			@NotNull String givenName, String middleName, String familyName, String email, @NotNull boolean emailVerified,
			String phone, @NotNull boolean phoneVerified, String username, String preferredLanguageCode, String timezone,
			String calendarPreference, @NotNull UserStatus status, @NotNull boolean hasPassword, Instant lastLoginAt,
			@NotNull List<RoleAssignmentSummary> roleAssignments,
			@NotNull @Schema(description = "Permissions merged per scope, from all roles held there") List<ScopePermissions> scopes,
			@Schema(description = "READY avatar; ask /media/{id}/url for a signed link") MediaSummary avatar) {

		static MeResponse from(Me me) {
			User u = me.user();
			Map<AccessScope, ScopeAccumulator> byScope = new LinkedHashMap<>();
			for (AssignmentView v : me.assignments()) {
				ScopeAccumulator acc = byScope.computeIfAbsent(v.scope(), (s) -> new ScopeAccumulator(v));
				acc.roles.add(v.roleCode());
				acc.permissions.addAll(v.permissions());
			}
			List<ScopePermissions> scopes = new ArrayList<>();
			byScope.forEach((scope, acc) -> scopes.add(new ScopePermissions(UserScope.of(scope), acc.name,
					acc.schoolSlug, acc.schoolName, List.copyOf(acc.roles), List.copyOf(acc.permissions))));
			return new MeResponse(u.getId(), u.getAccountType(), u.getDisplayName(), u.getGivenName(), u.getMiddleName(),
					u.getFamilyName(), u.getEmail(), u.getEmailVerifiedAt() != null, u.getPhoneE164(),
					u.getPhoneVerifiedAt() != null, u.getUsername(), u.getPreferredLanguageCode(), u.getTimezone(),
					u.getCalendarPreference(), u.getStatus(), me.hasPassword(), u.getLastLoginAt(),
					me.assignments().stream().map(RoleAssignmentSummary::from).toList(), scopes, me.avatar());
		}

	}

	private static final class ScopeAccumulator {

		final String name;

		final String schoolSlug;

		final String schoolName;

		final TreeSet<String> roles = new TreeSet<>();

		final TreeSet<String> permissions = new TreeSet<>();

		ScopeAccumulator(AssignmentView first) {
			this.name = first.scopeName();
			this.schoolSlug = first.schoolSlug();
			this.schoolName = first.schoolName();
		}

	}

}
