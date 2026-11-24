package com.nemaz.school.realtime.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.realtime.RealtimePublisher;
import com.nemaz.school.realtime.application.RealtimeConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Cluster fan-out over Redis pub/sub. {@link #toUser} publishes to {@value #USER_EVENTS}; every
 * instance (this one included) receives it and hands it to its own in-memory STOMP broker, which
 * delivers to the user's sessions connected there. Revocations travel on {@value #SESSION_EVENTS}
 * so each instance closes its own connections.
 */
@Component
public class RedisRealtimeBus implements RealtimePublisher {

	public static final String USER_EVENTS = "rt:user-events";

	public static final String SESSION_EVENTS = "rt:session-events";

	private static final Logger log = LoggerFactory.getLogger(RedisRealtimeBus.class);

	private final StringRedisTemplate redis;

	private final JsonMapper json;

	private final SimpMessagingTemplate messaging;

	private final RealtimeConnections connections;

	RedisRealtimeBus(StringRedisTemplate redis, JsonMapper json, SimpMessagingTemplate messaging,
			RealtimeConnections connections) {
		this.redis = redis;
		this.json = json;
		this.messaging = messaging;
		this.connections = connections;
	}

	@Override
	public void toUser(UUID userId, String destination, Object payload) {
		if (!destination.startsWith("/queue/")) {
			throw new IllegalArgumentException("Not a user queue: " + destination);
		}
		ObjectNode envelope = this.json.createObjectNode();
		envelope.put("userId", userId.toString());
		envelope.put("destination", destination);
		envelope.set("payload", this.json.valueToTree(payload));
		publish(USER_EVENTS, envelope.toString());
	}

	/** Asks every instance to close this user's connections of the given session families. */
	public void broadcastRevoked(UUID userId, List<UUID> familyIds) {
		ObjectNode envelope = this.json.createObjectNode();
		envelope.put("type", "sessions.revoked");
		envelope.put("userId", userId.toString());
		var families = envelope.putArray("familyIds");
		familyIds.forEach((id) -> families.add(id.toString()));
		publish(SESSION_EVENTS, envelope.toString());
	}

	private void publish(String channel, String message) {
		try {
			this.redis.convertAndSend(channel, message);
		}
		catch (DataAccessException ex) {
			log.warn("Could not publish to {}: {}", channel, ex.getMessage());
		}
	}

	MessageListener userEvents() {
		return (Message message, byte[] pattern) -> {
			try {
				JsonNode envelope = this.json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
				UUID userId = UUID.fromString(envelope.get("userId").asString());
				if (this.connections.hasUser(userId)) {
					this.messaging.convertAndSendToUser(userId.toString(), envelope.get("destination").asString(),
							envelope.get("payload").toString());
				}
			}
			catch (RuntimeException ex) {
				log.warn("Ignoring malformed realtime event: {}", ex.getMessage());
			}
		};
	}

	MessageListener sessionEvents() {
		return (Message message, byte[] pattern) -> {
			try {
				JsonNode envelope = this.json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
				UUID userId = UUID.fromString(envelope.get("userId").asString());
				List<UUID> families = envelope.get("familyIds").valueStream().map((n) -> UUID.fromString(n.asString())).toList();
				this.connections.closeFamilies(userId, families);
			}
			catch (RuntimeException ex) {
				log.warn("Ignoring malformed session event: {}", ex.getMessage());
			}
		};
	}

}
