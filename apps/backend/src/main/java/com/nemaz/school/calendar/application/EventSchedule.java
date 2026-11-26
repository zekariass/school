package com.nemaz.school.calendar.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.text.PlainText;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The "when" of an event, validated and in the two representations the database keeps. A <b>timed</b> event has instants
 * ({@code startAt}, {@code endAt}, end not before start) and a zone to display them in; an <b>all-day</b> event is calendar
 * dates in its zone ({@code startDate} to {@code endDate}, inclusive; a one-day event has both equal), stored with the
 * instants of the start of the first day and of the day after the last one (end exclusive), which depend on the zone
 * (23, 24 or 25 hours per day around a daylight-saving change). Neither may be longer than a year; years outside
 * 1970 to 2200 are refused.
 */
@Component
class EventSchedule {

	static final int TITLE_MAX = 200;

	static final int DESCRIPTION_MAX = 5000;

	static final int LOCATION_MAX = 200;

	private static final int MAX_DAYS = 366;

	private final JdbcClient jdbc;

	EventSchedule(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A validated schedule: what to store, and the series (null when the event does not repeat). */
	record Schedule(boolean allDay, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate, ZoneId zone,
			String rule, Instant recurrenceEndsAt) {

		boolean sameTimeAs(Schedule other) {
			return this.allDay == other.allDay && this.startAt.equals(other.startAt) && this.endAt.equals(other.endAt)
					&& this.zone.getId().equals(other.zone.getId());
		}

	}

	static Schedule of(boolean allDay, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate, ZoneId zone,
			String ruleText) {
		Instant start;
		Instant end;
		LocalDate firstDay;
		LocalTime time = null;
		if (allDay) {
			if (startDate == null || endDate == null || startAt != null || endAt != null || endDate.isBefore(startDate)
					|| endDate.toEpochDay() - startDate.toEpochDay() >= MAX_DAYS || outOfRange(startDate)
					|| outOfRange(endDate)) {
				throw ApiException.badRequest("event.time_invalid");
			}
			start = startDate.atStartOfDay(zone).toInstant();
			end = endDate.plusDays(1).atStartOfDay(zone).toInstant();
			firstDay = startDate;
		}
		else {
			if (startAt == null || endAt == null || startDate != null || endDate != null || endAt.isBefore(startAt)
					|| Duration.between(startAt, endAt).toDays() >= MAX_DAYS || outOfRange(startAt.atZone(zone).toLocalDate())
					|| outOfRange(endAt.atZone(zone).toLocalDate())) {
				throw ApiException.badRequest("event.time_invalid");
			}
			start = startAt;
			end = endAt;
			firstDay = start.atZone(zone).toLocalDate();
			time = start.atZone(zone).toLocalTime();
			startDate = null;
			endDate = null;
		}
		String rule = null;
		Instant ends = null;
		if (ruleText != null && !ruleText.isBlank()) {
			Recurrence recurrence = Recurrence.parse(ruleText);
			recurrence.validateFor(firstDay, zone, time);
			rule = recurrence.text();
			Recurrence.Series series = allDay ? Recurrence.Series.allDay(zone, startDate, endDate)
					: Recurrence.Series.timed(zone, start, end);
			Recurrence.Occurrence last = recurrence.last(series);
			ends = (last != null) ? last.end() : null;
		}
		return new Schedule(allDay, start, end, startDate, endDate, zone, rule, ends);
	}

	private static boolean outOfRange(LocalDate date) {
		return date.getYear() < 1970 || date.getYear() > 2200;
	}

	// --- zones --------------------------------------------------------------------------------

	/** An IANA zone from a client; 400 {@code event.time_zone_invalid} for anything else (offsets such as +03:00 included). */
	static ZoneId zone(String name) {
		try {
			if (name == null || name.isBlank() || !ZoneId.getAvailableZoneIds().contains(name.strip())) {
				throw ApiException.badRequest("event.time_zone_invalid");
			}
			return ZoneId.of(name.strip());
		}
		catch (DateTimeException ex) {
			throw ApiException.badRequest("event.time_zone_invalid");
		}
	}

	/** The zone of a campus (its own, else the school's); the school's when {@code campusId} is null. */
	ZoneId zoneOfSchool(UUID schoolId, UUID campusId) {
		String name = this.jdbc.sql("""
				select coalesce(c.timezone, s.timezone) from schools s left join campuses c on c.id = :campus and c.school_id = s.id
				where s.id = :school""")
			.param("school", schoolId)
			.param("campus", campusId)
			.query(String.class)
			.optional()
			.orElse("UTC");
		return safe(name);
	}

	/** The zone of a class: its campus's, else the school's. */
	ZoneId zoneOfClass(UUID schoolId, UUID classId) {
		UUID campus = this.jdbc.sql("select campus_id from classes where id = :id")
			.param("id", classId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		return zoneOfSchool(schoolId, campus);
	}

	/** A user's own zone (profile), UTC when unset. */
	ZoneId zoneOfUser(UUID userId) {
		String name = this.jdbc.sql("select timezone from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse(null);
		return safe(name);
	}

	private static ZoneId safe(String name) {
		try {
			return (name == null) ? ZoneId.of("UTC") : ZoneId.of(name);
		}
		catch (DateTimeException ex) {
			return ZoneId.of("UTC");
		}
	}

	// --- text ---------------------------------------------------------------------------------

	static String title(String raw) {
		String text = PlainText.clean(raw);
		if (text.isEmpty()) {
			throw ApiException.badRequest("event.title_required");
		}
		if (PlainText.length(text) > TITLE_MAX) {
			throw ApiException.badRequest("event.title_too_long");
		}
		return text;
	}

	static String description(String raw) {
		return optional(raw, DESCRIPTION_MAX, "event.description_too_long");
	}

	static String location(String raw) {
		return optional(raw, LOCATION_MAX, "event.location_too_long");
	}

	private static String optional(String raw, int max, String tooLong) {
		if (raw == null) {
			return null;
		}
		String text = PlainText.clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		if (PlainText.length(text) > max) {
			throw ApiException.badRequest(tooLong);
		}
		return text;
	}

}
