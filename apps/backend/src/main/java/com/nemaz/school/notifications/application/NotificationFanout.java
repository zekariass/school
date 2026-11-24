package com.nemaz.school.notifications.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.notifications.NotificationsCreated;
import com.nemaz.school.notifications.domain.NotificationRow;
import com.nemaz.school.notifications.infrastructure.NotificationStore;
import com.nemaz.school.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * After the inbox rows are committed: (a) tell the recipients' open apps through the realtime
 * gateway, (b) fan out push. A failure in (a) never blocks (b), and a failure in (b) is retried
 * through the event outbox (rows are unique per notification and device, so retrying is safe).
 * The push calls run inside this listener's own transaction: acceptable while volumes are small;
 * a dedicated queue replaces it if push volume grows.
 */
@Component
class NotificationFanout {

	private static final Logger log = LoggerFactory.getLogger(NotificationFanout.class);

	private final NotificationStore store;

	private final RealtimePublisher realtime;

	private final PushFanout push;

	NotificationFanout(NotificationStore store, RealtimePublisher realtime, PushFanout push) {
		this.store = store;
		this.realtime = realtime;
		this.push = push;
	}

	@ApplicationModuleListener
	void on(NotificationsCreated event) {
		List<NotificationRow> rows = this.store.findByIds(event.notificationIds());
		if (rows.isEmpty()) {
			return;
		}
		try {
			Map<UUID, Integer> unread = this.store
				.unreadCounts(rows.stream().map(NotificationRow::userId).collect(Collectors.toSet()));
			for (NotificationRow row : rows) {
				this.realtime.toUser(row.userId(), RealtimePublisher.NOTIFICATIONS_QUEUE,
						RealtimeEvents.Created.of(NotificationView.of(row), unread.getOrDefault(row.userId(), 0)));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Real-time delivery of notifications failed: {}", ex.getMessage());
		}
		this.push.fanOut(rows);
	}

}
