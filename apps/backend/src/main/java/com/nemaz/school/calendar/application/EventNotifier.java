package com.nemaz.school.calendar.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The notifications of the calendar, in the caller's transaction.
 *
 * <p>
 * <b>Text privacy.</b> A parent meeting is about one child and its title may name the child, so its notifications never
 * contain the title, the description, the place or the child: they say "parent meeting", the teacher's name and the time.
 * Ordinary events (school, campus, grade, class, personal) carry the title the organiser chose for everybody, and nothing
 * else (never the description or the place). The route in {@code data.path} is a relative app path.
 */
@Component
class EventNotifier {

	private static final int CHUNK = 500;

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	enum Kind {

		INVITATION("EVENT_INVITATION", "notification.event_invitation"),

		UPDATED("EVENT_UPDATED", "notification.event_updated"),

		CANCELLED("EVENT_CANCELLED", "notification.event_cancelled"),

		REMINDER("UPCOMING_EVENT", "notification.upcoming_event");

		private final String type;

		private final String key;

		Kind(String type, String key) {
			this.type = type;
			this.key = key;
		}

	}

	private final NotificationService notifications;

	private final JdbcClient jdbc;

	EventNotifier(NotificationService notifications, JdbcClient jdbc) {
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	/**
	 * Tells {@code recipients} (the actor is left out) about the event at {@code when} (the time it concerns: the start, or
	 * the occurrence's). Returns how many notifications were written.
	 */
	int send(Kind kind, EventRow event, Instant when, UUID actor, Collection<UUID> recipients, String occurrenceId) {
		List<UUID> people = new ArrayList<>(Set.copyOf(recipients));
		people.remove(actor);
		if (people.isEmpty()) {
			return 0;
		}
		boolean meeting = event.type() == EventType.PARENT_MEETING;
		String time = format(event, when);
		String teacher = meeting ? organiserName(event) : null;
		int written = 0;
		for (int from = 0; from < people.size(); from += CHUNK) {
			List<UUID> chunk = people.subList(from, Math.min(people.size(), from + CHUNK));
			NotificationRequest.Builder builder = NotificationRequest.to(kind.type, chunk)
				.school(event.schoolId())
				.source("EVENT", event.id())
				.path("/calendar/events/" + event.id())
				.data("eventId", event.id().toString());
			if (occurrenceId != null) {
				builder.data("occurrenceId", occurrenceId);
			}
			if (meeting) {
				// no title, no child, no place: only that there is a parent meeting, who asks and when
				builder.messageKey(kind.key + "_meeting");
				builder.args((kind == Kind.REMINDER) ? new Object[] { time } : new Object[] { teacher, time });
			}
			else {
				builder.args(event.title(), time);
			}
			written += this.notifications.notify(builder.build()).size();
		}
		return written;
	}

	private String organiserName(EventRow event) {
		return this.jdbc.sql("select display_name from users where id = :id")
			.param("id", event.createdBy())
			.query(String.class)
			.optional()
			.orElse("");
	}

	/** The time in the event's own zone, language-neutral: {@code 2026-12-03 09:00}, or the date for an all-day event. */
	static String format(EventRow event, Instant when) {
		ZoneId zone = event.zone();
		return event.allDay() ? DATE.format(when.atZone(zone)) : DATE_TIME.format(when.atZone(zone));
	}

}
