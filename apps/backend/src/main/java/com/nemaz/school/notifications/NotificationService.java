package com.nemaz.school.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

	/**
	 * For each of the users who is inside their own quiet hours right now, when those end. Users not in quiet hours (or
	 * without quiet hours) are absent. Lets other channels (email, SMS) follow the same policy as push.
	 */
	Map<UUID, Instant> quietHoursEnds(Collection<UUID> userIds);

	/**
	 * Cancels the pushes of this source that are held back for quiet hours and not sent yet (a withdrawn announcement must
	 * not ring a phone later). Returns how many were cancelled.
	 */
	int cancelHeldBackPushes(String sourceType, UUID sourceId);

}
