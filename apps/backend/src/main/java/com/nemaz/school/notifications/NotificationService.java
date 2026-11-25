package com.nemaz.school.notifications;

import java.util.List;
import java.util.UUID;

/** What other modules use to notify people. Joins the caller's transaction. */
public interface NotificationService {

	/**
	 * Writes one inbox row per recipient (title and body rendered in the recipient's language) and
	 * publishes {@link NotificationsCreated}. Duplicate and unknown recipients are ignored.
	 *
	 * @return the ids of the notifications created
	 * @throws IllegalArgumentException when the type is unknown or the request is malformed (a
	 * programming error, not a user error)
	 */
	List<UUID> notify(NotificationRequest request);

	/** True when {@code userId} already has a notification of this type for this source (for idempotent listeners). */
	boolean alreadyNotified(UUID userId, String typeCode, String sourceType, UUID sourceId);

}
