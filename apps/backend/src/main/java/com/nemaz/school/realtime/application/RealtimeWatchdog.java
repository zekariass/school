package com.nemaz.school.realtime.application;

import com.nemaz.school.identity.SessionAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, each instance closes its own connections whose session family is deny-listed
 * or whose account is no longer ACTIVE. This covers anything the revocation event missed
 * (Redis outage, an account suspended without a session change). Deliberately not behind
 * ShedLock: each instance owns different connections.
 */
@Component
public class RealtimeWatchdog {

	private static final Logger log = LoggerFactory.getLogger(RealtimeWatchdog.class);

	private final RealtimeConnections connections;

	private final SessionAuthenticator sessions;

	RealtimeWatchdog(RealtimeConnections connections, SessionAuthenticator sessions) {
		this.connections = connections;
		this.sessions = sessions;
	}

	@Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
	public int sweep() {
		int closed = this.connections.sweep((userId, familyId) -> !this.sessions.isActive(userId, familyId));
		if (closed > 0) {
			log.info("Closed {} WebSocket connections of ended sessions", closed);
		}
		return closed;
	}

}
