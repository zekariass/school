package com.nemaz.school.notifications;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.RecordingPushSender;
import org.junit.jupiter.api.AfterEach;

import org.springframework.beans.factory.annotation.Autowired;

import static org.awaitility.Awaitility.await;

/** Shared helpers of the notification tests: users with a language and timezone, devices, delivery rows. */
abstract class NotificationTestSupport extends AuthTestSupport {

	@Autowired
	protected NotificationService notifications;

	@Autowired
	protected RecordingPushSender pushes;

	@AfterEach
	void resetPushes() {
		this.pushes.reset();
	}

	/** An ACTIVE user who prefers {@code language} and lives in {@code timezone} (either may be null). */
	protected UUID user(String language, String timezone) {
		UUID id = this.personas.createUser("notif-" + language, "ACTIVE");
		this.jdbc.update("update users set preferred_language_code = ?, timezone = ? where id = ?", language, timezone,
				id);
		return id;
	}

	/** Registers an active Expo device directly in the database; returns its token. */
	protected String device(UUID userId) {
		String token = "ExponentPushToken[" + Ids.newId().toString().replace("-", "") + "]";
		this.jdbc.update("""
				insert into push_devices (user_id, platform, provider, push_token, last_seen_at)
				values (?, 'ANDROID', 'EXPO', ?, now())""", userId, token);
		return token;
	}

	protected UUID deviceId(String token) {
		return this.jdbc.queryForObject("select id from push_devices where push_token = ?", UUID.class, token);
	}

	protected List<Map<String, Object>> deliveries(UUID notificationId) {
		return this.jdbc.queryForList("select * from notification_deliveries where notification_id = ? order by queued_at",
				notificationId);
	}

	/** Waits until the fan-out of the notification has written its delivery rows. */
	protected List<Map<String, Object>> awaitDeliveries(UUID notificationId) {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> !deliveries(notificationId).isEmpty());
		return deliveries(notificationId);
	}

	protected UUID notifyOne(String type, UUID user, Object... args) {
		return this.notifications.notify(NotificationRequest.to(type, user).args(args).build()).getFirst();
	}

}
