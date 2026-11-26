package com.nemaz.school.calendar.api;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.calendar.application.CalendarModels.CalendarPage;
import com.nemaz.school.calendar.application.CalendarService;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The union calendar (R-17). */
@RestController
@Tag(name = "calendar")
public class CalendarController {

	private final CalendarService calendar;

	CalendarController(CalendarService calendar) {
		this.calendar = calendar;
	}

	@GetMapping("/api/v1/calendar")
	@Operation(operationId = "getCalendar",
			summary = "The caller's calendar for a window [from, to) (instants, at most 366 days): native events they may see (school-wide for members and guardians, campus, grade and class events by the class and school visibility rules, parent meetings only for their organiser and the invited guardians whose link still holds, their own personal events), the holidays of their schools and the start and end of their academic years and periods, each item tagged with its source. schoolId narrows to one school (404 school.not_found, 403 calendar.feature_disabled); studentId narrows a guardian's view to one child (404 calendar.student_not_found); sources is a comma list of EVENT, PERSONAL_EVENT, HOLIDAY, ACADEMIC_PERIOD (default all, PERSONAL_EVENT only when no school or child is named). Recurring events are expanded for the window only. Ordered by start then id, keyset paged (limit up to 500, 200 by default); truncated says the window held more than the server returns. Times are instants plus the item's time zone; all-day items also carry inclusive calendar dates")
	public CalendarPage get(@RequestParam Instant from, @RequestParam Instant to,
			@RequestParam(required = false) UUID schoolId, @RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) String sources, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.calendar.calendar(CurrentUser.require(), from, to, schoolId, studentId, sources, cursor, limit);
	}

}
