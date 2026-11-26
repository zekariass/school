package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.calendar.application.CalendarModels.CalendarCapabilities;
import com.nemaz.school.calendar.application.CalendarModels.CalendarClassRef;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventUpdateRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventView;
import com.nemaz.school.calendar.application.CalendarModels.EventScope;
import com.nemaz.school.calendar.application.CalendarModels.EventStatus;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.EventNotifier.Kind;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.calendar.application.EventSchedule.Schedule;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle of a native event: create, change, cancel, delete. Each change is audited in its transaction with ids,
 * scope, type and counts only (never the title, text or place: a parent meeting's title may name a child).
 *
 * <p>
 * <b>Who may create</b> (403 {@code event.create_denied} otherwise): SCHOOL, CAMPUS and GRADE_LEVEL events need
 * {@code school.calendar.manage} at the school; CLASS events a manager of the school's calendar, a manager of the class
 * ({@code class.manage}) or an ACTIVE LEAD or CO_TEACHER of it (setting {@code calendar.teachers_can_create_class_events});
 * a PARENT_MEETING an ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER of a class the student is ACTIVE in; PERSONAL events any
 * signed-in user (they belong to no school). Who may change, cancel and delete follows {@link CalendarAccess#mayManage}.
 *
 * <p>
 * <b>Cancel, never delete silently:</b> cancelling keeps the event (shown as cancelled) and tells the audience; only a
 * cancelled event, or a personal one, can be deleted (soft).
 */
@Service
public class EventService {

	private final JdbcClient jdbc;

	private final CalendarAccess access;

	private final EventViews views;

	private final EventSchedule schedules;

	private final EventAudiences audiences;

	private final EventNotifier notifier;

	private final CalendarSettings settings;

	private final ClassAccess classes;

	private final NotificationService notifications;

	private final AuditLog audit;

	private final Clock clock;

	EventService(JdbcClient jdbc, CalendarAccess access, EventViews views, EventSchedule schedules, EventAudiences audiences,
			EventNotifier notifier, CalendarSettings settings, ClassAccess classes, NotificationService notifications,
			AuditLog audit, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.views = views;
		this.schedules = schedules;
		this.audiences = audiences;
		this.notifier = notifier;
		this.settings = settings;
		this.classes = classes;
		this.notifications = notifications;
		this.audit = audit;
		this.clock = clock;
	}

	// --- create -------------------------------------------------------------------------------

	@Transactional
	public CalendarEventView createInSchool(CurrentUser user, UUID schoolId, CalendarEventRequest req) {
		this.access.requireSchool(user, schoolId);
		if (req.eventType() == EventType.PARENT_MEETING) {
			return createMeeting(user, schoolId, req);
		}
		EventScope scope = req.scope();
		if (scope == null || scope == EventScope.PERSONAL || scope == EventScope.INVITEES) {
			throw ApiException.badRequest("event.scope_invalid");
		}
		if (req.regardingStudentId() != null) {
			throw ApiException.badRequest("event.scope_invalid");
		}
		UUID campusId = null;
		UUID gradeId = null;
		UUID classId = null;
		ZoneId defaultZone;
		switch (scope) {
			case SCHOOL -> {
				requireOnly(req, false, false, false);
				requireManager(user, schoolId);
				defaultZone = this.schedules.zoneOfSchool(schoolId, null);
			}
			case CAMPUS -> {
				requireOnly(req, true, false, false);
				this.access.requireOwned("campuses", req.campusId(), schoolId);
				requireManager(user, schoolId);
				campusId = req.campusId();
				defaultZone = this.schedules.zoneOfSchool(schoolId, campusId);
			}
			case GRADE_LEVEL -> {
				requireOnly(req, false, true, false);
				this.access.requireOwned("grade_levels", req.gradeLevelId(), schoolId);
				requireManager(user, schoolId);
				gradeId = req.gradeLevelId();
				defaultZone = this.schedules.zoneOfSchool(schoolId, null);
			}
			case CLASS -> {
				requireOnly(req, false, false, true);
				ClassRef ref = (req.classId() == null) ? null
						: this.classes.find(req.classId()).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
				boolean manager = this.access.manager(user, schoolId);
				if (ref == null || (!manager && !this.classes.canView(user, ref.id()))) {
					throw ApiException.notFound("class.not_found");
				}
				if (ref.archived()) {
					throw ApiException.conflict("class.archived");
				}
				if (!this.access.mayCreateClassEvent(user, schoolId, ref.id())) {
					throw ApiException.forbidden("event.create_denied");
				}
				classId = ref.id();
				defaultZone = this.schedules.zoneOfSchool(schoolId, ref.campusId());
			}
			default -> throw ApiException.badRequest("event.scope_invalid");
		}
		EventType type = ordinaryType(scope, req.eventType());
		return insert(user, schoolId, scope, campusId, gradeId, classId, null, type, req, defaultZone, null);
	}

	@Transactional
	public CalendarEventView createPersonal(CurrentUser user, CalendarEventRequest req) {
		if ((req.scope() != null && req.scope() != EventScope.PERSONAL) || req.campusId() != null || req.gradeLevelId() != null
				|| req.classId() != null || req.regardingStudentId() != null) {
			throw ApiException.badRequest("event.scope_invalid");
		}
		if (req.eventType() != null && req.eventType() != EventType.OTHER) {
			throw ApiException.badRequest("event.type_invalid");
		}
		return insert(user, null, EventScope.PERSONAL, null, null, null, user.userId(), EventType.OTHER, req,
				this.schedules.zoneOfUser(user.userId()), null);
	}

	private CalendarEventView createMeeting(CurrentUser user, UUID schoolId, CalendarEventRequest req) {
		if ((req.scope() != null && req.scope() != EventScope.INVITEES) || req.campusId() != null || req.gradeLevelId() != null
				|| req.classId() != null) {
			throw ApiException.badRequest("event.scope_invalid");
		}
		if (req.recurrenceRule() != null && !req.recurrenceRule().isBlank()) {
			throw ApiException.badRequest("event.recurrence_not_allowed");
		}
		if (Boolean.TRUE.equals(req.allDay())) {
			throw ApiException.badRequest("event.time_invalid");
		}
		UUID student = req.regardingStudentId();
		this.access.requireActiveStudent(schoolId, student);
		if (!this.access.teachesStudent(user.userId(), schoolId, student)) {
			throw ApiException.forbidden("event.meeting_not_allowed");
		}
		List<UUID> guardians = this.access.invitableGuardians(schoolId, student);
		if (guardians.isEmpty()) {
			throw ApiException.unprocessable("event.no_participants");
		}
		return insert(user, schoolId, EventScope.INVITEES, null, null, null, null, EventType.PARENT_MEETING, req,
				this.schedules.zoneOfSchool(schoolId, null), guardians);
	}

	private static void requireOnly(CalendarEventRequest req, boolean campus, boolean grade, boolean cls) {
		if ((req.campusId() != null) != campus || (req.gradeLevelId() != null) != grade || (req.classId() != null) != cls) {
			throw ApiException.badRequest("event.scope_invalid");
		}
	}

	private void requireManager(CurrentUser user, UUID schoolId) {
		if (!this.access.manager(user, schoolId)) {
			throw ApiException.forbidden("event.create_denied");
		}
	}

	private static EventType ordinaryType(EventScope scope, EventType requested) {
		if (requested == null) {
			return (scope == EventScope.CLASS) ? EventType.CLASS_EVENT : EventType.SCHOOL_EVENT;
		}
		boolean valid = (scope == EventScope.CLASS)
				? (requested == EventType.CLASS_EVENT || requested == EventType.EXAM || requested == EventType.OTHER)
				: CalendarAccess.schoolWideType(requested);
		if (!valid) {
			throw ApiException.badRequest("event.type_invalid");
		}
		return requested;
	}

	private CalendarEventView insert(CurrentUser user, UUID schoolId, EventScope scope, UUID campusId, UUID gradeId, UUID classId,
			UUID ownerId, EventType type, CalendarEventRequest req, ZoneId defaultZone, List<UUID> guardians) {
		String title = EventSchedule.title(req.title());
		String description = EventSchedule.description(req.description());
		String location = EventSchedule.location(req.location());
		ZoneId zone = (req.timeZone() != null && !req.timeZone().isBlank()) ? EventSchedule.zone(req.timeZone()) : defaultZone;
		Schedule when = EventSchedule.of(Boolean.TRUE.equals(req.allDay()), req.startAt(), req.endAt(), req.startDate(),
				req.endDate(), zone, req.recurrenceRule());
		boolean meeting = type == EventType.PARENT_MEETING;
		boolean rsvp = meeting || Boolean.TRUE.equals(req.rsvpEnabled());
		if (rsvp && !meeting && (when.rule() != null || scope == EventScope.PERSONAL)) {
			throw ApiException.badRequest("event.rsvp_unsupported");
		}
		Instant now = this.clock.instant();
		if (meeting && !when.startAt().isAfter(now)) {
			throw ApiException.badRequest("event.time_in_past");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into events (id, scope_type, school_id, campus_id, grade_level_id, class_id, owner_id, event_type, title,
				                    description, location, start_at, end_at, timezone, all_day, start_date, end_date,
				                    recurrence_rule, recurrence_ends_at, rsvp_enabled, regarding_student_id, created_by,
				                    created_at, updated_at)
				values (:id, :scope, :school, :campus, :grade, :class, :owner, :type, :title, :description, :location, :start,
				        :end, :zone, :allDay, :startDate, :endDate, :rule, :ends, :rsvp, :student, :me, :now, :now)""")
			.param("id", id)
			.param("scope", scope.name())
			.param("school", schoolId)
			.param("campus", campusId)
			.param("grade", gradeId)
			.param("class", classId)
			.param("owner", ownerId)
			.param("type", type.name())
			.param("title", title)
			.param("description", description)
			.param("location", location)
			.param("start", Timestamp.from(when.startAt()))
			.param("end", Timestamp.from(when.endAt()))
			.param("zone", when.zone().getId())
			.param("allDay", when.allDay())
			.param("startDate", when.startDate())
			.param("endDate", when.endDate())
			.param("rule", when.rule())
			.param("ends", (when.recurrenceEndsAt() != null) ? Timestamp.from(when.recurrenceEndsAt()) : null)
			.param("rsvp", rsvp)
			.param("student", meeting ? req.regardingStudentId() : null)
			.param("me", user.userId())
			.param("now", Timestamp.from(now))
			.update();
		if (meeting) {
			insertParticipant(id, user.userId(), "ORGANIZER", null, now);
			for (UUID guardian : guardians) {
				insertParticipant(id, guardian, "ATTENDEE", "PENDING", now);
			}
		}
		EventRow row = this.views.find(id, false).orElseThrow();
		record(user, "event.create", row, new Meta().with("recurring", row.recurring())
			.with("allDay", row.allDay())
			.with("rsvp", row.rsvpEnabled())
			.with("participants", meeting ? guardians.size() : null));
		if (meeting) {
			this.notifier.send(Kind.INVITATION, row, row.startAt(), user.userId(), guardians, null);
		}
		else if (scope != EventScope.PERSONAL && this.settings.notifyNewEvents(schoolId)) {
			this.notifier.send(Kind.INVITATION, row, row.startAt(), user.userId(), this.audiences.audience(row), null);
		}
		return this.views.view(user, row);
	}

	private void insertParticipant(UUID eventId, UUID userId, String role, String status, Instant now) {
		this.jdbc.sql("""
				insert into event_participants (event_id, user_id, role, rsvp_status, invited_at)
				values (:event, :user, :role, :status, :now) on conflict do nothing""")
			.param("event", eventId)
			.param("user", userId)
			.param("role", role)
			.param("status", status)
			.param("now", Timestamp.from(now))
			.update();
	}

	// --- read ---------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public CalendarEventView get(CurrentUser user, UUID eventId) {
		return this.views.view(user, this.views.requireVisible(user, eventId, false));
	}

	// --- change -------------------------------------------------------------------------------

	@Transactional
	public CalendarEventView update(CurrentUser user, UUID eventId, CalendarEventUpdateRequest req) {
		EventRow row = this.views.requireVisible(user, eventId, true);
		requireManage(user, row);
		if (row.status() == EventStatus.CANCELLED) {
			throw ApiException.conflict("event.cancelled");
		}
		boolean meeting = row.type() == EventType.PARENT_MEETING;
		String title = (req.title() != null) ? EventSchedule.title(req.title()) : row.title();
		String description = (req.description() != null) ? EventSchedule.description(req.description()) : row.description();
		String location = (req.location() != null) ? EventSchedule.location(req.location()) : row.location();
		boolean timeInput = req.startAt() != null || req.endAt() != null || req.startDate() != null || req.endDate() != null
				|| req.allDay() != null || req.timeZone() != null || req.recurrenceRule() != null;
		Schedule current = new Schedule(row.allDay(), row.startAt(), row.endAt(), row.startDate(), row.endDate(), row.zone(),
				row.recurrenceRule(), row.recurrenceEndsAt());
		Schedule next = current;
		if (timeInput) {
			boolean allDay = (req.allDay() != null) ? req.allDay() : row.allDay();
			ZoneId zone = (req.timeZone() != null) ? EventSchedule.zone(req.timeZone()) : row.zone();
			String rule = (req.recurrenceRule() != null) ? req.recurrenceRule() : row.recurrenceRule();
			if (meeting && ((req.recurrenceRule() != null && !req.recurrenceRule().isBlank()) || allDay)) {
				throw ApiException.badRequest((allDay) ? "event.time_invalid" : "event.recurrence_not_allowed");
			}
			boolean moved = req.startAt() != null || req.endAt() != null || req.startDate() != null || req.endDate() != null
					|| (req.allDay() != null && req.allDay() != row.allDay());
			Instant startAt = null;
			Instant endAt = null;
			java.time.LocalDate startDate = null;
			java.time.LocalDate endDate = null;
			if (moved) {
				if (allDay) {
					startDate = req.startDate();
					endDate = req.endDate();
					if (startDate == null && endDate != null && row.allDay()) {
						startDate = row.startDate();
					}
					if (endDate == null && startDate != null && row.allDay()) {
						endDate = row.endDate();
					}
				}
				else {
					startAt = req.startAt();
					endAt = req.endAt();
					if (startAt == null && endAt != null && !row.allDay()) {
						startAt = row.startAt();
					}
					if (endAt == null && startAt != null && !row.allDay()) {
						endAt = row.endAt();
					}
				}
			}
			else if (allDay) {
				startDate = row.startDate();
				endDate = row.endDate();
			}
			else {
				startAt = row.startAt();
				endAt = row.endAt();
			}
			next = EventSchedule.of(allDay, startAt, endAt, startDate, endDate, zone, rule);
		}
		boolean scheduleChanged = !current.sameTimeAs(next) || !Objects.equals(current.rule(), next.rule());
		boolean rsvp = row.rsvpEnabled();
		if (req.rsvpEnabled() != null && !meeting) {
			rsvp = req.rsvpEnabled();
		}
		if (rsvp && !meeting && (next.rule() != null || row.scope() == EventScope.PERSONAL)) {
			throw ApiException.badRequest("event.rsvp_unsupported");
		}
		if (meeting && scheduleChanged && !next.startAt().isAfter(this.clock.instant())) {
			throw ApiException.badRequest("event.time_in_past");
		}
		boolean placeChanged = !Objects.equals(location, row.location());
		boolean material = scheduleChanged || placeChanged;
		List<String> changed = new ArrayList<>();
		if (!title.equals(row.title())) {
			changed.add("title");
		}
		if (!Objects.equals(description, row.description())) {
			changed.add("description");
		}
		if (placeChanged) {
			changed.add("location");
		}
		if (scheduleChanged) {
			changed.add("schedule");
		}
		if (rsvp != row.rsvpEnabled()) {
			changed.add("rsvp");
		}
		if (changed.isEmpty()) {
			return this.views.view(user, row);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update events set title = :title, description = :description, location = :location, start_at = :start,
				       end_at = :end, timezone = :zone, all_day = :allDay, start_date = :startDate, end_date = :endDate,
				       recurrence_rule = :rule, recurrence_ends_at = :ends, rsvp_enabled = :rsvp, updated_at = :now,
				       version = version + 1
				where id = :id""")
			.param("title", title)
			.param("description", description)
			.param("location", location)
			.param("start", Timestamp.from(next.startAt()))
			.param("end", Timestamp.from(next.endAt()))
			.param("zone", next.zone().getId())
			.param("allDay", next.allDay())
			.param("startDate", next.startDate())
			.param("endDate", next.endDate())
			.param("rule", next.rule())
			.param("ends", (next.recurrenceEndsAt() != null) ? Timestamp.from(next.recurrenceEndsAt()) : null)
			.param("rsvp", rsvp)
			.param("now", Timestamp.from(now))
			.param("id", row.id())
			.update();
		int overridesCleared = 0;
		if (scheduleChanged) {
			// the exceptions are keyed by the occurrences of the old schedule: they would point at nothing
			overridesCleared = this.jdbc.sql("delete from event_overrides where event_id = :id").param("id", row.id()).update();
			// an answer was given for the old time: ask again
			this.jdbc.sql("""
					update event_participants set rsvp_status = 'PENDING', responded_at = null
					where event_id = :id and role = 'ATTENDEE' and rsvp_status is not null""").param("id", row.id()).update();
		}
		if (!rsvp && row.rsvpEnabled()) {
			this.jdbc.sql("delete from event_participants where event_id = :id and role = 'ATTENDEE'").param("id", row.id()).update();
		}
		EventRow updated = this.views.find(row.id(), false).orElseThrow();
		record(user, "event.update", updated, new Meta().with("fields", changed)
			.with("material", material)
			.with("overridesCleared", overridesCleared));
		if (material) {
			notifyAudience(user, updated, Kind.UPDATED, updated.startAt(), null);
		}
		return this.views.view(user, updated);
	}

	// --- cancel and delete --------------------------------------------------------------------

	/** Cancels the event (every occurrence of a series); idempotent. The audience is told. */
	@Transactional
	public CalendarEventView cancel(CurrentUser user, UUID eventId, String reason) {
		EventRow row = this.views.requireVisible(user, eventId, true);
		requireManage(user, row);
		if (row.status() == EventStatus.CANCELLED) {
			return this.views.view(user, row);
		}
		String note = EventSchedule.description(reason);
		if (note != null && note.length() > 500) {
			note = note.substring(0, 500);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update events set status = 'CANCELLED', cancelled_at = :now, cancelled_by = :me, cancellation_reason = :reason,
				       updated_at = :now, version = version + 1 where id = :id""")
			.param("now", Timestamp.from(now))
			.param("me", user.userId())
			.param("reason", note)
			.param("id", row.id())
			.update();
		// an invitation or change still waiting for the end of somebody's quiet hours must not ring after the cancellation
		int held = this.notifications.cancelHeldBackPushes("EVENT", row.id());
		EventRow cancelled = this.views.find(row.id(), false).orElseThrow();
		record(user, "event.cancel", cancelled, new Meta().with("hasReason", note != null).with("heldPushesCancelled", held));
		notifyAudience(user, cancelled, Kind.CANCELLED, cancelled.startAt(), null);
		return this.views.view(user, cancelled);
	}

	/** Soft-deletes a cancelled event (or a personal one). Idempotent for an already deleted event is 404. */
	@Transactional
	public void delete(CurrentUser user, UUID eventId) {
		EventRow row = this.views.requireVisible(user, eventId, true);
		requireManage(user, row);
		if (row.scope() != EventScope.PERSONAL && row.status() != EventStatus.CANCELLED) {
			throw ApiException.conflict("event.cancel_first");
		}
		this.jdbc.sql("update events set deleted_at = :now, updated_at = :now, version = version + 1 where id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", row.id())
			.update();
		record(user, "event.delete", row, new Meta());
	}

	// --- helpers shared with the occurrence service -------------------------------------------

	void requireManage(CurrentUser user, EventRow row) {
		if (!this.access.mayManage(user, row)) {
			throw ApiException.forbidden("event.access_denied");
		}
	}

	/**
	 * Tells the people of the event about a change or cancellation: a parent meeting's participants always, the audience of
	 * an ordinary event when the school asks for it ({@code calendar.notify.changes}), nobody for a personal event.
	 */
	void notifyAudience(CurrentUser user, EventRow row, Kind kind, Instant when, String occurrenceId) {
		if (row.type() == EventType.PARENT_MEETING) {
			this.notifier.send(kind, row, when, user.userId(), this.audiences.participants(row), occurrenceId);
		}
		else if (row.scope() != EventScope.PERSONAL && this.settings.notifyChanges(row.schoolId())) {
			this.notifier.send(kind, row, when, user.userId(), this.audiences.audience(row), occurrenceId);
		}
	}

	/** Audit metadata under construction (null values are left out). */
	static final class Meta extends LinkedHashMap<String, Object> {

		Meta with(String key, Object value) {
			if (value != null) {
				put(key, value);
			}
			return this;
		}

	}

	void record(CurrentUser user, String action, EventRow row, Meta meta) {
		AuditEntry entry = AuditEntry.of(action, AuditOutcome.SUCCESS)
			.target("event", row.id())
			.metadata("scope", row.scope().name())
			.metadata("type", row.type().name());
		if (row.schoolId() != null) {
			entry.school(row.schoolId());
		}
		entry.metadata(meta);
		this.audit.record(entry);
	}

	// --- capabilities -------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public CalendarCapabilities capabilities(CurrentUser user, UUID schoolId) {
		this.access.requireSchool(user, schoolId);
		boolean manager = this.access.manager(user, schoolId);
		List<CalendarClassRef> refs = new ArrayList<>();
		if (!manager) {
			Set<UUID> ids = this.access.creatableClasses(user, schoolId);
			if (!ids.isEmpty()) {
				refs = this.jdbc.sql("select id, name from classes where id in (:ids) order by name, id")
					.param("ids", ids)
					.query((rs, i) -> new CalendarClassRef(rs.getObject("id", UUID.class), rs.getString("name")))
					.list();
			}
		}
		boolean meetings = this.jdbc.sql("""
				select exists (select 1 from class_teachers ct where ct.teacher_id = :me and ct.school_id = :school
				               and ct.status = 'ACTIVE' and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))""")
			.param("me", user.userId())
			.param("school", schoolId)
			.query(Boolean.class)
			.single();
		return new CalendarCapabilities(manager, manager, refs, manager || !refs.isEmpty(), meetings, true,
				this.schedules.zoneOfSchool(schoolId, null).getId());
	}

}
