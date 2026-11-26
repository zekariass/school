package com.nemaz.school.calendar;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;

/**
 * What a {@link CalendarSource} is asked: the caller, the window {@code [from, to)} (at most a year), the schools to look
 * in (already checked for visibility and for the calendar feature; empty for a source that also serves non-school items
 * such as personal events), the optional child filter (the caller has been checked to be that child's guardian), and the
 * cap per source.
 */
public record CalendarQuery(CurrentUser user, Instant from, Instant to, Set<UUID> schoolIds, UUID studentId, int maxItems) {

	public CalendarQuery {
		schoolIds = Set.copyOf(schoolIds);
	}

}
