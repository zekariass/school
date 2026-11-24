package com.nemaz.school.notifications;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class PushDeviceApiTest extends NotificationTestSupport {

	private static String expoToken() {
		return "ExponentPushToken[" + Ids.newId().toString().replace("-", "") + "]";
	}

	private ResultActions register(String bearerToken, Map<String, Object> body) throws Exception {
		return this.mvc.perform(post("/api/v1/me/push-devices").header("Authorization", "Bearer " + bearerToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(body)));
	}

	private Map<String, Object> expo(String token) {
		return Map.of("platform", "ANDROID", "provider", "EXPO", "token", token, "appVersion", "1.0.0", "deviceModel",
				"Pixel 9", "locale", "am-ET");
	}

	@Test
	void registersAndRefreshesByToken() throws Exception {
		Persona user = this.personas.newUser("device-register");
		String token = expoToken();

		JsonNode first = this.json.readTree(register(user.accessToken(), expo(token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist())
			.andReturn().getResponse().getContentAsString());
		JsonNode second = this.json.readTree(register(user.accessToken(), expo(token)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());

		assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
		assertThat(this.jdbc.queryForObject("select count(*) from push_devices where push_token = ?", Integer.class, token))
			.isEqualTo(1);
		Map<String, Object> row = this.jdbc.queryForMap("select * from push_devices where push_token = ?", token);
		assertThat(row.get("user_id")).isEqualTo(user.userId());
		assertThat(row.get("device_model")).isEqualTo("Pixel 9");
		assertThat(row.get("session_family_id")).isNotNull();
	}

	@Test
	void theDeviceIsBoundToTheSessionFamilyOfTheAccessToken() throws Exception {
		UUID userId = this.personas.createUser("device-family", "ACTIVE");
		TokenPair session = session(userId);
		String token = expoToken();
		register(session.accessToken(), expo(token)).andExpect(status().isOk());
		UUID family = this.jdbc.queryForObject("select token_family_id from auth_sessions where user_id = ?", UUID.class, userId);
		assertThat(this.jdbc.queryForObject("select session_family_id from push_devices where push_token = ?", UUID.class, token))
			.isEqualTo(family);

		// the family survives refresh rotation: the device stays active
		var next = read(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())).andExpect(status().isOk()));
		String refreshed = next.get("accessToken").asString();
		register(refreshed, expo(token)).andExpect(status().isOk());
		assertThat(this.jdbc.queryForObject("select revoked_at is null from push_devices where push_token = ?", Boolean.class, token))
			.isTrue();
	}

	@Test
	void aTokenMovesToTheNextUserOfThePhone() throws Exception {
		Persona first = this.personas.newUser("device-first");
		Persona second = this.personas.newUser("device-second");
		String token = expoToken();
		String id = register(first.accessToken(), expo(token)).andReturn().getResponse().getContentAsString();
		UUID deviceId = UUID.fromString(this.json.readTree(id).get("id").asString());
		// a push still waiting for the previous owner
		UUID pending = this.notifications.notify(NotificationRequest.to("NEW_POST", first.userId()).args("5A", "T").build()).getFirst();
		await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(token).size() == 1);
		this.jdbc.update("update notification_deliveries set status = 'FAILED', attempt_count = 1, last_attempt_at = now() where notification_id = ?", pending);

		register(second.accessToken(), expo(token)).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(deviceId.toString()));

		assertThat(this.jdbc.queryForObject("select user_id from push_devices where push_token = ?", UUID.class, token))
			.isEqualTo(second.userId());
		assertThat(deliveries(pending).getFirst().get("status")).isEqualTo("SUPPRESSED");
		assertThat(deliveries(pending).getFirst().get("suppressed_reason")).isEqualTo("BLOCKED");
		// the first user can no longer remove (or see) it
		this.mvc.perform(delete("/api/v1/me/push-devices/" + deviceId).with(first.bearer())).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/me/push-devices/" + deviceId).with(second.bearer())).andExpect(status().isNoContent());
		assertThat(this.jdbc.queryForObject("select revoked_at is not null from push_devices where id = ?", Boolean.class, deviceId))
			.isTrue();
		// registering again brings it back
		register(second.accessToken(), expo(token)).andExpect(status().isOk());
		assertThat(this.jdbc.queryForObject("select revoked_at is null from push_devices where id = ?", Boolean.class, deviceId))
			.isTrue();
	}

	@Test
	void deletingSomeoneElsesOrAnUnknownDeviceIsNotFound() throws Exception {
		Persona owner = this.personas.newUser("device-owner");
		Persona other = this.personas.newUser("device-other");
		String id = register(owner.accessToken(), expo(expoToken())).andReturn().getResponse().getContentAsString();
		UUID deviceId = UUID.fromString(this.json.readTree(id).get("id").asString());

		this.mvc.perform(delete("/api/v1/me/push-devices/" + deviceId).with(other.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("notification.device_not_found"));
		this.mvc.perform(delete("/api/v1/me/push-devices/" + UUID.randomUUID()).with(owner.bearer()))
			.andExpect(status().isNotFound());
		assertThat(this.jdbc.queryForObject("select revoked_at is null from push_devices where id = ?", Boolean.class, deviceId))
			.isTrue();
	}

	@Test
	void tokensAreValidatedPerProvider() throws Exception {
		Persona user = this.personas.newUser("device-validate");
		register(user.accessToken(), Map.of("platform", "ANDROID", "provider", "EXPO", "token", "not-an-expo-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.push_token_invalid"));
		register(user.accessToken(), Map.of("platform", "IOS", "provider", "APNS", "token", "zz".repeat(40)))
			.andExpect(status().isBadRequest());
		register(user.accessToken(), Map.of("platform", "IOS", "provider", "APNS", "token", "ab".repeat(32)))
			.andExpect(status().isOk());
		register(user.accessToken(), Map.of("platform", "WEB", "provider", "EXPO", "token", expoToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("notification.push_platform_mismatch"));
		register(user.accessToken(), Map.of("platform", "WEB", "provider", "WEBPUSH", "token", "http://insecure.example/endpoint-123"))
			.andExpect(status().isBadRequest());
		register(user.accessToken(), Map.of("platform", "WEB", "provider", "WEBPUSH", "token", "https://push.example/send/abcdef123456"))
			.andExpect(status().isOk());
		register(user.accessToken(), Map.of("platform", "ANDROID", "provider", "FCM", "token", "short"))
			.andExpect(status().isBadRequest());
		register(user.accessToken(), Map.of("platform", "ANDROID", "provider", "EXPO", "token", expoToken(), "locale", "not a locale!"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void signingOutRevokesTheDevicesOfThatSessionOnly() throws Exception {
		UUID userId = this.personas.createUser("device-logout", "ACTIVE");
		TokenPair phone = session(userId);
		TokenPair tablet = session(userId);
		String phoneToken = expoToken();
		String tabletToken = expoToken();
		register(phone.accessToken(), expo(phoneToken)).andExpect(status().isOk());
		register(tablet.accessToken(), expo(tabletToken)).andExpect(status().isOk());

		postJson("/api/v1/auth/logout", Map.of("refreshToken", phone.refreshToken())).andExpect(status().isNoContent());

		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.queryForObject("select revoked_at is not null from push_devices where push_token = ?",
					Boolean.class, phoneToken));
		assertThat(this.jdbc.queryForObject("select revoked_at is null from push_devices where push_token = ?",
				Boolean.class, tabletToken)).isTrue();

		// no pushes to the signed-out phone
		notifyOne("NEW_MESSAGE", userId, "Sara");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.pushes.sentTo(tabletToken).size() == 1);
		assertThat(this.pushes.sentTo(phoneToken)).isEmpty();
	}

	@Test
	void signingOutEverywhereRevokesEveryDevice() throws Exception {
		UUID userId = this.personas.createUser("device-logout-all", "ACTIVE");
		TokenPair phone = session(userId);
		TokenPair tablet = session(userId);
		String phoneToken = expoToken();
		String tabletToken = expoToken();
		register(phone.accessToken(), expo(phoneToken)).andExpect(status().isOk());
		register(tablet.accessToken(), expo(tabletToken)).andExpect(status().isOk());

		this.mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + phone.accessToken()))
			.andExpect(status().isNoContent());

		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.queryForObject("select count(*) from push_devices where user_id = ? and revoked_at is null",
					Integer.class, userId) == 0);
	}

	@Test
	void reuseOfARefreshTokenRevokesItsDevices() throws Exception {
		UUID userId = this.personas.createUser("device-reuse", "ACTIVE");
		TokenPair session = session(userId);
		String token = expoToken();
		register(session.accessToken(), expo(token)).andExpect(status().isOk());

		postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())).andExpect(status().isOk());
		postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())).andExpect(status().isUnauthorized());

		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.queryForObject("select revoked_at is not null from push_devices where push_token = ?",
					Boolean.class, token));
	}

}
