package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.notifications.domain.PushDevice;
import com.nemaz.school.notifications.infrastructure.DeliveryStore;
import com.nemaz.school.notifications.infrastructure.PushDeviceStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Push device registration. A token is unique: registering it again refreshes it, and a different user takes it over. */
@Service
public class PushDeviceService {

	private static final Pattern EXPO = Pattern.compile("^Expo(nent)?PushToken\\[[A-Za-z0-9_\\-]{8,200}\\]$");

	private static final Pattern FCM = Pattern.compile("^[A-Za-z0-9_\\-:.]{20,4096}$");

	private static final Pattern APNS = Pattern.compile("^[0-9a-fA-F]{64,200}$");

	private static final Pattern WEBPUSH = Pattern.compile("^https://[^\\s]{10,2000}$");

	private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,3}$");

	private final PushDeviceStore devices;

	private final DeliveryStore deliveries;

	private final Clock clock;

	PushDeviceService(PushDeviceStore devices, DeliveryStore deliveries, Clock clock) {
		this.devices = devices;
		this.deliveries = deliveries;
		this.clock = clock;
	}

	/**
	 * Registers the device for the signed-in user and binds it to their session family, so it
	 * stops receiving pushes when that session ends. A token that belonged to another user (the phone
	 * changed hands) moves to this user, and pushes still pending for the previous owner are
	 * blocked.
	 */
	@Transactional
	public PushDevice register(CurrentUser user, Registration r) {
		validate(r);
		this.devices.ownerOf(r.token()).ifPresent((owner) -> {
			if (!owner.equals(user.userId())) {
				this.devices.findIdByToken(r.token()).ifPresent((id) -> this.deliveries.blockPendingOfDevice(id, user.userId()));
			}
		});
		return this.devices.upsert(user.userId(), user.sessionId(), r.platform(), r.provider(), r.token(),
				trim(r.appVersion(), 32), trim(r.deviceModel(), 64), trim(r.locale(), 16), this.clock.instant());
	}

	@Transactional
	public void revoke(UUID userId, UUID deviceId) {
		this.devices.findActive(userId, deviceId)
			.orElseThrow(() -> ApiException.notFound("notification.device_not_found"));
		this.devices.revoke(deviceId, this.clock.instant());
	}

	private static void validate(Registration r) {
		boolean valid = switch (r.provider()) {
			case "EXPO" -> EXPO.matcher(r.token()).matches();
			case "FCM" -> FCM.matcher(r.token()).matches();
			case "APNS" -> APNS.matcher(r.token()).matches();
			case "WEBPUSH" -> WEBPUSH.matcher(r.token()).matches();
			default -> false;
		};
		if (!valid) {
			throw ApiException.badRequest("notification.push_token_invalid");
		}
		boolean platformFits = switch (r.provider()) {
			case "EXPO" -> !"WEB".equals(r.platform());
			case "APNS" -> "IOS".equals(r.platform());
			case "WEBPUSH" -> "WEB".equals(r.platform());
			default -> true;
		};
		if (!platformFits) {
			throw ApiException.badRequest("notification.push_platform_mismatch");
		}
		if (r.locale() != null && !r.locale().isBlank() && !LOCALE.matcher(r.locale()).matches()) {
			throw ApiException.badRequest("notification.push_locale_invalid");
		}
	}

	private static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		return (trimmed.length() > max) ? trimmed.substring(0, max) : trimmed;
	}

	public record Registration(String platform, String provider, String token, String appVersion, String deviceModel,
			String locale) {

		@Override
		public String toString() {
			return "Registration[" + this.platform + "/" + this.provider + ", token redacted]";
		}

	}

}
