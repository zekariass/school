package com.nemaz.school.notifications.application;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursPolicyTest {

	private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

	private static final LocalTime TEN_PM = LocalTime.of(22, 0);

	private static final LocalTime SIX_AM = LocalTime.of(6, 0);

	/** Local time in Addis Ababa (UTC+3, no daylight saving) as an instant. */
	private static Instant addis(int hour, int minute) {
		return Instant.parse("2026-06-10T00:00:00Z").plusSeconds(((hour - 3) * 60L + minute) * 60L);
	}

	@Test
	void windowAcrossMidnight() {
		assertThat(QuietHoursPolicy.isQuiet(addis(21, 59), ADDIS, TEN_PM, SIX_AM)).isFalse();
		assertThat(QuietHoursPolicy.isQuiet(addis(22, 0), ADDIS, TEN_PM, SIX_AM)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(23, 30), ADDIS, TEN_PM, SIX_AM)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(0, 0), ADDIS, TEN_PM, SIX_AM)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(5, 59), ADDIS, TEN_PM, SIX_AM)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(6, 0), ADDIS, TEN_PM, SIX_AM)).isFalse();
		assertThat(QuietHoursPolicy.isQuiet(addis(12, 0), ADDIS, TEN_PM, SIX_AM)).isFalse();
	}

	@Test
	void windowWithinOneDay() {
		LocalTime start = LocalTime.of(13, 0);
		LocalTime end = LocalTime.of(14, 30);
		assertThat(QuietHoursPolicy.isQuiet(addis(12, 59), ADDIS, start, end)).isFalse();
		assertThat(QuietHoursPolicy.isQuiet(addis(13, 0), ADDIS, start, end)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(14, 29), ADDIS, start, end)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(addis(14, 30), ADDIS, start, end)).isFalse();
	}

	@Test
	void usesTheUsersTimezoneNotUtc() {
		Instant instant = Instant.parse("2026-06-10T20:00:00Z"); // 23:00 in Addis, 20:00 UTC
		assertThat(QuietHoursPolicy.isQuiet(instant, ADDIS, TEN_PM, SIX_AM)).isTrue();
		assertThat(QuietHoursPolicy.isQuiet(instant, ZoneId.of("UTC"), TEN_PM, SIX_AM)).isFalse();
	}

	@Test
	void noWindowWhenUnsetOrEmpty() {
		assertThat(QuietHoursPolicy.isQuiet(addis(23, 0), ADDIS, null, null)).isFalse();
		assertThat(QuietHoursPolicy.isQuiet(addis(23, 0), ADDIS, TEN_PM, TEN_PM)).isFalse();
	}

}
