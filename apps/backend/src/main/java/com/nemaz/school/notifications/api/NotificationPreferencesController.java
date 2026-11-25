package com.nemaz.school.notifications.api;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.notifications.application.PreferenceService;
import com.nemaz.school.notifications.application.PreferenceService.Change;
import com.nemaz.school.notifications.application.PreferenceService.Preferences;
import com.nemaz.school.notifications.application.PreferenceService.SettingsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the signed-in user wants to be notified about, and when not to be disturbed. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "notifications")
public class NotificationPreferencesController {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final PreferenceService preferences;

	NotificationPreferencesController(PreferenceService preferences) {
		this.preferences = preferences;
	}

	@GetMapping("/notification-preferences")
	@Operation(operationId = "getNotificationPreferences",
			summary = "Every notification type by category with its channels: enabled (stored choice, else the type's default) and locked (the user cannot change it)")
	public Preferences get() {
		return this.preferences.preferences(CurrentUser.require().userId(), LocaleContextHolder.getLocale());
	}

	@PutMapping("/notification-preferences")
	@Operation(operationId = "updateNotificationPreferences",
			summary = "Switch PUSH on or off per type; locked types and IN_APP cannot be switched off (400 notification.preference_locked)")
	public Preferences update(@Valid @RequestBody UpdatePreferencesRequest r) {
		List<Change> changes = r.changes().stream().map((c) -> new Change(c.typeCode(), c.channel(), c.enabled())).toList();
		return this.preferences.change(CurrentUser.require().userId(), changes, LocaleContextHolder.getLocale());
	}

	@GetMapping("/notification-settings")
	@Operation(operationId = "getNotificationSettings", summary = "Quiet hours (in my timezone) and digest mode")
	public SettingsResponse getSettings() {
		return SettingsResponse.from(this.preferences.settings(CurrentUser.require().userId()));
	}

	@PutMapping("/notification-settings")
	@Operation(operationId = "updateNotificationSettings",
			summary = "Set quiet hours (both times or neither); only digest mode OFF exists so far")
	public SettingsResponse updateSettings(@Valid @RequestBody UpdateSettingsRequest r) {
		return SettingsResponse.from(this.preferences.saveSettings(CurrentUser.require().userId(), parse(r.quietHoursStart()),
				parse(r.quietHoursEnd()), r.digestMode()));
	}

	private static LocalTime parse(String value) {
		return (value == null || value.isBlank()) ? null : LocalTime.parse(value, HH_MM);
	}

	public record UpdatePreferencesRequest(
			@NotNull(message = "validation.required") @Size(min = 1, max = 200, message = "validation.tooLong") List<@Valid PreferenceChange> changes) {
	}

	public record PreferenceChange(@NotBlank(message = "validation.required") @Size(max = 64, message = "validation.tooLong") String typeCode,
			@NotBlank(message = "validation.required") @Size(max = 16, message = "validation.tooLong") String channel,
			@NotNull(message = "validation.required") Boolean enabled) {
	}

	public record UpdateSettingsRequest(
			@Schema(description = "HH:mm in my timezone, or absent for no quiet hours") @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "validation.invalid") String quietHoursStart,
			@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "validation.invalid") String quietHoursEnd,
			@Schema(allowableValues = { "OFF" }) @Size(max = 16, message = "validation.tooLong") String digestMode) {
	}

	public record SettingsResponse(String quietHoursStart, String quietHoursEnd, @NotNull String digestMode,
			@NotNull @Schema(description = "The timezone quiet hours are evaluated in") String timezone) {

		static SettingsResponse from(SettingsView v) {
			return new SettingsResponse((v.quietHoursStart() != null) ? v.quietHoursStart().format(HH_MM) : null,
					(v.quietHoursEnd() != null) ? v.quietHoursEnd().format(HH_MM) : null, v.digestMode(), v.timezone());
		}

	}

}
