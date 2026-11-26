package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.calendar.CalendarItem;
import com.nemaz.school.calendar.CalendarQuery;
import com.nemaz.school.calendar.CalendarSource;
import com.nemaz.school.calendar.application.CalendarModels.EventScope;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.calendar.application.EventRows.OverrideRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The native events as calendar sources: {@code EVENT} (school, campus, grade, class and parent-meeting events the caller
 * may see) and {@code PERSONAL_EVENT} (their own). Both ask {@link CalendarAccess#visible} for the rows, expand recurring
 * events for the window only (server side, with the caps below) and merge their exceptions. Descriptions are not part of the
 * union (read the event for the text).
 */
@Component
class EventSources {

	/** Occurrences of one recurring event returned for one request (the window of a year of daily events fits). */
	static final int MAX_OCCURRENCES_PER_SERIES = 500;

	private final JdbcClient jdbc;

	private final CalendarAccess access;

	EventSources(JdbcClient jdbc, CalendarAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	/** {@code EVENT}: events of the schools in the query. */
	@Component
	static class School implements CalendarSource {

		private final EventSources sources;

		School(EventSources sources) {
			this.sources = sources;
		}

		@Override
		public String key() {
			return "EVENT";
		}

		@Override
		public List<CalendarItem> items(CalendarQuery query) {
			return this.sources.items(query, false);
		}

	}

	/** {@code PERSONAL_EVENT}: the caller's own events (never with a child filter, never anybody else's). */
	@Component
	static class Personal implements CalendarSource {

		private final EventSources sources;

		Personal(EventSources sources) {
			this.sources = sources;
		}

		@Override
		public String key() {
			return "PERSONAL_EVENT";
		}

		@Override
		public List<CalendarItem> items(CalendarQuery query) {
			return (query.studentId() != null) ? List.of() : this.sources.items(query, true);
		}

	}

	List<CalendarItem> items(CalendarQuery query, boolean personal) {
		CalendarAccess.Target restricted = personal
				? new CalendarAccess.Target(query.user().userId(), null, Set.of(), Set.of(),
						new CalendarAccess.Scope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()))
				: this.access.resolved(query.user(), query.schoolIds(), query.studentId());
		if (!personal && restricted.schools().isEmpty()) {
			return List.of();
		}
		CalendarAccess.Pred visible = this.access.visible(query.user(), restricted, personal, !personal);
		Instant from = query.from();
		Instant to = query.to();
		Instant fromExt = from.minus(OccurrenceService.MAX_SHIFT).minus(Duration.ofDays(1));
		Instant toExt = to.plus(OccurrenceService.MAX_SHIFT);
		List<EventRow> rows = this.jdbc.sql("select " + EventRows.COLUMNS + " from events e where " + visible.sql() + """
				 and ((e.recurrence_rule is null and e.start_at < :cv_to and (e.end_at > :cv_from or e.start_at >= :cv_from))
				   or (e.recurrence_rule is not null and e.start_at < :cv_to_ext
				       and (e.recurrence_ends_at is null or e.recurrence_ends_at > :cv_from_ext)))
				order by e.start_at, e.id limit :cv_limit""")
			.params(visible.params())
			.param("cv_to", Timestamp.from(to))
			.param("cv_from", Timestamp.from(from))
			.param("cv_to_ext", Timestamp.from(toExt))
			.param("cv_from_ext", Timestamp.from(fromExt))
			.param("cv_limit", query.maxItems() + 1)
			.query((rs, i) -> EventRows.map(rs))
			.list();
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = rows.stream().map(EventRow::id).toList();
		Map<UUID, String> mine = new HashMap<>();
		this.jdbc.sql("select event_id, rsvp_status from event_participants where user_id = :me and event_id in (:ids)")
			.param("me", query.user().userId())
			.param("ids", ids)
			.query((rs) -> {
				mine.put(rs.getObject("event_id", UUID.class), rs.getString("rsvp_status"));
			});
		Map<UUID, Map<Instant, OverrideRow>> overrides = new HashMap<>();
		List<UUID> recurring = rows.stream().filter(EventRow::recurring).map(EventRow::id).toList();
		if (!recurring.isEmpty()) {
			this.jdbc.sql("select " + EventRows.OVERRIDE_COLUMNS
					+ " from event_overrides o where o.event_id in (:ids) and o.occurrence_start >= :from and o.occurrence_start < :to")
				.param("ids", recurring)
				.param("from", Timestamp.from(fromExt))
				.param("to", Timestamp.from(toExt))
				.query((rs, i) -> EventRows.mapOverride(rs))
				.list()
				.forEach((o) -> overrides.computeIfAbsent(o.eventId(), (k) -> new HashMap<>()).put(o.occurrenceStart(), o));
		}
		List<CalendarItem> out = new ArrayList<>();
		for (EventRow row : rows) {
			String rsvp = row.rsvpEnabled() ? mine.getOrDefault(row.id(), null) : null;
			if (!row.recurring()) {
				out.add(item(row, row.startAt(), row.endAt(), row.startDate(), row.endDate(), null, null, false, row.status(),
						rsvp));
				continue;
			}
			Recurrence rule = Recurrence.parse(row.recurrenceRule());
			Map<Instant, OverrideRow> own = overrides.getOrDefault(row.id(), Map.of());
			for (Recurrence.Occurrence occurrence : rule.expand(row.series(), fromExt, toExt, MAX_OCCURRENCES_PER_SERIES, null)) {
				OverrideRow override = own.get(occurrence.start());
				EventStatus status = (row.status() == EventStatus.CANCELLED || (override != null && override.cancelled()))
						? EventStatus.CANCELLED : EventStatus.SCHEDULED;
				boolean modified = override != null && !override.cancelled();
				Instant start = occurrence.start();
				Instant end = occurrence.end();
				LocalDate startDate = row.allDay() ? occurrence.day() : null;
				LocalDate endDate = row.allDay() ? occurrence.day().plusDays(row.endDate().toEpochDay() - row.startDate().toEpochDay())
						: null;
				if (modified) {
					if (override.startAt() != null) {
						start = override.startAt();
						end = override.endAt();
					}
					else if (override.startDate() != null) {
						startDate = override.startDate();
						endDate = override.endDate();
						start = startDate.atStartOfDay(row.zone()).toInstant();
						end = endDate.plusDays(1).atStartOfDay(row.zone()).toInstant();
					}
				}
				if (!(start.isBefore(to) && (end.isAfter(from) || !start.isBefore(from)))) {
					continue;
				}
				out.add(item(row, start, end, startDate, endDate, EventRows.occurrenceId(occurrence.start()), override, modified,
						status, rsvp));
			}
		}
		return out;
	}

	private static CalendarItem item(EventRow row, Instant start, Instant end, LocalDate startDate, LocalDate endDate,
			String occurrenceId, OverrideRow override, boolean modified, EventStatus status, String myRsvp) {
		String title = (modified && override.title() != null) ? override.title() : row.title();
		String location = (modified && override.location() != null) ? override.location() : row.location();
		boolean personal = row.scope() == EventScope.PERSONAL;
		return new CalendarItem((occurrenceId == null) ? row.id().toString() : row.id() + "@" + occurrenceId,
				personal ? "PERSONAL_EVENT" : "EVENT", row.type().name(), title, null, location, row.schoolId(),
				row.scope().name(), row.campusId(), row.gradeLevelId(), row.classId(), row.allDay(), start, end,
				row.allDay() ? startDate : null, row.allDay() ? endDate : null, row.timeZone(), status.name(), row.id(),
				occurrenceId, row.recurring(), modified, row.rsvpEnabled(), myRsvp, row.regardingStudentId(), null);
	}

}
