package com.nemaz.school.attendance.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * School-local time. An attendance date is a calendar date in the time zone of the class's campus (when it has its own)
 * or of the school; the day starts at local midnight (for zones with daylight saving the day may be 23 or 25 hours
 * long, which {@link LocalDate#atStartOfDay(ZoneId)} handles). The server clock decides what "now" is.
 */
@Component
class SchoolTime {

	private final JdbcClient jdbc;

	private final Clock clock;

	SchoolTime(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	ZoneId zoneOf(ClassRef ref) {
		return zone(ref.schoolId(), ref.campusId());
	}

	/** The zone of a campus (else of the school) by ids, for records that carry a campus but no class. */
	ZoneId zoneOf(UUID schoolId, UUID campusId) {
		return zone(schoolId, campusId);
	}

	ZoneId zoneOfSchool(UUID schoolId) {
		return zone(schoolId, null);
	}

	private ZoneId zone(UUID schoolId, UUID campusId) {
		String name = this.jdbc.sql("""
				select coalesce(c.timezone, s.timezone) from schools s left join campuses c on c.id = :campus and c.school_id = s.id
				where s.id = :school""")
			.param("school", schoolId)
			.param("campus", campusId)
			.query(String.class)
			.optional()
			.orElse("UTC");
		try {
			return ZoneId.of(name);
		}
		catch (DateTimeException ex) {
			return ZoneId.of("UTC");
		}
	}

	Instant now() {
		return this.clock.instant();
	}

	LocalDate today(ZoneId zone) {
		return LocalDate.ofInstant(this.clock.instant(), zone);
	}

	/** The school-local date of an instant. */
	LocalDate dateOf(Instant instant, ZoneId zone) {
		return LocalDate.ofInstant(instant, zone);
	}

	Instant startOf(LocalDate date, ZoneId zone) {
		return date.atStartOfDay(zone).toInstant();
	}

	/** The instant the date ends: the start of the next local day. */
	Instant endOf(LocalDate date, ZoneId zone) {
		return date.plusDays(1).atStartOfDay(zone).toInstant();
	}

}
