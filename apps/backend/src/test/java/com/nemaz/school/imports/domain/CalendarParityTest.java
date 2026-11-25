package com.nemaz.school.imports.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java port of the calendar conversion against {@code packages/calendar} (the TypeScript source of truth): the
 * fixture lists the Ethiopian date of every Gregorian day from 1900-01-01 to 2100-12-31
 * ({@code node scripts/gen-calendar-parity-fixture.mjs}); both directions are checked.
 */
class CalendarParityTest {

	@Test
	void everyDayOf1900To2100ConvertsLikeTheTypeScriptPackage() throws IOException {
		int days = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
				CalendarParityTest.class.getResourceAsStream("/imports/calendar-parity.csv"), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				String[] sides = line.split(",");
				int[] g = parts(sides[0]);
				int[] e = parts(sides[1]);
				LocalDate gregorian = LocalDate.of(g[0], g[1], g[2]);
				EthiopianCalendar.Civil ethiopian = EthiopianCalendar.toEthiopian(gregorian);
				assertThat(new int[] { ethiopian.year(), ethiopian.month(), ethiopian.day() }).as("to Ethiopian: " + gregorian)
					.containsExactly(e);
				assertThat(EthiopianCalendar.toGregorian(e[0], e[1], e[2])).as("to Gregorian: " + sides[1]).isEqualTo(gregorian);
				days++;
			}
		}
		assertThat(days).isEqualTo(73_414);
	}

	@Test
	void knownDates() {
		// Ethiopian New Year 2018 E.C. falls on 11 September 2025; the year after the leap year 2015 starts on the 12th
		assertThat(EthiopianCalendar.toGregorian(2018, 1, 1)).isEqualTo(LocalDate.of(2025, 9, 11));
		assertThat(EthiopianCalendar.toGregorian(2016, 1, 1)).isEqualTo(LocalDate.of(2023, 9, 12));
		assertThat(EthiopianCalendar.isLeapYear(2015)).isTrue();
		assertThat(EthiopianCalendar.daysInMonth(2015, 13)).isEqualTo(6);
		assertThat(EthiopianCalendar.isValid(2016, 13, 6)).isFalse();
	}

	private static int[] parts(String text) {
		String[] p = text.split("-");
		return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
	}

}
