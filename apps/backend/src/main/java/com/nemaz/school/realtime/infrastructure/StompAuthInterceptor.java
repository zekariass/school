package com.nemaz.school.realtime.infrastructure;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.identity.SessionAuthenticator;
import com.nemaz.school.identity.SessionAuthenticator.AuthenticatedSession;
import com.nemaz.school.realtime.application.RealtimeConnections;
import com.nemaz.school.realtime.application.RealtimeTickets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;

/**
 * The gateway's access rules, applied to every frame a client sends.
 * <ul>
 * <li><b>CONNECT</b> needs exactly one credential: {@code Authorization: Bearer <access token>}
 * (native apps) or {@code ticket: <single-use ticket>} (browsers). Either must pass the same
 * session and account checks as an HTTP request. A connection without an {@code Origin} header
 * is not a browser, so it cannot be driven by another site; such connections may use a bearer
 * token but never a ticket (a ticket exists to stand in for ambient browser trust). The
 * principal name is the user id.</li>
 * <li><b>SUBSCRIBE</b> only to {@code /user/queue/<name>}: the broker maps that to the
 * caller's own session, so other users' queues are unreachable.</li>
 * <li><b>SEND</b> is refused outright (no inbound messaging yet).</li>
 * <li>Every other frame (except DISCONNECT) needs a connection that completed CONNECT.</li>
 * </ul>
 * A refused frame becomes an ERROR frame with a stable reason code, and the connection closes.
 */
class StompAuthInterceptor implements ChannelInterceptor {

	/** Session attribute set by the handshake: did the HTTP upgrade carry an Origin header. */
	static final String HAS_ORIGIN = "realtime.hasOrigin";

	static final int MAX_CONNECTIONS_PER_USER = 20;

	private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

	private static final Pattern USER_QUEUE = Pattern.compile("^/user/queue/[A-Za-z0-9._-]{1,64}$");

	private static final String BEARER = "Bearer ";

	private final SessionAuthenticator sessions;

	private final RealtimeTickets tickets;

	private final RealtimeConnections connections;

	StompAuthInterceptor(SessionAuthenticator sessions, RealtimeTickets tickets, RealtimeConnections connections) {
		this.sessions = sessions;
		this.tickets = tickets;
		this.connections = connections;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}
		StompCommand command = accessor.getCommand();
		try {
			switch (command) {
				case CONNECT, STOMP -> connect(accessor);
				case SUBSCRIBE -> subscribe(accessor);
				case SEND -> throw new RealtimeAccessException("forbidden.send");
				// also synthesized by the transport when any connection closes, authenticated or not
				case DISCONNECT -> {
				}
				default -> requireConnected(accessor);
			}
		}
		catch (RealtimeAccessException ex) {
			log.debug("STOMP {} refused for session {}: {}", command, accessor.getSessionId(), ex.code());
			// the ERROR frame is built from this exception; the transport closes after sending it
			throw ex;
		}
		return message;
	}

	private void connect(StompHeaderAccessor accessor) {
		String authorization = firstHeader(accessor, "Authorization");
		String ticket = firstHeader(accessor, "ticket");
		if ((authorization == null) == (ticket == null)) {
			throw new RealtimeAccessException("auth.credentials_required");
		}
		AuthenticatedSession session;
		if (authorization != null) {
			if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
				throw new RealtimeAccessException("auth.invalid_token");
			}
			try {
				session = this.sessions.authenticateAccessToken(authorization.substring(BEARER.length()).trim());
			}
			catch (AuthenticationException ex) {
				throw new RealtimeAccessException("auth.invalid_token");
			}
		}
		else {
			Map<String, Object> attributes = accessor.getSessionAttributes();
			boolean hasOrigin = attributes != null && Boolean.TRUE.equals(attributes.get(HAS_ORIGIN));
			if (!hasOrigin) {
				throw new RealtimeAccessException("auth.ticket_requires_origin");
			}
			RealtimeTickets.Redeemed redeemed = this.tickets.redeem(ticket)
				.orElseThrow(() -> new RealtimeAccessException("auth.invalid_ticket"));
			if (!this.sessions.isActive(redeemed.userId(), redeemed.sessionId())) {
				throw new RealtimeAccessException("auth.invalid_ticket");
			}
			session = new AuthenticatedSession(redeemed.userId(), redeemed.sessionId());
		}
		if (this.connections.countFor(session.userId()) >= MAX_CONNECTIONS_PER_USER) {
			throw new RealtimeAccessException("realtime.too_many_connections");
		}
		accessor.setUser(new UserPrincipal(session.userId()));
		this.connections.bind(accessor.getSessionId(), session.userId(), session.sessionId());
	}

	private void subscribe(StompHeaderAccessor accessor) {
		requireConnected(accessor);
		String destination = accessor.getDestination();
		if (destination == null || !USER_QUEUE.matcher(destination).matches()) {
			throw new RealtimeAccessException("forbidden.destination");
		}
	}

	private static void requireConnected(StompHeaderAccessor accessor) {
		if (accessor.getUser() == null) {
			throw new RealtimeAccessException("auth.credentials_required");
		}
	}

	private static String firstHeader(StompHeaderAccessor accessor, String name) {
		String value = accessor.getFirstNativeHeader(name);
		if (value == null) {
			value = accessor.getFirstNativeHeader(name.toLowerCase());
		}
		return (value == null || value.isBlank()) ? null : value;
	}

	/** The STOMP principal: its name is the user id, which is what {@code /user/...} destinations route by. */
	record UserPrincipal(UUID userId) implements Principal {

		@Override
		public String getName() {
			return this.userId.toString();
		}

	}

}
