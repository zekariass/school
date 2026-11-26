package com.nemaz.school.realtime;

import java.util.UUID;

/**
 * Pushes a message to every connected session of a user, on any backend instance (through
 * Redis pub/sub). Delivery is best effort: a user who is offline simply misses it, so anything
 * that matters must also be stored (the notification inbox is).
 */
public interface RealtimePublisher {

	/** The queue clients subscribe to as {@code /user/queue/notifications}. */
	String NOTIFICATIONS_QUEUE = "/queue/notifications";

	/** The queue of message doorbells ({@code /user/queue/messages}): ids only, content is fetched through the API. */
	String MESSAGES_QUEUE = "/queue/messages";

	/**
	 * @param destination a user queue such as {@link #NOTIFICATIONS_QUEUE}
	 * @param payload serialized as JSON
	 */
	void toUser(UUID userId, String destination, Object payload);

}
