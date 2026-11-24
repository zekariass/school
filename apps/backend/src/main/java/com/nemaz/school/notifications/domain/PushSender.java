package com.nemaz.school.notifications.domain;

import java.util.List;
import java.util.Map;

/**
 * A push provider. {@code send} returns exactly one result per message, in the same order, and
 * does not throw for per-message problems (those are results); an exception means the whole
 * call failed and every message of it counts as failed (and is retried).
 */
public interface PushSender {

	List<PushResult> send(List<PushMessage> messages);

	/** Whether this sender can deliver to devices of {@code provider} (EXPO, FCM, APNS, WEBPUSH). */
	default boolean supports(String provider) {
		return true;
	}

	/**
	 * @param provider the device's provider (EXPO, FCM, APNS, WEBPUSH)
	 * @param token the device's push token; never log it in full
	 * @param data routing data for the app, such as {@code path}
	 * @param urgent deliver with high priority
	 */
	record PushMessage(String provider, String token, String title, String body, Map<String, Object> data,
			boolean urgent) {
	}

	/** {@code DEVICE_NOT_REGISTERED}: the token is dead (app uninstalled); the device must be revoked. */
	record PushResult(Outcome outcome, String providerMessageId, String error) {

		public enum Outcome {

			SENT, FAILED, DEVICE_NOT_REGISTERED

		}

		public static PushResult sent(String providerMessageId) {
			return new PushResult(Outcome.SENT, providerMessageId, null);
		}

		public static PushResult failed(String error) {
			return new PushResult(Outcome.FAILED, null, error);
		}

		public static PushResult deviceNotRegistered() {
			return new PushResult(Outcome.DEVICE_NOT_REGISTERED, null, "DeviceNotRegistered");
		}

	}

}
