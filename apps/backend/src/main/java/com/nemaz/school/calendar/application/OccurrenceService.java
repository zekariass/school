package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.CalendarCancelRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventView;
import com.nemaz.school.calendar.application.CalendarModels.CalendarOccurrenceUpdateRequest;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.EventNotifier.Kind;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.calendar.application.EventRows.OverrideRow;
import com.nemaz.school.calendar.application.EventService.Meta;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exceptions of recurring events: cancel or change <em>one</em> occurrence, or put it back. An occurrence is named by its
 * id, the UTC instant it has in the series ({@code 20261201T070000Z}; an all-day occurrence: the start of its first day in
 * the event's zone), which does not change when the occurrence is moved. A cancelled occurrence is the EXDATE of the series; it
 * stays visible as CANCELLED. A change may move the occurrence by at most 7 days and keeps its kind (timed or all-day).
 * Changing the series' own schedule clears its exceptions ({@code EventService.update}).
 */
@Service
public class OccurrenceService {

	static final Duration MAX_SHIFT = Duration.ofDays(7);

	private final JdbcClient jdbc;

	private final EventViews views;

	private final EventService events;

	private final Clock clock;

	OccurrenceService(JdbcClient jdbc, EventViews views, EventService events, Clock clock) {
		this.jdbc = jdbc;
		this.views = views;
		this.events = events;
		this.clock = clock;
	}

	private record Located(EventRow row, Instant original, OverrideRow existing, Recurrence.Occurrence occurrence) {
	}

	private Located locate(CurrentUser user, UUID eventId, String occurrenceId) {
		EventRow row = this.views.requireVisible(user, eventId, true);
		this.events.requireManage(user, row);
		if (!row.recurring()) {
			throw ApiException.conflict("event.not_recurring");
		}
		if (row.status() == EventStatus.CANCELLED) {
			throw ApiException.conflict("event.cancelled");
		}
		Instant original = EventRows.parseOccurrenceId(occurrenceId);
		List<Recurrence.Occurrence> hit = Recurrence.parse(row.recurrenceRule())
			.expand(row.series(), original, original.plusSeconds(1), 1, null);
		if (hit.isEmpty() || !hit.getFirst().start().equals(original)) {
			throw ApiException.notFound("event.occurrence_not_found");
		}
		OverrideRow existing = this.jdbc.sql("select " + EventRows.OVERRIDE_COLUMNS
				+ " from event_overrides o where o.event_id = :id and o.occurrence_start = :at")
			.param("id", row.id())
			.param("at", Timestamp.from(original))
			.query((rs, i) -> EventRows.mapOverride(rs))
			.optional()
			.orElse(null);
		return new Located(row, original, existing, hit.getFirst());
	}

	@Transactional
	public CalendarEventView cancel(CurrentUser user, UUID eventId, String occurrenceId, CalendarCancelRequest request) {
		Located at = locate(user, eventId, occurrenceId);
		if (at.existing() != null && at.existing().cancelled()) {
			return this.views.view(user, at.row());
		}
		String reason = (request != null) ? EventSchedule.description(request.reason()) : null;
		if (reason != null && reason.length() > 500) {
			reason = reason.substring(0, 500);
		}
		Instant shown = (at.existing() != null && at.existing().startAt() != null) ? at.existing().startAt() : at.original();
		upsert(user, at.row().id(), at.original(), "CANCELLED", null, null, null, null, null, null, null, reason);
		this.events.record(user, "event.occurrence_cancel", at.row(), new Meta().with("hasReason", reason != null));
		this.events.notifyAudience(user, at.row(), Kind.CANCELLED, shown, EventRows.occurrenceId(at.original()));
		return this.views.view(user, at.row());
	}

	@Transactional
	public CalendarEventView modify(CurrentUser user, UUID eventId, String occurrenceId, CalendarOccurrenceUpdateRequest req) {
		Located at = locate(user, eventId, occurrenceId);
		EventRow row = at.row();
		OverrideRow old = (at.existing() != null && !at.existing().cancelled()) ? at.existing() : null;
		String title = (req.title() != null && !req.title().isBlank()) ? EventSchedule.title(req.title())
				: (old != null ? old.title() : null);
		String description = (req.description() != null && !req.description().isBlank())
				? EventSchedule.description(req.description()) : (old != null ? old.description() : null);
		String location = (req.location() != null && !req.location().isBlank()) ? EventSchedule.location(req.location())
				: (old != null ? old.location() : null);
		Instant startAt = (old != null) ? old.startAt() : null;
		Instant endAt = (old != null) ? old.endAt() : null;
		LocalDate startDate = (old != null) ? old.startDate() : null;
		LocalDate endDate = (old != null) ? old.endDate() : null;
		boolean timeGiven = req.startAt() != null || req.endAt() != null || req.startDate() != null || req.endDate() != null;
		if (timeGiven) {
			if (row.allDay()) {
				if (req.startAt() != null || req.endAt() != null || req.startDate() == null || req.endDate() == null
						|| req.endDate().isBefore(req.startDate())
						|| req.endDate().toEpochDay() - req.startDate().toEpochDay() >= 366) {
					throw ApiException.badRequest("event.time_invalid");
				}
				startDate = req.startDate();
				endDate = req.endDate();
				startAt = null;
				endAt = null;
				shiftCheck(startDate.atStartOfDay(row.zone()).toInstant(), at.original());
			}
			else {
				if (req.startDate() != null || req.endDate() != null || req.startAt() == null || req.endAt() == null
						|| req.endAt().isBefore(req.startAt())
						|| Duration.between(req.startAt(), req.endAt()).toDays() >= 366) {
					throw ApiException.badRequest("event.time_invalid");
				}
				startAt = req.startAt();
				endAt = req.endAt();
				startDate = null;
				endDate = null;
				shiftCheck(startAt, at.original());
			}
		}
		boolean material = timeGiven || (req.location() != null && !req.location().isBlank());
		if (title == null && description == null && location == null && startAt == null && startDate == null) {
			return this.views.view(user, row);
		}
		upsert(user, row.id(), at.original(), "MODIFIED", title, description, location, startAt, endAt, startDate, endDate,
				null);
		this.events.record(user, "event.occurrence_update", row, new Meta().with("material", material).with("moved", timeGiven));
		if (material) {
			Instant shown = (startAt != null) ? startAt
					: (startDate != null) ? startDate.atStartOfDay(row.zone()).toInstant() : at.original();
			this.events.notifyAudience(user, row, Kind.UPDATED, shown, EventRows.occurrenceId(at.original()));
		}
		return this.views.view(user, row);
	}

	/** Puts an occurrence back as the series has it (removes its exception). Idempotent. */
	@Transactional
	public CalendarEventView restore(CurrentUser user, UUID eventId, String occurrenceId) {
		Located at = locate(user, eventId, occurrenceId);
		if (at.existing() != null) {
			this.jdbc.sql("delete from event_overrides where event_id = :id and occurrence_start = :at")
				.param("id", at.row().id())
				.param("at", Timestamp.from(at.original()))
				.update();
			this.events.record(user, "event.occurrence_restore", at.row(), new Meta());
		}
		return this.views.view(user, at.row());
	}

	private static void shiftCheck(Instant moved, Instant original) {
		if (Duration.between(original, moved).abs().compareTo(MAX_SHIFT) > 0) {
			throw ApiException.badRequest("event.occurrence_shift_too_large");
		}
	}

	private void upsert(CurrentUser user, UUID eventId, Instant original, String status, String title, String description,
			String location, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate, String reason) {
		this.jdbc.sql("""
				insert into event_overrides (event_id, occurrence_start, status, title, description, location, start_at, end_at,
				                             start_date, end_date, reason, updated_by, updated_at)
				values (:event, :at, :status, :title, :description, :location, :start, :end, :startDate, :endDate, :reason, :me, :now)
				on conflict (event_id, occurrence_start) do update set status = excluded.status, title = excluded.title,
				  description = excluded.description, location = excluded.location, start_at = excluded.start_at,
				  end_at = excluded.end_at, start_date = excluded.start_date, end_date = excluded.end_date,
				  reason = excluded.reason, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
			.param("event", eventId)
			.param("at", Timestamp.from(original))
			.param("status", status)
			.param("title", title)
			.param("description", description)
			.param("location", location)
			.param("start", (startAt != null) ? Timestamp.from(startAt) : null)
			.param("end", (endAt != null) ? Timestamp.from(endAt) : null)
			.param("startDate", startDate)
			.param("endDate", endDate)
			.param("reason", reason)
			.param("me", user.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

}
