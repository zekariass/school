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

}
