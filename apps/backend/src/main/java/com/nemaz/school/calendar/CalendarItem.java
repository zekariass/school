package com.nemaz.school.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the calendar, whatever its source. Timed items carry {@code startAt}/{@code endAt} (instants) and the
 * {@code timeZone} to display them in; all-day items carry {@code startDate}/{@code endDate} (inclusive calendar dates in
 * {@code timeZone}) and also the instants {@code startAt} (start of the first day) and {@code endAt} (start of the day after
 * the last one) so a client can sort and window without special cases.
 */
@Schema(name = "CalendarItem")
public record CalendarItem(
		@Schema(description = "Stable id of this entry: the event id, 'eventId@occurrenceId' for an occurrence of a recurring event, 'HOLIDAY:id', 'PERIOD:id:START' ...", requiredMode = Schema.RequiredMode.REQUIRED) String id,
		@Schema(description = "EVENT, PERSONAL_EVENT, HOLIDAY, ACADEMIC_PERIOD (later sources add theirs)", requiredMode = Schema.RequiredMode.REQUIRED) String source,
		@Schema(description = "Source-specific kind: the event type (SCHOOL_EVENT, CLASS_EVENT, EXAM, PARENT_MEETING, OTHER), the holiday type, or YEAR_START, YEAR_END, PERIOD_START, PERIOD_END", requiredMode = Schema.RequiredMode.REQUIRED) String kind,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title, String description, String location,
		UUID schoolId, String scope, UUID campusId, UUID gradeLevelId, UUID classId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean allDay,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startAt,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endAt,
		@Schema(description = "All-day items: first day (inclusive), in timeZone") LocalDate startDate,
		@Schema(description = "All-day items: last day (inclusive), in timeZone") LocalDate endDate,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timeZone,
		@Schema(description = "SCHEDULED or CANCELLED (cancelled items stay visible)", requiredMode = Schema.RequiredMode.REQUIRED) String status,
		UUID eventId,
		@Schema(description = "Opaque occurrence id of a recurring event (the path part of the occurrence endpoints); absent for one-off events") String occurrenceId,
		boolean recurring, boolean modified, boolean rsvpEnabled,
		@Schema(description = "The caller's own answer (YES, NO, MAYBE or PENDING) on events that ask for one") String myRsvp,
		@Schema(description = "Parent meetings: the child it is about (only the participants see the event at all)") UUID regardingStudentId,
		@Schema(description = "Holidays: the name in every language; title is the one for the caller's language") Map<String, String> titleI18n) {

}
