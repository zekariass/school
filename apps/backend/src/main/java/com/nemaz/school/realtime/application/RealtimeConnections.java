package com.nemaz.school.realtime.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * The WebSocket connections of this instance and the user and session family each one
 * authenticated as. Revocation closes connections through here.
 */
@Component
public class RealtimeConnections {

	/** Application close code: the session was revoked or the account deactivated. */
	public static final CloseStatus SESSION_ENDED = new CloseStatus(4401, "session ended");

	private static final Logger log = LoggerFactory.getLogger(RealtimeConnections.class);

	private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();

	private final Map<String, Binding> bindings = new ConcurrentHashMap<>();

	/** Called when the transport opens, before the client has sent CONNECT. */
	public void opened(WebSocketSession session) {
		this.sockets.put(session.getId(), session);
	}

	public void closed(String sessionId) {
		this.sockets.remove(sessionId);
		this.bindings.remove(sessionId);
	}

	/** Records who a connection authenticated as (STOMP CONNECT succeeded). */
	public void bind(String sessionId, UUID userId, UUID sessionFamilyId) {
		this.bindings.put(sessionId, new Binding(userId, sessionFamilyId));
	}

	public int countFor(UUID userId) {
		return (int) this.bindings.values().stream().filter((b) -> b.userId().equals(userId)).count();
	}

	public boolean hasUser(UUID userId) {
		return this.bindings.values().stream().anyMatch((b) -> b.userId().equals(userId));
	}

	public int size() {
		return this.sockets.size();
	}

	/** Closes the user's connections that authenticated with one of the session families. Returns how many. */
	public int closeFamilies(UUID userId, Collection<UUID> familyIds) {
		Set<UUID> families = Set.copyOf(familyIds);
		return closeWhere((id, binding) -> binding.userId().equals(userId) && families.contains(binding.familyId()));
	}

	/** Closes every connection for which {@code isEnded} says the (user, family) pair is no longer valid. */
	public int sweep(BiPredicate<UUID, UUID> isEnded) {
		return closeWhere((id, binding) -> isEnded.test(binding.userId(), binding.familyId()));
	}

	/** Closes a connection that has not authenticated (or failed to). */
	public void close(String sessionId, CloseStatus status) {
		WebSocketSession socket = this.sockets.get(sessionId);
		if (socket != null) {
			closeQuietly(socket, status);
		}
	}

	private int closeWhere(BiPredicate<String, Binding> predicate) {
		List<String> ids = new ArrayList<>();
		this.bindings.forEach((id, binding) -> {
			if (predicate.test(id, binding)) {
				ids.add(id);
			}
		});
		for (String id : ids) {
			this.bindings.remove(id);
			WebSocketSession socket = this.sockets.get(id);
			if (socket != null) {
				closeQuietly(socket, SESSION_ENDED);
			}
		}
		return ids.size();
	}

	private static void closeQuietly(WebSocketSession socket, CloseStatus status) {
		try {
			if (socket.isOpen()) {
				socket.close(status);
			}
		}
		catch (IOException | RuntimeException ex) {
			log.debug("Closing WebSocket session {}: {}", socket.getId(), ex.getMessage());
		}
	}

	record Binding(UUID userId, UUID familyId) {
	}

}
