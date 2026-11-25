package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.notifications.domain.NotificationRow;
import com.nemaz.school.notifications.infrastructure.NotificationStore;
import com.nemaz.school.notifications.infrastructure.NotificationStore.Position;
import com.nemaz.school.realtime.RealtimePublisher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own inbox. Every operation is scoped by the caller's id, so another
 * user's notification is simply "not found". Changes are echoed to the user's other open apps
 * ({@code notification.read}, {@code notification.archived}) so badges stay in step.
 */
@Service
public class InboxService {

	private final NotificationStore store;

	private final NotificationService notifications;

	private final RealtimePublisher realtime;

	private final Clock clock;

	InboxService(NotificationStore store, NotificationService notifications, RealtimePublisher realtime, Clock clock) {
		this.store = store;
		this.notifications = notifications;
		this.realtime = realtime;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CursorPage<NotificationView> list(UUID userId, boolean unreadOnly, String cursor, Integer limit) {
		int pageSize = Cursors.clampLimit(limit);
		Position after = null;
		if (cursor != null && !cursor.isBlank()) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				after = Position.of(Long.parseLong(parts.get(0)), UUID.fromString(parts.get(1)));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<NotificationRow> rows = this.store.list(userId, unreadOnly, after, pageSize + 1);
		return CursorPage.fromOverfetch(rows, pageSize, (r) -> {
			Position position = new Position(r.createdAt().truncatedTo(ChronoUnit.MICROS), r.id());
			return Cursors.encode(Long.toString(position.micros()), r.id().toString());
		}).map(NotificationView::of);
	}

	@Transactional(readOnly = true)
	public int unreadCount(UUID userId) {
		return this.store.unreadCount(userId);
	}

	@Transactional
	public void markRead(UUID userId, UUID id) {
		if (!this.store.markRead(userId, id, this.clock.instant())) {
			throw ApiException.notFound("notification.not_found");
		}
		int unread = this.store.unreadCount(userId);
		AfterCommit.run(() -> this.realtime.toUser(userId, RealtimePublisher.NOTIFICATIONS_QUEUE,
				RealtimeEvents.Read.ids(List.of(id), unread)));
	}

	@Transactional
	public int markAllRead(UUID userId) {
		int changed = this.store.markAllRead(userId, this.clock.instant());
		if (changed > 0) {
			AfterCommit.run(() -> this.realtime.toUser(userId, RealtimePublisher.NOTIFICATIONS_QUEUE,
					RealtimeEvents.Read.all(0)));
		}
		return changed;
	}

	@Transactional
	public void archive(UUID userId, UUID id) {
		if (!this.store.archive(userId, id, this.clock.instant())) {
			throw ApiException.notFound("notification.not_found");
		}
		int unread = this.store.unreadCount(userId);
		AfterCommit.run(() -> this.realtime.toUser(userId, RealtimePublisher.NOTIFICATIONS_QUEUE,
				RealtimeEvents.Archived.of(id, unread)));
	}

	/** The platform console's "send me a test notification". */
	@Transactional
	public UUID sendTest(UUID userId) {
		return this.notifications
			.notify(NotificationRequest.to("TEST_NOTIFICATION", userId).path("/notifications").urgent().build())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("user.not_found"));
	}

}
