package com.nemaz.school.realtime.application;

import com.nemaz.school.identity.SessionsRevoked;
import com.nemaz.school.realtime.infrastructure.RedisRealtimeBus;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Ends WebSocket connections with the session family they authenticated with. The event is
 * handled once for the whole cluster, so it is re-broadcast on Redis and every instance closes
 * its own connections (see {@link RedisRealtimeBus}). Closing an already closed connection is a no-op.
 */
@Component
class RealtimeSessionEvents {

	private final RedisRealtimeBus bus;

	RealtimeSessionEvents(RedisRealtimeBus bus) {
		this.bus = bus;
	}

	@ApplicationModuleListener
	void on(SessionsRevoked event) {
		this.bus.broadcastRevoked(event.userId(), event.familyIds());
	}

}
