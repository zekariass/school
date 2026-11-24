package com.nemaz.school.notifications;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.nemaz.school.notifications.domain.PushSender.PushMessage;
import com.nemaz.school.notifications.domain.PushSender.PushResult;
import com.nemaz.school.notifications.domain.PushSender.PushResult.Outcome;
import com.nemaz.school.notifications.infrastructure.ExpoPushSender;
import com.nemaz.school.notifications.infrastructure.PushProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The Expo client against a stub of the Expo push API (no network). */
class ExpoPushSenderTest {

	private final JsonMapper json = JsonMapper.builder().build();

	private HttpServer server;

	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	private final List<String> authorization = new CopyOnWriteArrayList<>();

	private final AtomicReference<Function<JsonNode, Response>> handler = new AtomicReference<>();

	record Response(int status, String body) {
	}

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/--/api/v2/push/send", (exchange) -> {
			JsonNode body = this.json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			this.requests.add(body);
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			this.authorization.add((auth != null) ? auth : "");
			Response response = this.handler.get().apply(body);
			byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(response.status(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		this.server.start();
	}

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	private ExpoPushSender sender(String accessToken) {
		URI base = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
		return new ExpoPushSender(new PushProperties.Expo(base, accessToken, Duration.ofSeconds(2)), this.json);
	}

	private static PushMessage message(String token, boolean urgent) {
		return new PushMessage("EXPO", token, "Title " + token, "Body", Map.of("path", "/notifications"), urgent);
	}

	private static String ok(int count) {
		List<String> tickets = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			tickets.add("{\"status\":\"ok\",\"id\":\"ticket-" + i + "\"}");
		}
		return "{\"data\":[" + String.join(",", tickets) + "]}";
	}

	@Test
	void sendsTheDocumentedPayloadAndReadsTickets() {
		this.handler.set((body) -> new Response(200, ok(body.size())));

		List<PushResult> results = sender("").send(List.of(message("ExponentPushToken[a]", false),
				message("ExponentPushToken[b]", true)));

		assertThat(results).extracting(PushResult::outcome).containsExactly(Outcome.SENT, Outcome.SENT);
		assertThat(results.getFirst().providerMessageId()).isEqualTo("ticket-0");
		JsonNode request = this.requests.getFirst();
		assertThat(request.isArray()).isTrue();
		assertThat(request.get(0).get("to").asString()).isEqualTo("ExponentPushToken[a]");
		assertThat(request.get(0).get("title").asString()).isEqualTo("Title ExponentPushToken[a]");
		assertThat(request.get(0).get("data").get("path").asString()).isEqualTo("/notifications");
		assertThat(request.get(0).get("channelId").asString()).isEqualTo("default");
		assertThat(request.get(0).get("priority").asString()).isEqualTo("default");
		assertThat(request.get(1).get("priority").asString()).isEqualTo("high");
		assertThat(this.authorization.getFirst()).isEmpty();
	}

	@Test
	void sendsTheOptionalAccessTokenAsBearer() {
		this.handler.set((body) -> new Response(200, ok(body.size())));
		sender("secret-token").send(List.of(message("ExponentPushToken[a]", false)));
		assertThat(this.authorization).containsExactly("Bearer secret-token");
	}

	@Test
	void batchesOfAtMostOneHundred() {
		this.handler.set((body) -> new Response(200, ok(body.size())));
		List<PushMessage> messages = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			messages.add(message("ExponentPushToken[" + i + "]", false));
		}

		List<PushResult> results = sender("").send(messages);

		assertThat(this.requests).extracting(JsonNode::size).containsExactly(100, 100, 50);
		assertThat(results).hasSize(250).allMatch((r) -> r.outcome() == Outcome.SENT);
		// order is preserved across batches
		assertThat(this.requests.get(2).get(0).get("to").asString()).isEqualTo("ExponentPushToken[200]");
	}

	@Test
	void mapsTicketErrorsPerMessage() {
		this.handler.set((body) -> new Response(200, """
				{"data":[
				  {"status":"ok","id":"t1"},
				  {"status":"error","message":"The recipient device is not registered","details":{"error":"DeviceNotRegistered"}},
				  {"status":"error","message":"Too much data","details":{"error":"MessageTooBig"}},
				  {"status":"error","message":"Something odd"}
				]}"""));

		List<PushResult> results = sender("").send(List.of(message("ExponentPushToken[a]", false),
				message("ExponentPushToken[b]", false), message("ExponentPushToken[c]", false),
				message("ExponentPushToken[d]", false)));

		assertThat(results).extracting(PushResult::outcome)
			.containsExactly(Outcome.SENT, Outcome.DEVICE_NOT_REGISTERED, Outcome.FAILED, Outcome.FAILED);
		assertThat(results.get(1).error()).isEqualTo("DeviceNotRegistered");
		assertThat(results.get(2).error()).isEqualTo("MessageTooBig");
		assertThat(results.get(3).error()).isEqualTo("Something odd");
	}

	@Test
	void aFailedBatchFailsOnlyItsOwnMessages() {
		this.handler.set((body) -> (body.get(0).get("to").asString().equals("ExponentPushToken[0]"))
				? new Response(503, "{\"errors\":[{\"code\":\"SERVICE_UNAVAILABLE\"}]}") : new Response(200, ok(body.size())));
		List<PushMessage> messages = new ArrayList<>();
		for (int i = 0; i < 101; i++) {
			messages.add(message("ExponentPushToken[" + i + "]", false));
		}

		List<PushResult> results = sender("").send(messages);

		assertThat(results.subList(0, 100)).allMatch((r) -> r.outcome() == Outcome.FAILED);
		assertThat(results.get(0).error()).startsWith("expo_http");
		assertThat(results.get(100).outcome()).isEqualTo(Outcome.SENT);
	}

	@Test
	void topLevelErrorsAndGarbageFailAllMessages() {
		this.handler.set((body) -> new Response(200, "{\"errors\":[{\"code\":\"PUSH_TOO_MANY_EXPERIENCE_IDS\",\"message\":\"x\"}]}"));
		assertThat(sender("").send(List.of(message("ExponentPushToken[a]", false))))
			.singleElement()
			.satisfies((r) -> {
				assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
				assertThat(r.error()).isEqualTo("expo_error: PUSH_TOO_MANY_EXPERIENCE_IDS");
			});

		this.handler.set((body) -> new Response(200, "not json"));
		assertThat(sender("").send(List.of(message("ExponentPushToken[a]", false)))).singleElement()
			.satisfies((r) -> assertThat(r.outcome()).isEqualTo(Outcome.FAILED));

		// fewer tickets than messages is a failure, never a shifted mapping
		this.handler.set((body) -> new Response(200, ok(1)));
		assertThat(sender("").send(List.of(message("ExponentPushToken[a]", false), message("ExponentPushToken[b]", false))))
			.allMatch((r) -> r.outcome() == Outcome.FAILED);
	}

	@Test
	void anUnreachableServerFailsRetryably() {
		ExpoPushSender sender = sender("");
		this.server.stop(0);
		List<PushResult> results = sender.send(List.of(message("ExponentPushToken[a]", false)));
		assertThat(results).singleElement().satisfies((r) -> {
			assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
			assertThat(r.error()).startsWith("expo_http");
		});
		this.server = null;
	}

	@Test
	void onlyExpoDevicesAreSentThroughExpo() {
		assertThat(sender("").supports("EXPO")).isTrue();
		assertThat(sender("").supports("FCM")).isFalse();
	}

}
