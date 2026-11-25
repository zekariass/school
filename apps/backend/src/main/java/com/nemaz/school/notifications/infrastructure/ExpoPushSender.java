package com.nemaz.school.notifications.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nemaz.school.notifications.domain.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends through the Expo push service: {@code POST <base>/--/api/v2/push/send}, JSON, at most 100
 * messages per request. Per message, a ticket with {@code status: ok} is SENT (its {@code id}
 * is kept for receipts), {@code details.error = DeviceNotRegistered} marks the token dead, any
 * other error is a retryable failure. Receipts ({@code /getReceipts}) are not polled yet, so
 * SENT means "accepted by Expo", not "delivered to the phone".
 */
public class ExpoPushSender implements PushSender {

	static final int BATCH_SIZE = 100;

	private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

	private final RestClient client;

	private final JsonMapper json;

	public ExpoPushSender(PushProperties.Expo props, JsonMapper json) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(props.timeout());
		factory.setReadTimeout(props.timeout());
		String base = props.baseUrl().toString().replaceAll("/+$", "");
		RestClient.Builder builder = RestClient.builder()
			.requestFactory(factory)
			.baseUrl(base)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		if (props.accessToken() != null && !props.accessToken().isBlank()) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.accessToken());
		}
		this.client = builder.build();
		this.json = json;
	}

	@Override
	public boolean supports(String provider) {
		return "EXPO".equals(provider);
	}

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		List<PushResult> results = new ArrayList<>(messages.size());
		for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
			List<PushMessage> batch = messages.subList(from, Math.min(messages.size(), from + BATCH_SIZE));
			results.addAll(sendBatch(batch));
		}
		return results;
	}

	private List<PushResult> sendBatch(List<PushMessage> batch) {
		List<Map<String, Object>> body = batch.stream().map(ExpoPushSender::payload).toList();
		String response;
		try {
			response = this.client.post()
				.uri("/--/api/v2/push/send")
				.contentType(MediaType.APPLICATION_JSON)
				.body(this.json.writeValueAsString(body))
				.retrieve()
				.body(String.class);
		}
		catch (RestClientException ex) {
			log.warn("Expo push request failed: {}", ex.getMessage());
			return failAll(batch, "expo_http: " + ex.getClass().getSimpleName());
		}
		try {
			return parse(batch, this.json.readTree(response));
		}
		catch (RuntimeException ex) {
			log.warn("Expo push response could not be read: {}", ex.getMessage());
			return failAll(batch, "expo_response_invalid");
		}
	}

	static Map<String, Object> payload(PushMessage m) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("to", m.token());
		payload.put("title", m.title());
		if (m.body() != null) {
			payload.put("body", m.body());
		}
		payload.put("data", m.data());
		payload.put("sound", "default");
		payload.put("channelId", "default");
		payload.put("priority", m.urgent() ? "high" : "default");
		return payload;
	}

	private static List<PushResult> parse(List<PushMessage> batch, JsonNode response) {
		JsonNode tickets = response.get("data");
		if (tickets == null || !tickets.isArray() || tickets.size() != batch.size()) {
			String reason = "expo_error";
			JsonNode errors = response.get("errors");
			if (errors != null && errors.isArray() && !errors.isEmpty() && errors.get(0).has("code")) {
				reason = "expo_error: " + errors.get(0).get("code").asString();
			}
			return failAll(batch, reason);
		}
		List<PushResult> results = new ArrayList<>(batch.size());
		for (JsonNode ticket : tickets) {
			if ("ok".equals(text(ticket, "status"))) {
				results.add(PushResult.sent(text(ticket, "id")));
				continue;
			}
			JsonNode details = ticket.get("details");
			String error = (details != null) ? text(details, "error") : null;
			if ("DeviceNotRegistered".equals(error)) {
				results.add(PushResult.deviceNotRegistered());
			}
			else {
				String message = (error != null) ? error : text(ticket, "message");
				results.add(PushResult.failed(truncate((message != null) ? message : "expo_error")));
			}
		}
		return results;
	}

	private static List<PushResult> failAll(List<PushMessage> batch, String error) {
		return batch.stream().map((m) -> PushResult.failed(error)).toList();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asString();
	}

	private static String truncate(String value) {
		return (value.length() > 300) ? value.substring(0, 300) : value;
	}

}
