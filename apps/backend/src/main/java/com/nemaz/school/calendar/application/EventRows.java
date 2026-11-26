package com.nemaz.school.calendar.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.EventScope;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;

/** The {@code events} and {@code event_overrides} rows as the services use them, and the occurrence id codec. */
final class EventRows {

	static final String COLUMNS = """
			e.id, e.scope_type, e.school_id, e.campus_id, e.grade_level_id, e.class_id, e.owner_id, e.event_type, e.title,
			e.description, e.location, e.start_at, e.end_at, e.timezone, e.all_day, e.start_date, e.end_date,
			e.recurrence_rule, e.recurrence_ends_at, e.rsvp_enabled, e.regarding_student_id, e.status, e.cancelled_at,
			e.cancelled_by, e.cancellation_reason, e.created_by, e.created_at, e.updated_at""";

	private static final DateTimeFormatter OCCURRENCE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

	private EventRows() {
	}

	record EventRow(UUID id, EventScope scope, UUID schoolId, UUID campusId, UUID gradeLevelId, UUID classId, UUID ownerId,
			EventType type, String title, String description, String location, Instant startAt, Instant endAt,
			String timeZone, boolean allDay, LocalDate startDate, LocalDate endDate, String recurrenceRule,
			Instant recurrenceEndsAt, boolean rsvpEnabled, UUID regardingStudentId, EventStatus status, Instant cancelledAt,
			UUID cancelledBy, String cancellationReason, UUID createdBy, Instant createdAt, Instant updatedAt) {

		boolean recurring() {
			return this.recurrenceRule != null;
		}

		ZoneId zone() {
			try {
				return ZoneId.of(this.timeZone);
			}
			catch (DateTimeException ex) {
				return ZoneOffset.UTC;
			}
		}

		/** The same event with another title (the title of one modified occurrence). */
		EventRow withTitle(String newTitle) {
			return new EventRow(this.id, this.scope, this.schoolId, this.campusId, this.gradeLevelId, this.classId, this.ownerId,
					this.type, newTitle, this.description, this.location, this.startAt, this.endAt, this.timeZone, this.allDay,
					this.startDate, this.endDate, this.recurrenceRule, this.recurrenceEndsAt, this.rsvpEnabled,
					this.regardingStudentId, this.status, this.cancelledAt, this.cancelledBy, this.cancellationReason,
					this.createdBy, this.createdAt, this.updatedAt);
		}

		Recurrence.Series series() {
			return this.allDay ? Recurrence.Series.allDay(zone(), this.startDate, this.endDate)
					: Recurrence.Series.timed(zone(), this.startAt, this.endAt);
		}

	}

	static EventRow map(ResultSet rs) throws SQLException {
		return new EventRow(Rows.uuid(rs, "id"), EventScope.valueOf(rs.getString("scope_type")), Rows.uuid(rs, "school_id"),
				Rows.uuid(rs, "campus_id"), Rows.uuid(rs, "grade_level_id"), Rows.uuid(rs, "class_id"), Rows.uuid(rs, "owner_id"),
				EventType.valueOf(rs.getString("event_type")), rs.getString("title"), rs.getString("description"),
				rs.getString("location"), Rows.instant(rs, "start_at"), Rows.instant(rs, "end_at"), rs.getString("timezone"),
				rs.getBoolean("all_day"), Rows.date(rs, "start_date"), Rows.date(rs, "end_date"), rs.getString("recurrence_rule"),
				Rows.instant(rs, "recurrence_ends_at"), rs.getBoolean("rsvp_enabled"), Rows.uuid(rs, "regarding_student_id"),
				EventStatus.valueOf(rs.getString("status")), Rows.instant(rs, "cancelled_at"), Rows.uuid(rs, "cancelled_by"),
				rs.getString("cancellation_reason"), Rows.uuid(rs, "created_by"), Rows.instant(rs, "created_at"),
				Rows.instant(rs, "updated_at"));
	}

	/** An exception of a recurring event: CANCELLED, or MODIFIED with the fields that differ (null = as in the series). */
	record OverrideRow(UUID eventId, Instant occurrenceStart, String status, String title, String description,
			String location, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate, String reason) {

		boolean cancelled() {
			return "CANCELLED".equals(this.status);
		}

		String occurrenceId() {
			return EventRows.occurrenceId(this.occurrenceStart);
		}

	}

	static final String OVERRIDE_COLUMNS = """
			o.event_id, o.occurrence_start, o.status, o.title, o.description, o.location, o.start_at, o.end_at, o.start_date,
			o.end_date, o.reason""";

	static OverrideRow mapOverride(ResultSet rs) throws SQLException {
		return new OverrideRow(Rows.uuid(rs, "event_id"), Rows.instant(rs, "occurrence_start"), rs.getString("status"),
				rs.getString("title"), rs.getString("description"), rs.getString("location"), Rows.instant(rs, "start_at"),
				Rows.instant(rs, "end_at"), Rows.date(rs, "start_date"), Rows.date(rs, "end_date"), rs.getString("reason"));
	}

	// --- occurrence ids -----------------------------------------------------------------------

	/** The opaque id of an occurrence: the UTC instant it has in the series, {@code 20261201T070000Z}. */
	static String occurrenceId(Instant occurrenceStart) {
		return OCCURRENCE.format(occurrenceStart.atOffset(ZoneOffset.UTC));
	}

	/** The instant behind an occurrence id; 400 {@code event.occurrence_invalid} for anything else. */
	static Instant parseOccurrenceId(String id) {
		try {
			return LocalDateTime.parse(id, OCCURRENCE).toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.badRequest("event.occurrence_invalid");
		}
	}

}
