package com.nemaz.school.notifications.application;

import java.util.List;
import java.util.UUID;

/** Payloads pushed to a user's open apps on {@code /user/queue/notifications}. */
public final class RealtimeEvents {

	private RealtimeEvents() {
	}

	public record Created(String type, NotificationView notification, int unreadCount) {

		static Created of(NotificationView notification, int unreadCount) {
			return new Created("notification.created", notification, unreadCount);
		}

	}

	/** {@code all} is true for "mark all read"; otherwise {@code ids} lists the notifications read. */
	public record Read(String type, List<UUID> ids, boolean all, int unreadCount) {

		static Read ids(List<UUID> ids, int unreadCount) {
			return new Read("notification.read", ids, false, unreadCount);
		}

		static Read all(int unreadCount) {
			return new Read("notification.read", List.of(), true, unreadCount);
		}

	}

	public record Archived(String type, UUID id, int unreadCount) {

		static Archived of(UUID id, int unreadCount) {
			return new Archived("notification.archived", id, unreadCount);
		}

	}

}
