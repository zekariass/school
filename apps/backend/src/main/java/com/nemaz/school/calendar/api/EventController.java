package com.nemaz.school.calendar.api;

import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.CalendarCancelRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarCapabilities;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventUpdateRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarEventView;
import com.nemaz.school.calendar.application.CalendarModels.CalendarOccurrenceUpdateRequest;
import com.nemaz.school.calendar.application.CalendarModels.CalendarRsvpRequest;
import com.nemaz.school.calendar.application.EventService;
import com.nemaz.school.calendar.application.OccurrenceService;
import com.nemaz.school.calendar.application.RsvpService;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native events: create (school, campus, grade, class, parent meeting; personal under {@code /me}), read, change, cancel,
 * delete, answer, and the exceptions of recurring events. Event ids are global: a caller who may not see an event gets 404
 * {@code event.not_found} exactly as for an unknown id; one who sees it but may not change it gets 403
 * {@code event.access_denied}. A school whose calendar is switched off answers 403 {@code calendar.feature_disabled}.
 */
@RestController
@Tag(name = "calendar")
public class EventController {

	private final EventService events;

	private final OccurrenceService occurrences;

	private final RsvpService rsvps;

	EventController(EventService events, OccurrenceService occurrences, RsvpService rsvps) {
		this.events = events;
		this.occurrences = occurrences;
		this.rsvps = rsvps;
	}

	@PostMapping("/api/v1/schools/{schoolId}/events")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createEvent",
			summary = "Creates an event in a school's calendar: scope SCHOOL, CAMPUS (campusId) or GRADE_LEVEL (gradeLevelId) needs school.calendar.manage; scope CLASS (classId) a calendar manager, a class manager or an ACTIVE lead or co-teacher of the class (setting calendar.teachers_can_create_class_events); eventType PARENT_MEETING with regardingStudentId (no scope) an ACTIVE lead, co or subject teacher of one of the student's classes: the student's ACTIVE, unrestricted guardians with academics access are invited and notified, RSVP is on. Timed (startAt, endAt, timeZone) or all-day (allDay, startDate, endDate). Optional RFC 5545 subset recurrenceRule. Plain text. 400 event.* validation codes, 403 event.create_denied / event.meeting_not_allowed, 404 school.not_found / class.not_found / event.student_not_found, 409 class.archived, 422 event.no_participants")
	public CalendarEventView create(@PathVariable UUID schoolId, @Valid @RequestBody CalendarEventRequest request) {
		return this.events.createInSchool(CurrentUser.require(), schoolId, request);
	}

	@PostMapping("/api/v1/me/events")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createPersonalEvent",
			summary = "Creates a personal event: visible only to the caller, in their own time zone unless timeZone is given; no RSVP, not tied to a school (any signed-in user)")
	public CalendarEventView createPersonal(@Valid @RequestBody CalendarEventRequest request) {
		return this.events.createPersonal(CurrentUser.require(), request);
	}

	@GetMapping("/api/v1/schools/{schoolId}/events/capabilities")
	@Operation(operationId = "getCalendarCapabilities",
			summary = "What the caller may create in the school's calendar (school-wide events, events for given classes, parent meetings), for menus. 404 for callers who cannot see the school, 403 calendar.feature_disabled")
	public CalendarCapabilities capabilities(@PathVariable UUID schoolId) {
		return this.events.capabilities(CurrentUser.require(), schoolId);
	}

	@GetMapping("/api/v1/events/{eventId}")
	@Operation(operationId = "getEvent",
			summary = "One event the caller may see, with its rule and exceptions, the caller's own answer, what they may do, and (organisers and managers) the answers: a parent meeting's organiser sees each invited guardian's answer, nobody else does. 404 event.not_found when not visible (including a revoked or custody-restricted guardian's parent meeting)")
	public CalendarEventView get(@PathVariable UUID eventId) {
		return this.events.get(CurrentUser.require(), eventId);
	}

	@PatchMapping("/api/v1/events/{eventId}")
	@Operation(operationId = "updateEvent",
			summary = "Changes an event (creator or manager): text, place, time (startAt and endAt together, or both dates), zone, rule (moves the whole series and clears its exceptions), RSVP on or off. Moving the time or place tells the audience (always for parent meetings; other events when calendar.notify.changes) and asks answers again. 403 event.access_denied, 409 event.cancelled")
	public CalendarEventView update(@PathVariable UUID eventId, @Valid @RequestBody CalendarEventUpdateRequest request) {
		return this.events.update(CurrentUser.require(), eventId, request);
	}

	@PostMapping("/api/v1/events/{eventId}/cancel")
	@Operation(operationId = "cancelEvent",
			summary = "Cancels the whole event (every occurrence of a series): it stays in the calendar as CANCELLED, the audience is told, held-back pushes of the event are dropped. Idempotent")
	public CalendarEventView cancel(@PathVariable UUID eventId, @Valid @RequestBody(required = false) CalendarCancelRequest request) {
		return this.events.cancel(CurrentUser.require(), eventId, (request != null) ? request.reason() : null);
	}

	@DeleteMapping("/api/v1/events/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteEvent",
			summary = "Deletes (soft) a cancelled event, or a personal one at any time. 409 event.cancel_first for a scheduled school or class event")
	public void delete(@PathVariable UUID eventId) {
		this.events.delete(CurrentUser.require(), eventId);
	}

	@PutMapping("/api/v1/events/{eventId}/rsvp")
	@Operation(operationId = "answerEvent",
			summary = "Answers YES, NO or MAYBE (changeable until the event starts; the same answer again changes nothing). 409 event.rsvp_disabled, 409 event.rsvp_closed after the start or a cancellation; 404 when the caller is not an invited guardian of a parent meeting")
	public CalendarEventView answer(@PathVariable UUID eventId, @Valid @RequestBody CalendarRsvpRequest request) {
		return this.rsvps.answer(CurrentUser.require(), eventId, request.status());
	}

	@PostMapping("/api/v1/events/{eventId}/occurrences/{occurrenceId}/cancel")
	@Operation(operationId = "cancelEventOccurrence",
			summary = "Cancels one occurrence of a recurring event (an exception, EXDATE): it stays visible as CANCELLED and the audience is told. occurrenceId is the one items of the calendar carry. Idempotent. 409 event.not_recurring, 404 event.occurrence_not_found")
	public CalendarEventView cancelOccurrence(@PathVariable UUID eventId, @PathVariable String occurrenceId,
			@Valid @RequestBody(required = false) CalendarCancelRequest request) {
		return this.occurrences.cancel(CurrentUser.require(), eventId, occurrenceId, request);
	}

	@PatchMapping("/api/v1/events/{eventId}/occurrences/{occurrenceId}")
	@Operation(operationId = "updateEventOccurrence",
			summary = "Changes one occurrence of a recurring event (title, text, place, time: at most 7 days from its place in the series, in the same kind, timed or all-day). Brings back a cancelled occurrence. Time or place changes tell the audience")
	public CalendarEventView updateOccurrence(@PathVariable UUID eventId, @PathVariable String occurrenceId,
			@Valid @RequestBody CalendarOccurrenceUpdateRequest request) {
		return this.occurrences.modify(CurrentUser.require(), eventId, occurrenceId, request);
	}

	@DeleteMapping("/api/v1/events/{eventId}/occurrences/{occurrenceId}")
	@Operation(operationId = "restoreEventOccurrence",
			summary = "Removes the exception of one occurrence: it is as the series says again. Idempotent")
	public CalendarEventView restoreOccurrence(@PathVariable UUID eventId, @PathVariable String occurrenceId) {
		return this.occurrences.restore(CurrentUser.require(), eventId, occurrenceId);
	}

}
