package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventView;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.CalendarModels.RsvpAnswer;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answering an invitation (YES, NO or MAYBE). Allowed while the event asks for answers ({@code rsvp_enabled}), is not
 * cancelled and has not started; changing the answer is allowed until then, and answering the same again changes nothing
 * (idempotent). On a parent meeting only an invited guardian whose link still holds can answer (the visibility check of
 * the event is the same as for reading it), never the organiser; on other events everybody who can see the event can.
 * The audit entry records the answer and the totals, not who is behind them beyond the actor of the request.
 */
@Service
public class RsvpService {

	private final JdbcClient jdbc;

	private final EventViews views;

	private final AuditLog audit;

	private final Clock clock;

	RsvpService(JdbcClient jdbc, EventViews views, AuditLog audit, Clock clock) {
		this.jdbc = jdbc;
		this.views = views;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public CalendarEventView answer(CurrentUser user, UUID eventId, RsvpAnswer answer) {
		EventRow row = this.views.requireVisible(user, eventId, true);
		if (!row.rsvpEnabled()) {
			throw ApiException.conflict("event.rsvp_disabled");
		}
		Instant now = this.clock.instant();
		if (row.status() == EventStatus.CANCELLED || !now.isBefore(row.startAt())) {
			throw ApiException.conflict("event.rsvp_closed");
		}
		boolean meeting = row.type() == EventType.PARENT_MEETING;
		if (meeting) {
			if (user.userId().equals(row.createdBy())) {
				throw ApiException.forbidden("event.access_denied");
			}
			boolean invited = this.jdbc
				.sql("select exists (select 1 from event_participants where event_id = :e and user_id = :u and role = 'ATTENDEE')")
				.param("e", row.id())
				.param("u", user.userId())
				.query(Boolean.class)
				.single();
			if (!invited) {
				throw ApiException.notFound("event.not_found");
			}
		}
		String previous = this.jdbc.sql("select rsvp_status from event_participants where event_id = :e and user_id = :u")
			.param("e", row.id())
			.param("u", user.userId())
			.query(String.class)
			.optional()
			.orElse(null);
		if (!answer.name().equals(previous)) {
			this.jdbc.sql("""
					insert into event_participants (event_id, user_id, role, rsvp_status, responded_at, invited_at)
					values (:e, :u, 'ATTENDEE', :status, :now, :now)
					on conflict (event_id, user_id) do update set rsvp_status = excluded.rsvp_status,
					  responded_at = excluded.responded_at""")
				.param("e", row.id())
				.param("u", user.userId())
				.param("status", answer.name())
				.param("now", Timestamp.from(now))
				.update();
			int[] totals = totals(row.id());
			AuditEntry entry = AuditEntry.of("event.rsvp", AuditOutcome.SUCCESS)
				.target("event", row.id())
				.school(row.schoolId())
				.metadata("answer", answer.name())
				.metadata("yes", totals[0])
				.metadata("no", totals[1])
				.metadata("maybe", totals[2]);
			this.audit.record(entry);
		}
		return this.views.view(user, row);
	}

	private int[] totals(UUID eventId) {
		return this.jdbc.sql("""
				select count(*) filter (where rsvp_status = 'YES') as yes, count(*) filter (where rsvp_status = 'NO') as no,
				       count(*) filter (where rsvp_status = 'MAYBE') as maybe
				from event_participants where event_id = :e and role = 'ATTENDEE'""")
			.param("e", eventId)
			.query((rs, i) -> new int[] { rs.getInt("yes"), rs.getInt("no"), rs.getInt("maybe") })
			.single();
	}

}
