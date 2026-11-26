package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.EventScope;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.EventNotifier.Kind;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.calendar.application.EventRows.OverrideRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code UPCOMING_EVENT} reminders: for every occurrence that starts within its lead time, one reminder per person, ever
 * ({@code event_reminders} is the memory: the job can run any number of times, on several instances, or late, and nobody gets a
 * second one for the same occurrence). Each occurrence is handled in its own transaction (the memory rows and the
 * notifications commit together).
 *
 * <p>
 * <b>Lead time</b> (school settings): {@code calendar.reminder.event_minutes} (default one day; also for personal events,
 * from the platform default) and {@code calendar.reminder.parent_meeting_minutes} (default one hour); 0 turns reminders off.
 * <b>Who:</b> personal events: the owner; parent meetings: the organiser and the invited guardians whose link still holds,
 * except those who answered NO; other events: the people who answered YES or MAYBE, or everybody in the audience when the
 * school chose {@code calendar.reminder.audience = EVERYONE} (except those who answered NO). Cancelled events and cancelled
 * occurrences are never reminded; a moved occurrence is reminded for its new time. Pushes follow the recipients' quiet hours
 * as every notification does; the in-app item is immediate. A parent meeting's text never contains its title or the child.
 */
@Component
class EventReminders {

	private static final Logger log = LoggerFactory.getLogger(EventReminders.class);

	private static final int PAGE = 200;

	private static final int MAX_PAGES = 25;

	private final JdbcClient jdbc;

	private final CalendarSettings settings;

	private final EventAudiences audiences;

	private final EventNotifier notifier;

	private final Clock clock;

	private final TransactionTemplate tx;

	EventReminders(JdbcClient jdbc, CalendarSettings settings, EventAudiences audiences, EventNotifier notifier, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.audiences = audiences;
		this.notifier = notifier;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/** One pass; returns the number of reminders written. */
	int run() {
		Instant now = this.clock.instant();
		Instant horizon = now.plus(Duration.ofMinutes(CalendarSettings.MAX_LEAD_MINUTES));
		int sent = 0;
		UUID after = new UUID(0, 0);
		for (int page = 0; page < MAX_PAGES; page++) {
			List<EventRow> rows = this.jdbc.sql("select " + EventRows.COLUMNS + """
					 from events e where e.deleted_at is null and e.status = 'SCHEDULED' and e.id > :after
					   and ((e.recurrence_rule is null and e.start_at > :now and e.start_at <= :horizon)
					     or (e.recurrence_rule is not null and e.start_at <= :horizon_ext
					         and (e.recurrence_ends_at is null or e.recurrence_ends_at > :now)))
					order by e.id limit :n""")
				.param("after", after)
				.param("now", Timestamp.from(now))
				.param("horizon", Timestamp.from(horizon))
				.param("horizon_ext", Timestamp.from(horizon.plus(OccurrenceService.MAX_SHIFT)))
				.param("n", PAGE)
				.query((rs, i) -> EventRows.map(rs))
				.list();
			for (EventRow row : rows) {
				try {
					sent += remind(row, now);
				}
				catch (RuntimeException ex) {
					// one broken event never blocks the others; the next run tries again
					log.warn("Reminders of event {} failed: {}", row.id(), ex.getClass().getSimpleName());
				}
			}
			if (rows.size() < PAGE) {
				break;
			}
			after = rows.getLast().id();
		}
		this.jdbc.sql("delete from event_reminders where occurrence_start < :cutoff")
			.param("cutoff", Timestamp.from(now.minus(Duration.ofDays(1))))
			.update();
		return sent;
	}

	private int remind(EventRow row, Instant now) {
		boolean meeting = row.type() == EventType.PARENT_MEETING;
		int lead = meeting ? this.settings.meetingReminderMinutes(row.schoolId())
				: this.settings.eventReminderMinutes(row.schoolId());
		if (lead <= 0) {
			return 0;
		}
		Instant until = now.plus(Duration.ofMinutes(lead));
		int sent = 0;
		if (!row.recurring()) {
			if (row.startAt().isAfter(now) && !row.startAt().isAfter(until)) {
				sent += send(row, row.startAt(), row.startAt(), null);
			}
			return sent;
		}
		Recurrence rule = Recurrence.parse(row.recurrenceRule());
		Map<Instant, OverrideRow> overrides = new HashMap<>();
		this.jdbc.sql("select " + EventRows.OVERRIDE_COLUMNS + " from event_overrides o where o.event_id = :id")
			.param("id", row.id())
			.query((rs, i) -> EventRows.mapOverride(rs))
			.list()
			.forEach((o) -> overrides.put(o.occurrenceStart(), o));
		Instant from = now.minus(OccurrenceService.MAX_SHIFT);
		Instant to = until.plus(OccurrenceService.MAX_SHIFT);
		for (Recurrence.Occurrence occurrence : rule.expand(row.series(), from, to, EventSources.MAX_OCCURRENCES_PER_SERIES, null)) {
			OverrideRow override = overrides.get(occurrence.start());
			if (override != null && override.cancelled()) {
				continue;
			}
			Instant start = occurrence.start();
			String title = row.title();
			if (override != null) {
				if (override.startAt() != null) {
					start = override.startAt();
				}
				else if (override.startDate() != null) {
					start = override.startDate().atStartOfDay(row.zone()).toInstant();
				}
				if (override.title() != null) {
					title = override.title();
				}
			}
			if (start.isAfter(now) && !start.isAfter(until)) {
				sent += send(row.withTitle(title), start, occurrence.start(), EventRows.occurrenceId(occurrence.start()));
			}
		}
		return sent;
	}

	/** The reminders of one occurrence, atomically with their memory rows; returns how many were written. */
	private int send(EventRow row, Instant start, Instant occurrenceStart, String occurrenceId) {
		Integer sent = this.tx.execute((status) -> {
			Set<UUID> recipients = recipients(row);
			Set<UUID> fresh = new LinkedHashSet<>();
			for (UUID user : recipients) {
				int inserted = this.jdbc.sql("""
						insert into event_reminders (event_id, occurrence_start, user_id) values (:e, :t, :u)
						on conflict do nothing""")
					.param("e", row.id())
					.param("t", Timestamp.from(occurrenceStart))
					.param("u", user)
					.update();
				if (inserted == 1) {
					fresh.add(user);
				}
			}
			return this.notifier.send(Kind.REMINDER, row, start, null, fresh, occurrenceId);
		});
		return (sent != null) ? sent : 0;
	}

	private Set<UUID> recipients(EventRow row) {
		if (row.scope() == EventScope.PERSONAL) {
			return Set.of(row.ownerId());
		}
		Set<UUID> said = new LinkedHashSet<>(this.audiences.answered(row.id(), List.of("NO")));
		Set<UUID> people;
		if (row.type() == EventType.PARENT_MEETING) {
			people = new LinkedHashSet<>(this.audiences.participants(row));
		}
		else if (this.settings.remindEveryone(row.schoolId())) {
			people = new LinkedHashSet<>(this.audiences.audience(row));
		}
		else {
			people = new LinkedHashSet<>(this.audiences.answered(row.id(), List.of("YES", "MAYBE")));
		}
		people.removeAll(said);
		return people;
	}

}
