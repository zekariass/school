package com.nemaz.school.calendar.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.calendar.CalendarItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** The requests, views and enumerations of the calendar API. Names are unique across the OpenAPI document. */
public final class CalendarModels {

	private CalendarModels() {
	}

	public enum EventScope {

		SCHOOL, CAMPUS, GRADE_LEVEL, CLASS, PERSONAL,
		/** Only the people invited see the event (parent meetings). Set by the server; never sent. */
		INVITEES

	}

	public enum EventType {

		SCHOOL_EVENT, CLASS_EVENT, EXAM, PARENT_MEETING, OTHER

	}

	public enum EventStatus {

		SCHEDULED, CANCELLED

	}

	public enum RsvpAnswer {

		YES, NO, MAYBE

	}

	@Schema(name = "CalendarEventRequest", description = """
			A new event. Timed events: startAt, endAt (instants, end not before start) and timeZone (IANA; default the school's). \
			All-day events: allDay=true with startDate and endDate (calendar dates in timeZone, endDate inclusive and not before \
			startDate); no instants. scope SCHOOL needs nothing more, CAMPUS needs campusId, GRADE_LEVEL gradeLevelId, CLASS classId; \
			PARENT_MEETING needs regardingStudentId and no scope (the guardians of that child are invited). recurrenceRule is an RFC 5545 \
			subset: FREQ=DAILY|WEEKLY|MONTHLY, INTERVAL, BYDAY (weekly), BYMONTHDAY (monthly), UNTIL or COUNT; not on parent meetings and \
			not with rsvpEnabled. Text is plain text.""")
	public record CalendarEventRequest(EventScope scope, UUID campusId, UUID gradeLevelId, UUID classId, EventType eventType,
			@Size(max = 2000) String title, @Size(max = 20000) String description, @Size(max = 2000) String location,
			@Size(max = 64) String timeZone, Boolean allDay, Instant startAt, Instant endAt, LocalDate startDate,
			LocalDate endDate, @Size(max = 300) String recurrenceRule, Boolean rsvpEnabled, UUID regardingStudentId) {
	}

	@Schema(name = "CalendarEventUpdateRequest", description = """
			Changes an event; absent fields stay. Changing the time takes both startAt and endAt (or both dates, with allDay), \
			and for a recurring event moves the whole series (its exceptions are cleared). Empty description, location or \
			recurrenceRule removes it. The scope, type and student cannot change. Moving the time or place tells the audience \
			(always for parent meetings, and for other events when the school's settings ask for it); an RSVP is asked again \
			when the time moves.""")
	public record CalendarEventUpdateRequest(@Size(max = 2000) String title, @Size(max = 20000) String description,
			@Size(max = 2000) String location, @Size(max = 64) String timeZone, Boolean allDay, Instant startAt, Instant endAt,
			LocalDate startDate, LocalDate endDate, @Size(max = 300) String recurrenceRule, Boolean rsvpEnabled) {
	}

	@Schema(name = "CalendarOccurrenceUpdateRequest", description = """
			Changes one occurrence of a recurring event (an exception); absent fields keep the series' values. The new time \
			may move at most 7 days from the occurrence's own time and keeps its kind (timed or all-day).""")
	public record CalendarOccurrenceUpdateRequest(@Size(max = 2000) String title, @Size(max = 20000) String description,
			@Size(max = 2000) String location, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate) {
	}

	@Schema(name = "CalendarCancelRequest")
	public record CalendarCancelRequest(@Size(max = 2000) String reason) {
	}

	@Schema(name = "CalendarRsvpRequest")
	public record CalendarRsvpRequest(@jakarta.validation.constraints.NotNull RsvpAnswer status) {
	}

	@Schema(name = "CalendarRsvpCounts")
	public record CalendarRsvpCounts(int yes, int no, int maybe, int pending) {
	}

	@Schema(name = "CalendarRsvpParticipant", description = "A guardian invited to a parent meeting and their answer (the organiser only)")
	public record CalendarRsvpParticipant(UUID userId, String displayName, String status, Instant respondedAt) {
	}

	@Schema(name = "CalendarRsvpView")
	public record CalendarRsvpView(boolean enabled,
			@Schema(description = "The caller's own answer: YES, NO, MAYBE or PENDING (absent when the caller has not been asked)") String mine,
			@Schema(description = "Whether the caller can still answer now (event not started, not cancelled)") boolean canAnswer,
			@Schema(description = "Totals of the answers: the organiser and managers only") CalendarRsvpCounts counts,
			@Schema(description = "Who answered what: the organiser of a parent meeting only; nobody else ever sees it") List<CalendarRsvpParticipant> participants) {
	}

	@Schema(name = "CalendarOccurrenceOverrideView", description = "An exception of a recurring event: a cancelled or changed occurrence")
	public record CalendarOccurrenceOverrideView(String occurrenceId, Instant occurrenceStart,
			@Schema(description = "CANCELLED or MODIFIED") String status, String title, String description, String location,
			Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate, String reason) {
	}

	@Schema(name = "CalendarEventCapabilities", description = "What the caller may do with this event (the API stays the authority)")
	public record CalendarEventCapabilities(boolean canEdit, boolean canCancel, boolean canDelete) {
	}

	@Schema(name = "CalendarEventView")
	public record CalendarEventView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id, UUID schoolId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) EventScope scope, UUID campusId, UUID gradeLevelId,
			UUID classId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EventType eventType,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title, String description, String location,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timeZone, boolean allDay,
			@Schema(description = "Timed events: the start; all-day events: the start of the first day") Instant startAt,
			@Schema(description = "Timed events: the end; all-day events: the start of the day after the last one") Instant endAt,
			LocalDate startDate, LocalDate endDate,
			@Schema(description = "The RFC 5545 subset rule, canonical form") String recurrenceRule, boolean recurring,
			@Schema(description = "When a finite series ends (the end of its last occurrence)") Instant recurrenceEndsAt,
			boolean rsvpEnabled, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EventStatus status, Instant cancelledAt,
			String cancellationReason, UUID createdBy, String createdByName, UUID regardingStudentId,
			CalendarRsvpView rsvp, List<CalendarOccurrenceOverrideView> overrides, CalendarEventCapabilities capabilities,
			Instant createdAt, Instant updatedAt) {
	}

	@Schema(name = "CalendarClassRef")
	public record CalendarClassRef(UUID id, String name) {
	}

	@Schema(name = "CalendarCapabilities", description = "What the caller may create in a school (for menus; the API stays the authority)")
	public record CalendarCapabilities(boolean canManage, boolean canCreateSchoolEvents,
			@Schema(description = "Classes the caller may create class events for (every class of the school for managers: omitted then)") List<CalendarClassRef> classes,
			boolean canCreateAnyClassEvent, boolean canCreateParentMeetings, boolean calendarEnabled, String timeZone) {
	}

	@Schema(name = "CalendarPage", description = "The calendar of a window, ordered by start then id, keyset paged")
	public record CalendarPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CalendarItem> items,
			@Schema(description = "Opaque cursor of the next page; absent on the last page") String nextCursor,
			@Schema(description = "True when the window held more items than the server returns for one window (narrow the window)") boolean truncated,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant from,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant to) {
	}

}
