package com.nemaz.school.notifications.api;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.notifications.application.PushDeviceService;
import com.nemaz.school.notifications.application.PushDeviceService.Registration;
import com.nemaz.school.notifications.domain.PushDevice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/push-devices")
@Tag(name = "notifications")
public class PushDeviceController {

	private final PushDeviceService devices;

	PushDeviceController(PushDeviceService devices) {
		this.devices = devices;
	}

	@PostMapping
	@Operation(operationId = "registerPushDevice",
			summary = "Register (or refresh) this device's push token for my current session; a token already registered to someone else moves to me")
	public PushDeviceResponse register(@Valid @RequestBody RegisterPushDeviceRequest r) {
		PushDevice device = this.devices.register(CurrentUser.require(),
				new Registration(r.platform().name(), r.provider().name(), r.token(), r.appVersion(), r.deviceModel(),
						r.locale()));
		return PushDeviceResponse.from(device);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePushDevice", summary = "Stop pushes to one of my devices (idempotent)")
	public void delete(@PathVariable UUID id) {
		this.devices.revoke(CurrentUser.require().userId(), id);
	}

	public enum Platform {

		IOS, ANDROID, WEB

	}

	public enum Provider {

		EXPO, FCM, APNS, WEBPUSH

	}

	public record RegisterPushDeviceRequest(@NotNull(message = "validation.required") Platform platform,
			@NotNull(message = "validation.required") Provider provider,
			@NotBlank(message = "validation.required") @Size(max = 4096, message = "validation.tooLong") String token,
			@Size(max = 32, message = "validation.tooLong") String appVersion,
			@Size(max = 64, message = "validation.tooLong") String deviceModel,
			@Size(max = 16, message = "validation.tooLong") String locale) {

		@Override
		public String toString() {
			return "RegisterPushDeviceRequest[token redacted]";
		}

	}

	public record PushDeviceResponse(@NotNull UUID id, @NotNull Platform platform, @NotNull Provider provider,
			String appVersion, String deviceModel, String locale,
			@NotNull Instant createdAt, Instant lastSeenAt) {

		static PushDeviceResponse from(PushDevice d) {
			return new PushDeviceResponse(d.id(), Platform.valueOf(d.platform()), Provider.valueOf(d.provider()),
					d.appVersion(), d.deviceModel(), d.locale(), d.createdAt(), d.lastSeenAt());
		}

	}

}
