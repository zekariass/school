package com.nemaz.school.attendance.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code school.attendance.checkin.*}: the rate limits of the QR check-in, all per minute (fixed windows shared by every
 * backend instance). Every attempt counts, accepted or not.
 * <ul>
 * <li>{@code studentAttemptsPerMinute}: one student (or staff member) on a rotating code, counted before anything else is
 * looked at.</li>
 * <li>{@code classAttemptsPerMinute}: one rotating session, counted only for people who may check in to it (so an outsider
 * cannot use up a class's allowance).</li>
 * <li>{@code scansPerMinute}: one scanning teacher or manager on static cards (including the unknown ones).</li>
 * </ul>
 */
@ConfigurationProperties("school.attendance.checkin")
public record AttendanceCheckinProperties(@DefaultValue("10") int studentAttemptsPerMinute,
		@DefaultValue("120") int classAttemptsPerMinute, @DefaultValue("120") int scansPerMinute) {
}
