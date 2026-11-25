package com.nemaz.school.realtime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.infrastructure.SessionDenyList;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.realtime.application.RealtimeConnections;
import com.nemaz.school.realtime.application.RealtimeTickets;
import com.nemaz.school.realtime.application.RealtimeWatchdog;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.web.socket.WebSocketHttpHeaders;

import static com.nemaz.school.realtime.StompTestClient.bearer;
import static com.nemaz.school.realtime.StompTestClient.origin;
import static com.nemaz.school.realtime.StompTestClient.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The WebSocket gateway end to end, over a real socket: CONNECT rules, subscriptions, Redis fan-out, revocation. */
@IntegrationTest
class RealtimeGatewayTest extends AuthTestSupport {

	private static final String QUEUE = "/user/queue/notifications";

	private static final String BROWSER = "http://localhost:3100";

	@LocalServerPort
	int port;

	@Autowired
	RealtimeTickets tickets;

	@Autowired
	RealtimePublisher publisher;

	@Autowired
	RealtimeWatchdog watchdog;

	@Autowired
	RealtimeConnections connections;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	SessionDenyList denyList;

	@Autowired
	CacheManager caches;

	@Autowired
	NotificationService notifications;

	private StompTestClient withBearer(String accessToken) {
		return StompTestClient.connect(this.port, origin(null), bearer(accessToken));
	}

	private String newTicket(Persona persona) throws Exception {
		return read(this.mvc.perform(post("/api/v1/realtime/tickets").with(persona.bearer())).andExpect(status().isOk()))
			.get("ticket")
			.asString();
	}

	private void assertRefused(StompTestClient client, String reason) throws Exception {
		assertThat(client.nextError(5)).isEqualTo(reason);
		assertThat(client.awaitClosed(5)).as("connection closed after the ERROR frame").isTrue();
	}

	// --- CONNECT with a bearer token --------------------------------------------------------------

	@Test
	void aBearerTokenConnectsAndReceivesWhatIsPublishedThroughRedis() throws Exception {
		Persona user = this.personas.newUser("ws-bearer");
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.subscribe(QUEUE, user.userId(), this.publisher);
			this.publisher.toUser(user.userId(), RealtimePublisher.NOTIFICATIONS_QUEUE, Map.of("type", "probe", "n", 1));

			JsonNode message = this.json.readTree(client.nextMessage(5));
			assertThat(message.get("type").asString()).isEqualTo("probe");
			assertThat(message.get("n").asInt()).isEqualTo(1);
		}
	}

	@Test
	void theServerAnnouncesTenSecondHeartbeats() throws Exception {
		Persona user = this.personas.newUser("ws-heartbeat");
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.awaitConnected();
			assertThat(client.connectedHeaders.get().getHeartbeat()).containsExactly(10_000L, 10_000L);
		}
	}

	@Test
	void aNotificationReachesTheOpenAppWithItsUnreadCount() throws Exception {
		Persona user = this.personas.newUser("ws-notify");
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.subscribe(QUEUE, user.userId(), this.publisher);
			this.notifications.notify(NotificationRequest.to("NEW_MESSAGE", user.userId()).args("Sara").path("/messages/9").build());
			this.notifications.notify(NotificationRequest.to("NEW_MESSAGE", user.userId()).args("Tom").build());

			JsonNode first = this.json.readTree(client.nextMessage(10));
			JsonNode second = this.json.readTree(client.nextMessage(10));
			assertThat(first.get("type").asString()).isEqualTo("notification.created");
			List<String> titles = List.of(first.get("notification").get("title").asString(),
					second.get("notification").get("title").asString());
			assertThat(titles).containsExactlyInAnyOrder("New message from Sara", "New message from Tom");
			assertThat(Math.max(first.get("unreadCount").asInt(), second.get("unreadCount").asInt())).isEqualTo(2);
			JsonNode sara = first.get("notification").get("title").asString().endsWith("Sara") ? first : second;
			assertThat(sara.get("notification").get("data").get("path").asString()).isEqualTo("/messages/9");
			assertThat(sara.get("notification").get("id").asString()).isNotBlank();
		}
	}

	@Test
	void readingOnOneDeviceUpdatesTheBadgeOnTheOthers() throws Exception {
		Persona user = this.personas.newUser("ws-read-sync");
		UUID id = this.notifications.notify(NotificationRequest.to("NEW_MESSAGE", user.userId()).args("Sara").build()).getFirst();
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.subscribe(QUEUE, user.userId(), this.publisher);
			this.mvc.perform(post("/api/v1/notifications/" + id + "/read").with(user.bearer())).andExpect(status().isNoContent());

			JsonNode event = this.json.readTree(client.nextMessage(10));
			assertThat(event.get("type").asString()).isEqualTo("notification.read");
			assertThat(event.get("ids").get(0).asString()).isEqualTo(id.toString());
			assertThat(event.get("unreadCount").asInt()).isZero();

			this.notifications.notify(NotificationRequest.to("NEW_MESSAGE", user.userId()).args("Tom").build());
			assertThat(this.json.readTree(client.nextMessage(10)).get("type").asString()).isEqualTo("notification.created");
			this.mvc.perform(post("/api/v1/notifications/read-all").with(user.bearer())).andExpect(status().isOk());
			JsonNode all = this.json.readTree(client.nextMessage(10));
			assertThat(all.get("all").asBoolean()).isTrue();
			assertThat(all.get("unreadCount").asInt()).isZero();
		}
	}

	@Test
	void eachUserOnlyHearsTheirOwnQueue() throws Exception {
		Persona alice = this.personas.newUser("ws-alice");
		Persona bob = this.personas.newUser("ws-bob");
		try (StompTestClient a = withBearer(alice.accessToken()); StompTestClient b = withBearer(bob.accessToken())) {
			a.subscribe(QUEUE, alice.userId(), this.publisher);
			b.subscribe(QUEUE, bob.userId(), this.publisher);
			this.publisher.toUser(alice.userId(), RealtimePublisher.NOTIFICATIONS_QUEUE, Map.of("for", "alice"));

			assertThat(this.json.readTree(a.nextMessage(5)).get("for").asString()).isEqualTo("alice");
			assertThat(b.nextMessage(1)).isNull();
		}
	}

	@Test
	void everyConnectionOfAUserGetsTheMessage() throws Exception {
		Persona user = this.personas.newUser("ws-two-devices");
		TokenPair phone = session(user.userId());
		TokenPair web = session(user.userId());
		try (StompTestClient a = withBearer(phone.accessToken()); StompTestClient b = withBearer(web.accessToken())) {
			a.subscribe(QUEUE, user.userId(), this.publisher);
			b.subscribe(QUEUE, user.userId(), this.publisher);
			this.publisher.toUser(user.userId(), RealtimePublisher.NOTIFICATIONS_QUEUE, Map.of("n", 7));
			assertThat(a.nextMessage(5)).contains("7");
			assertThat(b.nextMessage(5)).contains("7");
		}
	}

	// --- CONNECT with a ticket --------------------------------------------------------------------

	@Test
	void aTicketConnectsOnceFromAnAllowedBrowserOrigin() throws Exception {
		Persona user = this.personas.newUser("ws-ticket");
		String ticket = newTicket(user);
		try (StompTestClient client = StompTestClient.connect(this.port, origin(BROWSER), ticket(ticket))) {
			client.subscribe(QUEUE, user.userId(), this.publisher);
			this.publisher.toUser(user.userId(), RealtimePublisher.NOTIFICATIONS_QUEUE, Map.of("via", "ticket"));
			assertThat(client.nextMessage(5)).contains("ticket");
		}
		// single use: the same ticket again is worthless
		try (StompTestClient again = StompTestClient.connect(this.port, origin(BROWSER), ticket(ticket))) {
			assertRefused(again, "auth.invalid_ticket");
		}
	}

	@Test
	void aTicketLivesSixtySecondsAndIsStoredHashed() throws Exception {
		Persona user = this.personas.newUser("ws-ticket-ttl");
		String ticket = newTicket(user);
		String key = RealtimeTickets.key(ticket);

		assertThat(key).doesNotContain(ticket).startsWith("rt:ticket:");
		Long ttl = this.redis.getExpire(key);
		assertThat(ttl).isBetween(55L, 60L);
		assertThat(this.redis.opsForValue().get(key)).startsWith(user.userId().toString());

		// time passes: the entry expires and the ticket no longer connects
		this.redis.expire(key, Duration.ofMillis(100));
		await().atMost(Duration.ofSeconds(5)).until(() -> this.redis.opsForValue().get(key) == null);
		try (StompTestClient client = StompTestClient.connect(this.port, origin(BROWSER), ticket(ticket))) {
			assertRefused(client, "auth.invalid_ticket");
		}
	}

	@Test
	void ticketsAreSingleUseUnderRacingRedemptions() throws Exception {
		Persona user = this.personas.newUser("ws-ticket-race");
		String ticket = newTicket(user);
		RealtimeTickets tickets = this.tickets;
		var results = java.util.stream.IntStream.range(0, 8).parallel().mapToObj((i) -> tickets.redeem(ticket)).toList();
		assertThat(results.stream().filter(java.util.Optional::isPresent)).hasSize(1);
	}

	@Test
	void aTicketNeedsABrowserOrigin() throws Exception {
		Persona user = this.personas.newUser("ws-ticket-origin");
		try (StompTestClient client = StompTestClient.connect(this.port, origin(null), ticket(newTicket(user)))) {
			assertRefused(client, "auth.ticket_requires_origin");
		}
	}

	@Test
	void aForeignOriginIsRefusedAtTheHandshake() throws Exception {
		Persona user = this.personas.newUser("ws-foreign-origin");
		try (StompTestClient client = StompTestClient.connect(this.port, origin("https://evil.example"), bearer(user.accessToken()))) {
			assertThat(client.awaitClosed(5)).isTrue();
			assertThat(client.isOpen()).isFalse();
			assertThat(client.nextError(1)).startsWith("connect failed");
		}
		try (StompTestClient client = StompTestClient.connect(this.port, origin(BROWSER), bearer(user.accessToken()))) {
			client.awaitConnected();
			assertThat(client.isOpen()).isTrue();
		}
	}

	@Test
	void ticketsAreRateLimitedPerUser() throws Exception {
		Persona user = this.personas.newUser("ws-ticket-limit");
		for (int i = 0; i < 30; i++) {
			this.mvc.perform(post("/api/v1/realtime/tickets").with(user.bearer())).andExpect(status().isOk());
		}
		this.mvc.perform(post("/api/v1/realtime/tickets").with(user.bearer()))
			.andExpect(status().isTooManyRequests())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
				.value("realtime.rate_limited"));
	}

	// --- refused CONNECT ----------------------------------------------------------------------------

	@Test
	void connectWithoutCredentialsIsRefusedAndClosed() throws Exception {
		try (StompTestClient client = StompTestClient.connect(this.port, origin(null), new StompHeaders())) {
			assertRefused(client, "auth.credentials_required");
		}
	}

	@Test
	void invalidTokensAreRefused() throws Exception {
		for (String token : List.of("garbage", "a.b.c")) {
			try (StompTestClient client = withBearer(token)) {
				assertRefused(client, "auth.invalid_token");
			}
		}
		StompHeaders notBearer = new StompHeaders();
		notBearer.add("Authorization", "Basic abc");
		try (StompTestClient client = StompTestClient.connect(this.port, origin(null), notBearer)) {
			assertRefused(client, "auth.invalid_token");
		}
	}

	@Test
	void bothCredentialsAtOnceAreRefused() throws Exception {
		Persona user = this.personas.newUser("ws-both");
		StompHeaders both = bearer(user.accessToken());
		both.add("ticket", newTicket(user));
		try (StompTestClient client = StompTestClient.connect(this.port, origin(BROWSER), both)) {
			assertRefused(client, "auth.credentials_required");
		}
	}

	@Test
	void aRevokedSessionOrSuspendedAccountCannotConnect() throws Exception {
		UUID userId = this.personas.createUser("ws-revoked-connect", "ACTIVE");
		TokenPair session = session(userId);
		this.tx.executeWithoutResult((s) -> this.tokenService.logoutAll(userId, null));
		await().atMost(Duration.ofSeconds(5)).until(() -> this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
			.header("Authorization", "Bearer " + session.accessToken())).andReturn().getResponse().getStatus() == 401);
		try (StompTestClient client = withBearer(session.accessToken())) {
			assertRefused(client, "auth.invalid_token");
		}

		Persona suspended = this.personas.get(com.nemaz.school.support.Personas.Name.SUSPENDED_USER);
		try (StompTestClient client = withBearer(suspended.accessToken())) {
			assertRefused(client, "auth.invalid_token");
		}
	}

	// --- what a connected client may do --------------------------------------------------------------

	@Test
	void subscribingAnywhereButTheOwnUserQueueIsRefused() throws Exception {
		Persona user = this.personas.newUser("ws-subscribe");
		Persona other = this.personas.newUser("ws-other");
		for (String destination : List.of("/topic/news", "/queue/notifications", "/user/" + other.userId() + "/queue/notifications",
				"/user/queue/a/b", "/user/queue/../secret", "/app/anything", "/user/queue/")) {
			try (StompTestClient client = withBearer(user.accessToken())) {
				client.awaitConnected().subscribe(destination, new org.springframework.messaging.simp.stomp.StompFrameHandler() {
					@Override
					public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
						return String.class;
					}

					@Override
					public void handleFrame(StompHeaders headers, Object payload) {
					}
				});
				assertRefused(client, "forbidden.destination");
			}
		}
	}

	@Test
	void sendingIsRefused() throws Exception {
		Persona user = this.personas.newUser("ws-send");
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.awaitConnected().send("/app/echo", "hello");
			assertRefused(client, "forbidden.send");
		}
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.awaitConnected().send("/user/" + user.userId() + "/queue/notifications", "spoof");
			assertRefused(client, "forbidden.send");
		}
	}

	@Test
	void anOversizedFrameClosesTheConnection() throws Exception {
		Persona user = this.personas.newUser("ws-big");
		try (StompTestClient client = withBearer(user.accessToken())) {
			client.awaitConnected().send("/app/x", "x".repeat(80 * 1024));
			assertThat(client.awaitClosed(5)).isTrue();
		}
	}

	// --- revocation ------------------------------------------------------------------------------------

	@Test
	void signingOutDisconnectsThatSessionOnly() throws Exception {
		UUID userId = this.personas.createUser("ws-logout", "ACTIVE");
		TokenPair phone = session(userId);
		TokenPair web = session(userId);
		try (StompTestClient a = withBearer(phone.accessToken()); StompTestClient b = withBearer(web.accessToken())) {
			a.subscribe(QUEUE, userId, this.publisher);
			b.subscribe(QUEUE, userId, this.publisher);

			postJson("/api/v1/auth/logout", Map.of("refreshToken", phone.refreshToken())).andExpect(status().isNoContent());

			assertThat(a.awaitClosed(10)).as("signed-out session is disconnected").isTrue();
			assertThat(b.isOpen()).isTrue();
			this.publisher.toUser(userId, RealtimePublisher.NOTIFICATIONS_QUEUE, Map.of("still", "here"));
			assertThat(b.nextMessage(5)).contains("here");
			assertThat(a.nextMessage(1)).isNull();
		}
	}

	@Test
	void signingOutEverywhereDisconnectsEverySession() throws Exception {
		UUID userId = this.personas.createUser("ws-logout-all", "ACTIVE");
		TokenPair phone = session(userId);
		TokenPair web = session(userId);
		try (StompTestClient a = withBearer(phone.accessToken()); StompTestClient b = withBearer(web.accessToken())) {
			a.subscribe(QUEUE, userId, this.publisher);
			b.subscribe(QUEUE, userId, this.publisher);
			this.mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + phone.accessToken()))
				.andExpect(status().isNoContent());
			assertThat(a.awaitClosed(10)).isTrue();
			assertThat(b.awaitClosed(10)).isTrue();
		}
	}

	@Test
	void refreshTokenReuseDisconnectsTheSession() throws Exception {
		UUID userId = this.personas.createUser("ws-reuse", "ACTIVE");
		TokenPair session = session(userId);
		try (StompTestClient client = withBearer(session.accessToken())) {
			client.subscribe(QUEUE, userId, this.publisher);
			postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())).andExpect(status().isOk());
			// the connection survives a normal refresh
			assertThat(client.isOpen()).isTrue();
			postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())).andExpect(status().isUnauthorized());
			assertThat(client.awaitClosed(10)).isTrue();
		}
	}

	@Test
	void theWatchdogClosesWhatTheEventMissed() throws Exception {
		UUID userId = this.personas.createUser("ws-watchdog", "ACTIVE");
		TokenPair session = session(userId);
		UUID family = this.jdbc.queryForObject("select token_family_id from auth_sessions where user_id = ?", UUID.class, userId);
		try (StompTestClient client = withBearer(session.accessToken())) {
			client.subscribe(QUEUE, userId, this.publisher);
			assertThat(this.watchdog.sweep()).isZero();
			assertThat(client.isOpen()).isTrue();

			// deny-listed behind the gateway's back (no SessionsRevoked event)
			this.denyList.deny(List.of(family));
			assertThat(this.watchdog.sweep()).isGreaterThanOrEqualTo(1);
			assertThat(client.awaitClosed(5)).isTrue();
		}
	}

	@Test
	void theWatchdogClosesConnectionsOfSuspendedAccounts() throws Exception {
		UUID userId = this.personas.createUser("ws-suspended", "ACTIVE");
		TokenPair session = session(userId);
		try (StompTestClient client = withBearer(session.accessToken())) {
			client.subscribe(QUEUE, userId, this.publisher);
			this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId);
			this.caches.getCache("auth-users").clear();
			this.watchdog.sweep();
			assertThat(client.awaitClosed(5)).isTrue();
		}
	}

	@Test
	void closedConnectionsAreForgotten() throws Exception {
		Persona user = this.personas.newUser("ws-forget");
		int before = this.connections.countFor(user.userId());
		StompTestClient client = withBearer(user.accessToken());
		client.awaitConnected();
		await().atMost(Duration.ofSeconds(5)).until(() -> this.connections.countFor(user.userId()) == before + 1);
		client.close();
		await().atMost(Duration.ofSeconds(5)).until(() -> this.connections.countFor(user.userId()) == before);
	}

}
