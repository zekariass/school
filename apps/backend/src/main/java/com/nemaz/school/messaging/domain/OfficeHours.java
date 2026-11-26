package com.nemaz.school.messaging.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The school's optional office hours ({@code messaging.teacher_hours}): ISO weekdays 1-7 and a same-day window in the school's
 * time zone, start inclusive and end exclusive. A window whose start is not before its end is never "inside" (restrictive: the
 * messages are then always delivered silently; overnight windows are not supported).
 */
public record OfficeHours(List<Integer> days, LocalTime start, LocalTime end) {

	public OfficeHours {
		days = List.copyOf(days);
	}

	public boolean contains(Instant instant, ZoneId zone) {
		ZonedDateTime local = instant.atZone(zone);
		LocalTime time = local.toLocalTime();
		return this.days.contains(local.getDayOfWeek().getValue()) && this.start.isBefore(this.end)
				&& !time.isBefore(this.start) && time.isBefore(this.end);
	}

}
