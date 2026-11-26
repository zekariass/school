package com.nemaz.school.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.notifications.application.DeliveryRetryJob;
import com.nemaz.school.notifications.domain.PushSender.PushResult;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rendering, preferences, quiet hours, urgent bypass, devices and retries of the notification pipeline. */
@IntegrationTest
class NotificationDeliveryTest extends NotificationTestSupport {

	/** 23:00 in Africa/Addis_Ababa (UTC+3). */
	private static final Instant LATE_EVENING = Instant.parse("2026-06-10T20:00:00Z");

	@Autowired
	DeliveryRetryJob retry;

	private void setQuietHours(UUID userId, String start, String end) {
		this.jdbc.update("""
				insert into user_notification_settings (user_id, quiet_hours_start, quiet_hours_end) values (?, ?::time, ?::time)
				on conflict (user_id) do update set quiet_hours_start = excluded.quiet_hours_start,
				  quiet_hours_end = excluded.quiet_hours_end""", userId, start, end);
	}

	private static int attempts(Map<String, Object> row) {
		return ((Number) row.get("attempt_count")).intValue();
	}

	private Map<String, Object> inbox(UUID notificationId) {
		return this.jdbc.queryForMap("select * from notifications where id = ?", notificationId);
	}

	@Test
	void textsAreRenderedInEachRecipientsLanguage() {
		UUID amharic = user("am", null);
		UUID english = user("en", null);
		UUID unset = user(null, null);

		var ids = this.notifications.notify(NotificationRequest.to("NEW_POST", List.of(amharic, english, unset))
			.args("Abebe", "Sara")
			.path("/messages/123")
			.build());

		assertThat(ids).hasSize(3);
		Map<String, Object> am = this.jdbc.queryForMap("select title, body, data::text as data from notifications where user_id = ?", amharic);
		assertThat(am.get("title")).isEqualTo("በAbebe አዲስ ልጥፍ");
		assertThat(am.get("data").toString()).contains("/messages/123");
		assertThat(this.jdbc.queryForObject("select title from notifications where user_id = ?", String.class, english))
			.isEqualTo("New post in Abebe");
		assertThat(this.jdbc.queryForObject("select title from notifications where user_id = ?", String.class, unset))
			.isEqualTo("New post in Abebe");
	}

	@Test
	void localizedArgumentsFollowTheRecipientsLanguage() {
		UUID amharic = user("am", null);
		UUID english = user("en", null);
		var role = LocalizedText.of(Map.of("en", "School administrator", "am", "የትምህርት ቤት አስተዳዳሪ"));
		var school = LocalizedText.of(Map.of("en", "Hope School"));

		this.notifications.notify(NotificationRequest.to("ROLE_GRANTED", List.of(amharic, english)).args(role, school).build());

		assertThat(this.jdbc.queryForObject("select body from notifications where user_id = ?", String.class, english))
			.isEqualTo("You are now School administrator at Hope School.");
		assertThat(this.jdbc.queryForObject("select body from notifications where user_id = ?", String.class, amharic))
			.isEqualTo("አሁን በHope School የትምህርት ቤት አስተዳዳሪ ነዎት።");
	}

	@Test
	void unknownTypesAndUnsafePathsAreProgrammingErrors() {
		UUID user = user("en", null);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> this.notifications.notify(NotificationRequest.to("NO_SUCH_TYPE", user).build()));
		for (String path : List.of("https://evil.example/x", "//evil.example", "javascript:alert(1)", "relative/path")) {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
					() -> NotificationRequest.to("TEST_NOTIFICATION", user).path(path).build(), path);
		}
	}

	@Test
	void pushesToEveryActiveDeviceAndRecordsIt() {
		UUID user = user("en", null);
		String first = device(user);
		String second = device(user);
		UUID id = this.notifications.notify(NotificationRequest.to("NEW_POST", user).args("Sara", "Teacher").path("/messages/1").build()).getFirst();

		List<Map<String, Object>> rows = awaitDeliveries(id);
		await().atMost(Duration.ofSeconds(10)).until(() -> deliveries(id).stream().allMatch((r) -> "SENT".equals(r.get("status"))));
		assertThat(deliveries(id)).hasSize(2).allSatisfy((r) -> {
			assertThat(r.get("channel")).isEqualTo("PUSH");
			assertThat(attempts(r)).isEqualTo(1);
			assertThat(r.get("sent_at")).isNotNull();
		});
		assertThat(this.pushes.sentTo(first)).singleElement().satisfies((m) -> {
			assertThat(m.title()).isEqualTo("New post in Sara");
			assertThat(m.data()).containsEntry("path", "/messages/1").containsEntry("notificationId", id.toString());
			assertThat(m.urgent()).isFalse();
		});
		assertThat(this.pushes.sentTo(second)).hasSize(1);
		assertThat(rows).isNotEmpty();
	}

	@Test
	void aPushNamesTheSchoolOfTheNotificationAndANotificationWithoutOneDoesNot() {
		UUID user = user("en", null);
		String device = device(user);
		UUID school = this.personas.newSchool("Push school id");
		UUID withSchool = this.notifications.notify(NotificationRequest.to("NEW_POST", user).args("Sara", "Teacher")
			.path("/messages/2").school(school).build()).getFirst();
		UUID without = notifyOne("NEW_POST", user, "Sara");
		await().atMost(Duration.ofSeconds(10)).until(() -> deliveries(withSchool).stream().allMatch((r) -> "SENT".equals(r.get("status")))
				&& deliveries(without).stream().allMatch((r) -> "SENT".equals(r.get("status"))));
		assertThat(this.pushes.sentTo(device)).anySatisfy((m) -> {
			assertThat(m.data()).containsEntry("notificationId", withSchool.toString()).containsEntry("schoolId", school.toString());
		}).anySatisfy((m) -> {
			assertThat(m.data()).containsEntry("notificationId", without.toString()).doesNotContainKey("schoolId");
		});
	}

	@Test
	void aUserWithoutADeviceGetsTheInboxRowAndANoDeviceRecord() {
		UUID user = user("en", null);
		UUID id = notifyOne("NEW_POST", user, "Sara");

		List<Map<String, Object>> rows = awaitDeliveries(id);
		assertThat(rows).singleElement().satisfies((r) -> {
			assertThat(r.get("status")).isEqualTo("SUPPRESSED");
			assertThat(r.get("suppressed_reason")).isEqualTo("NO_DEVICE");
			assertThat(r.get("push_device_id")).isNull();
		});
		assertThat(inbox(id).get("read_at")).isNull();
		assertThat(this.pushes.sent()).isEmpty();
	}

	@Test
	void aTypeThatIsNotPushedByDefaultNeedsOptIn() {
		UUID user = user("en", null);
		String token = device(user);
		// NEW_STORY is IN_APP only by default: no delivery rows at all, nothing pushed
		UUID quiet = notifyOne("NEW_STORY", user, "5A", "Teacher");
		await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> deliveries(quiet).isEmpty());
		assertThat(this.pushes.sentTo(token)).isEmpty();

		// after opting in, it is pushed
		this.jdbc.update("insert into notification_preferences (user_id, type_code, channel, enabled) values (?, 'NEW_STORY', 'PUSH', true)", user);
		UUID id = notifyOne("NEW_STORY", user, "5A", "Teacher");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
		assertThat(deliveries(id)).singleElement().satisfies((r) -> assertThat(r.get("status")).isEqualTo("SENT"));
	}

	@Test
	void aUserWhoSwitchedPushOffForATypeIsNotPushed() throws Exception {
		var user = this.personas.newUser("opt-out");
		String token = device(user.userId());

		this.mvc.perform(put("/api/v1/me/notification-preferences").with(user.bearer())
			.contentType("application/json")
			.content("{\"changes\":[{\"typeCode\":\"NEW_POST\",\"channel\":\"PUSH\",\"enabled\":false}]}"))
			.andExpect(status().isOk());

		UUID id = notifyOne("NEW_POST", user.userId(), "5A", "Teacher");
		List<Map<String, Object>> rows = awaitDeliveries(id);
		assertThat(rows).singleElement().satisfies((r) -> {
			assertThat(r.get("status")).isEqualTo("SUPPRESSED");
			assertThat(r.get("suppressed_reason")).isEqualTo("PREFERENCE");
		});
		assertThat(this.pushes.sentTo(token)).isEmpty();
		// the inbox still has it: only the push was declined
		assertThat(inbox(id).get("title")).isEqualTo("New post in 5A");
	}

	@Test
	void aLockedTypeIsPushedEvenIfAnOptOutRowExists() {
		UUID user = user("en", null);
		String token = device(user);
		this.jdbc.update("insert into notification_preferences (user_id, type_code, channel, enabled) values (?, 'CONSENT_REQUESTED', 'PUSH', false)", user);

		notifyOne("CONSENT_REQUESTED", user, "School office", "photos");

		await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
	}

	@Test
	void quietHoursHoldBackPushAcrossMidnightInTheUsersTimezone() {
		UUID user = user("en", "Africa/Addis_Ababa");
		String token = device(user);
		setQuietHours(user, "22:00", "06:00");
		try {
			this.clock.moveTo(LATE_EVENING); // 23:00 in Addis Ababa, 20:00 UTC
			UUID late = notifyOne("NEW_POST", user, "Sara");
			assertThat(awaitDeliveries(late)).singleElement().satisfies((r) -> {
				assertThat(r.get("status")).isEqualTo("SUPPRESSED");
				assertThat(r.get("suppressed_reason")).isEqualTo("QUIET_HOURS");
				assertThat(r.get("push_device_id")).isEqualTo(deviceId(token));
			});

			this.clock.moveTo(Instant.parse("2026-06-11T02:30:00Z")); // 05:30 local: still quiet after midnight
			UUID early = notifyOne("NEW_POST", user, "Sara");
			assertThat(awaitDeliveries(early)).singleElement()
				.satisfies((r) -> assertThat(r.get("suppressed_reason")).isEqualTo("QUIET_HOURS"));

			this.clock.moveTo(Instant.parse("2026-06-11T03:30:00Z")); // 06:30 local: quiet hours are over
			UUID morning = notifyOne("NEW_POST", user, "Sara");
			await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
			assertThat(deliveries(morning)).singleElement().satisfies((r) -> assertThat(r.get("status")).isEqualTo("SENT"));
			assertThat(this.pushes.sentTo(token)).hasSize(1);
		}
		finally {
			this.clock.reset();
		}
	}

	@Test
	void quietHoursUseTheUsersTimezoneNotTheServers() {
		// 20:00 UTC is 23:00 in Addis Ababa but 20:00 in UTC: a UTC user is not in quiet hours
		UUID user = user("en", "UTC");
		String token = device(user);
		setQuietHours(user, "22:00", "06:00");
		try {
			this.clock.moveTo(LATE_EVENING);
			notifyOne("NEW_POST", user, "Sara");
			await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
		}
		finally {
			this.clock.reset();
		}
	}

	@Test
	void urgentBypassesQuietHoursOnlyForTypesThatMayBeUrgent() {
		UUID user = user("en", "Africa/Addis_Ababa");
		String token = device(user);
		setQuietHours(user, "22:00", "06:00");
		try {
			this.clock.moveTo(LATE_EVENING);
			// ANNOUNCEMENT_PUBLISHED can be urgent
			UUID urgent = this.notifications.notify(NotificationRequest.to("ANNOUNCEMENT_PUBLISHED", user)
				.args("School closed tomorrow", "Principal")
				.urgent()
				.build()).getFirst();
			await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
			assertThat(this.pushes.sentTo(token).getFirst().urgent()).isTrue();
			assertThat(inbox(urgent).get("priority")).isEqualTo("URGENT");

			// NEW_POST cannot: the request is downgraded and held back
			UUID post = this.notifications.notify(NotificationRequest.to("NEW_POST", user).args("5A", "Teacher").urgent().build())
				.getFirst();
			assertThat(inbox(post).get("priority")).isEqualTo("NORMAL");
			assertThat(awaitDeliveries(post)).singleElement()
				.satisfies((r) -> assertThat(r.get("suppressed_reason")).isEqualTo("QUIET_HOURS"));
			assertThat(this.pushes.sentTo(token)).hasSize(1);
		}
		finally {
			this.clock.reset();
		}
	}

	@Test
	void deviceNotRegisteredRevokesTheDevice() {
		UUID user = user("en", null);
		String dead = device(user);
		String alive = device(user);
		this.pushes.respondWith((m) -> m.token().equals(dead) ? PushResult.deviceNotRegistered()
				: PushResult.sent("ok"));

		UUID id = notifyOne("NEW_POST", user, "Sara");

		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.queryForObject("select revoked_at is not null from push_devices where push_token = ?",
					Boolean.class, dead));
		assertThat(this.jdbc.queryForObject("select revoked_at is null from push_devices where push_token = ?",
				Boolean.class, alive)).isTrue();
		Map<String, Object> failed = this.jdbc.queryForMap(
				"select status, last_error from notification_deliveries where notification_id = ? and push_device_id = ?", id,
				deviceId(dead));
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat(failed.get("last_error")).isEqualTo("DeviceNotRegistered");

		// the revoked device is not used again
		UUID next = notifyOne("NEW_POST", user, "Sara");
		await().atMost(Duration.ofSeconds(10)).until(() -> deliveries(next).size() == 1);
		assertThat(this.pushes.sentTo(dead)).hasSize(1);
		assertThat(this.pushes.sentTo(alive)).hasSize(2);
	}

	@Test
	void failedPushesAreRetriedWithBackOffUpToThreeAttempts() {
		UUID user = user("en", null);
		String token = device(user);
		this.pushes.respondWith((m) -> PushResult.failed("expo_http: timeout"));
		UUID id = notifyOne("NEW_POST", user, "Sara");
		await().atMost(Duration.ofSeconds(10))
			.until(() -> deliveries(id).stream().anyMatch((r) -> "FAILED".equals(r.get("status"))));
		try {
			assertThat(attempts(deliveries(id).getFirst())).isEqualTo(1);
			assertThat(deliveries(id).getFirst().get("last_error")).isEqualTo("expo_http: timeout");

			// back-off: nothing is due right away
			this.retry.retryDue();
			assertThat(this.pushes.sentTo(token)).hasSize(1);

			this.clock.advance(Duration.ofSeconds(61));
			this.retry.retryDue();
			assertThat(this.pushes.sentTo(token)).hasSize(2);
			assertThat(attempts(deliveries(id).getFirst())).isEqualTo(2);

			// after the second failure the wait is five minutes
			this.clock.advance(Duration.ofSeconds(120));
			this.retry.retryDue();
			assertThat(this.pushes.sentTo(token)).hasSize(2);
			this.clock.advance(Duration.ofSeconds(181));
			this.retry.retryDue();
			assertThat(this.pushes.sentTo(token)).hasSize(3);
			assertThat(attempts(deliveries(id).getFirst())).isEqualTo(3);

			// three attempts is the limit
			this.clock.advance(Duration.ofHours(1));
			this.retry.retryDue();
			assertThat(this.pushes.sentTo(token)).hasSize(3);
			assertThat(deliveries(id).getFirst().get("status")).isEqualTo("FAILED");
		}
		finally {
			this.clock.reset();
		}
	}

	@Test
	void aRetrySucceedsOnceTheProviderRecovers() {
		UUID user = user("en", null);
		String token = device(user);
		this.pushes.respondWith((m) -> PushResult.failed("expo_http: timeout"));
		UUID id = notifyOne("NEW_POST", user, "Sara");
		await().atMost(Duration.ofSeconds(10))
			.until(() -> deliveries(id).stream().anyMatch((r) -> "FAILED".equals(r.get("status"))));
		this.pushes.respondWith((m) -> PushResult.sent("ticket-1"));
		try {
			this.clock.advance(Duration.ofSeconds(61));
			this.retry.retryDue();
			Map<String, Object> row = deliveries(id).getFirst();
			assertThat(row.get("status")).isEqualTo("SENT");
			assertThat(row.get("provider_message_id")).isEqualTo("ticket-1");
			assertThat(attempts(row)).isEqualTo(2);
			assertThat(row.get("last_error")).isNull();
			assertThat(this.pushes.sentTo(token)).hasSize(2);
		}
		finally {
			this.clock.reset();
		}
	}

	@Test
	void handlingTheSameEventTwiceSendsOnce() {
		UUID user = user("en", null);
		String token = device(user);
		UUID id = notifyOne("NEW_POST", user, "Sara");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);

		// the outbox redelivers: simulate by publishing the same event again
		this.tx.executeWithoutResult((s) -> this.events.publishEvent(new NotificationsCreated(List.of(id))));
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> this.pushes.sentTo(token).size() == 1);
		assertThat(deliveries(id)).hasSize(1);
	}

	@Autowired
	org.springframework.context.ApplicationEventPublisher events;

}
