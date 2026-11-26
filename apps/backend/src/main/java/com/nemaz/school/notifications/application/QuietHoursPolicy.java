package com.nemaz.school.notifications.application;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Quiet hours are a daily window in the user's own timezone, {@code start} inclusive to
 * {@code end} exclusive. A window whose end is not after its start runs past midnight (22:00 to
 * 06:00 covers 23:30 and 05:59). Equal start and end means no window.
 */
public final class QuietHoursPolicy {

	private QuietHoursPolicy() {
	}

	public static boolean isQuiet(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
		if (start == null || end == null || start.equals(end)) {
			return false;
		}
		LocalTime local = now.atZone(zone).toLocalTime();
		if (start.isBefore(end)) {
			return !local.isBefore(start) && local.isBefore(end);
		}
		return !local.isBefore(start) || local.isBefore(end);
	}

	/**
	 * When the quiet window that {@code now} is in ends (the next time the zone's clock shows {@code end}); empty when
	 * {@code now} is not quiet. Used to hold a push back instead of dropping it.
	 */
	public static java.util.Optional<Instant> endOfQuietHours(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
		if (!isQuiet(now, zone, start, end)) {
			return java.util.Optional.empty();
		}
		java.time.ZonedDateTime local = now.atZone(zone);
		java.time.ZonedDateTime candidate = local.with(end);
		if (!candidate.isAfter(local)) {
			candidate = local.toLocalDate().plusDays(1).atTime(end).atZone(zone);
		}
		return java.util.Optional.of(candidate.toInstant());
	}

}
