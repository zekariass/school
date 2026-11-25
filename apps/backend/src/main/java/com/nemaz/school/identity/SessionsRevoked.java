package com.nemaz.school.identity;

import java.util.List;
import java.util.UUID;

/**
 * One or more session families of a user ended (sign-out, sign-out everywhere, refresh-token
 * reuse, password change, deactivated account). Their access tokens are already deny-listed
 * when this is delivered. Listeners close what was bound to those sessions (WebSocket
 * connections, push devices). Delivered at least once.
 */
public record SessionsRevoked(UUID userId, List<UUID> familyIds) {

	public SessionsRevoked {
		familyIds = List.copyOf(familyIds);
	}

}
