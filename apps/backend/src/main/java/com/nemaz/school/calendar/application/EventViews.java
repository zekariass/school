package com.nemaz.school.calendar.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.CalendarEventCapabilities;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventView;
import com.nemaz.school.calendar.application.CalendarModels.CalendarOccurrenceOverrideView;
import com.nemaz.school.calendar.application.CalendarModels.CalendarRsvpCounts;
import com.nemaz.school.calendar.application.CalendarModels.CalendarRsvpParticipant;
import com.nemaz.school.calendar.application.CalendarModels.CalendarRsvpView;
import com.nemaz.school.calendar.application.CalendarModels.EventScope;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.calendar.application.EventRows.OverrideRow;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads an event the caller may see and renders it. The visibility check is the same SQL predicate as the union's
 * ({@link CalendarAccess#visible}), so "can I open it" and "does it appear in my calendar" never disagree. Who answered what is
 * shown to the organiser of a parent meeting only; ordinary events show totals to their managers; everybody else sees their own
 * answer.
 */
@Component
@Transactional(readOnly = true)
class EventViews {

	private final JdbcClient jdbc;

	private final CalendarAccess access;

	private final EventAudiences audiences;

	private final Clock clock;

	EventViews(JdbcClient jdbc, CalendarAccess access, EventAudiences audiences, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.audiences = audiences;
		this.clock = clock;
	}

	/** The event row regardless of who asks (callers authorize); empty for unknown and deleted events. */
	java.util.Optional<EventRow> find(UUID eventId, boolean forUpdate) {
		return this.jdbc.sql("select " + EventRows.COLUMNS + " from events e where e.id = :id and e.deleted_at is null"
				+ (forUpdate ? " for update" : ""))
			.param("id", eventId)
			.query((rs, i) -> EventRows.map(rs))
			.optional();
	}

	/**
	 * The event if the caller may see it: 404 {@code event.not_found} for unknown, deleted and invisible events alike; 404
	 * {@code school.not_found}/403 {@code calendar.feature_disabled} come from the school resolution.
	 */
	EventRow requireVisible(CurrentUser user, UUID eventId, boolean forUpdate) {
		EventRow row = find(eventId, forUpdate).orElseThrow(() -> ApiException.notFound("event.not_found"));
		if (!visible(user, row)) {
			throw ApiException.notFound("event.not_found");
		}
		return row;
	}

	boolean visible(CurrentUser user, EventRow row) {
		CalendarAccess.Target target;
		if (row.schoolId() == null) {
			return user.userId().equals(row.ownerId());
		}
		try {
			target = this.access.target(user, row.schoolId(), null);
		}
		catch (ApiException ex) {
			if (ex.status().value() == 404) {
				return false;
			}
			throw ex;
		}
		CalendarAccess.Pred pred = this.access.visible(user, target, false, true);
		return this.jdbc.sql("select exists (select 1 from events e where e.id = :cv_event and " + pred.sql() + ")")
			.params(pred.params())
			.param("cv_event", row.id())
			.query(Boolean.class)
			.single();
	}

	CalendarEventView view(CurrentUser user, EventRow row) {
		boolean manage = this.access.mayManage(user, row);
		boolean cancelled = row.status() == EventStatus.CANCELLED;
		CalendarEventCapabilities capabilities = new CalendarEventCapabilities(manage && !cancelled, manage && !cancelled,
				manage && (cancelled || row.scope() == EventScope.PERSONAL));
		String createdByName = this.jdbc.sql("select display_name from users where id = :id")
			.param("id", row.createdBy())
			.query(String.class)
			.optional()
			.orElse(null);
		return new CalendarEventView(row.id(), row.schoolId(), row.scope(), row.campusId(), row.gradeLevelId(), row.classId(),
				row.type(), row.title(), row.description(), row.location(), row.timeZone(), row.allDay(), row.startAt(),
				row.endAt(), row.startDate(), row.endDate(), row.recurrenceRule(), row.recurring(), row.recurrenceEndsAt(),
				row.rsvpEnabled(), row.status(), row.cancelledAt(), row.cancellationReason(), row.createdBy(), createdByName,
				row.regardingStudentId(), rsvp(user, row, manage), overrides(row), capabilities, row.createdAt(),
				row.updatedAt());
	}

	private CalendarRsvpView rsvp(CurrentUser user, EventRow row, boolean manage) {
		if (!row.rsvpEnabled()) {
			return new CalendarRsvpView(false, null, false, null, null);
		}
		boolean meeting = row.type() == EventType.PARENT_MEETING;
		String mine = this.jdbc.sql("select rsvp_status from event_participants where event_id = :e and user_id = :u")
			.param("e", row.id())
			.param("u", user.userId())
			.query(String.class)
			.optional()
			.orElse(null);
		boolean organiser = user.userId().equals(row.createdBy());
		boolean open = row.status() == EventStatus.SCHEDULED && this.clock.instant().isBefore(row.startAt());
		// a meeting's guardian must be an invited participant; ordinary events ask everybody who can see them
		boolean canAnswer = open && (meeting ? (!organiser && mine != null) : true);
		CalendarRsvpCounts counts = null;
		List<CalendarRsvpParticipant> participants = null;
		if (meeting && organiser) {
			participants = new ArrayList<>();
			for (UUID id : this.audiences.participants(row)) {
				if (id.equals(row.createdBy())) {
					continue;
				}
				participants.add(this.jdbc.sql("""
						select p.user_id, u.display_name, p.rsvp_status, p.responded_at from event_participants p
						join users u on u.id = p.user_id where p.event_id = :e and p.user_id = :u""")
					.param("e", row.id())
					.param("u", id)
					.query((rs, i) -> new CalendarRsvpParticipant(Rows.uuid(rs, "user_id"), rs.getString("display_name"),
							rs.getString("rsvp_status"), Rows.instant(rs, "responded_at")))
					.single());
			}
			counts = countOf(participants);
		}
		else if (!meeting && manage) {
			counts = this.jdbc.sql("""
					select count(*) filter (where rsvp_status = 'YES') as yes, count(*) filter (where rsvp_status = 'NO') as no,
					       count(*) filter (where rsvp_status = 'MAYBE') as maybe,
					       count(*) filter (where rsvp_status = 'PENDING') as pending
					from event_participants where event_id = :e and role = 'ATTENDEE'""")
				.param("e", row.id())
				.query((rs, i) -> new CalendarRsvpCounts(rs.getInt("yes"), rs.getInt("no"), rs.getInt("maybe"),
						rs.getInt("pending")))
				.single();
		}
		return new CalendarRsvpView(true, mine, canAnswer, counts, participants);
	}

	private static CalendarRsvpCounts countOf(List<CalendarRsvpParticipant> participants) {
		int yes = 0;
		int no = 0;
		int maybe = 0;
		int pending = 0;
		for (CalendarRsvpParticipant p : participants) {
			switch (String.valueOf(p.status())) {
				case "YES" -> yes++;
				case "NO" -> no++;
				case "MAYBE" -> maybe++;
				default -> pending++;
			}
		}
		return new CalendarRsvpCounts(yes, no, maybe, pending);
	}

	private List<CalendarOccurrenceOverrideView> overrides(EventRow row) {
		if (!row.recurring()) {
			return List.of();
		}
		return this.jdbc.sql("select " + EventRows.OVERRIDE_COLUMNS
				+ " from event_overrides o where o.event_id = :id order by o.occurrence_start")
			.param("id", row.id())
			.query((rs, i) -> EventRows.mapOverride(rs))
			.list()
			.stream()
			.map(EventViews::toView)
			.toList();
	}

	static CalendarOccurrenceOverrideView toView(OverrideRow o) {
		Instant start = o.occurrenceStart();
		return new CalendarOccurrenceOverrideView(EventRows.occurrenceId(start), start, o.status(), o.title(), o.description(),
				o.location(), o.startAt(), o.endAt(), o.startDate(), o.endDate(), o.reason());
	}

}
