package com.nemaz.school.calendar.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.nemaz.school.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The RRULE subset: parsing, rejection, expansion, daylight saving, caps. Pure logic, no Spring. */
class RecurrenceTest {

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

	private static final Instant WINDOW_START = Instant.parse("2020-01-01T00:00:00Z");

	private static final Instant FAR = Instant.parse("2040-01-01T00:00:00Z");

	private static Recurrence.Series timed(ZoneId zone, String localStart, int minutes) {
		Instant start = ZonedDateTime.of(java.time.LocalDateTime.parse(localStart), zone).toInstant();
		return Recurrence.Series.timed(zone, start, start.plus(Duration.ofMinutes(minutes)));
	}

	private static List<Recurrence.Occurrence> all(String rule, Recurrence.Series series, int max) {
		return Recurrence.parse(rule).expand(series, WINDOW_START, FAR, max, null);
	}

	private static String code(Runnable call) {
		try {
			call.run();
		}
		catch (ApiException ex) {
			return ex.code();
		}
		return null;
	}

	// --- parsing ------------------------------------------------------------------------------

	@Test
	void parsesTheSubsetAndStoresACanonicalForm() {
		assertThat(Recurrence.parse("rrule:freq=weekly;byday=we,mo;interval=2;count=10").text())
			.isEqualTo("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10");
		assertThat(Recurrence.parse("FREQ=MONTHLY;BYMONTHDAY=15,1;UNTIL=20271231").text())
			.isEqualTo("FREQ=MONTHLY;BYMONTHDAY=1,15;UNTIL=20271231");
		assertThat(Recurrence.parse("FREQ=DAILY;UNTIL=20271231T210000Z").text()).isEqualTo("FREQ=DAILY;UNTIL=20271231T210000Z");
		assertThat(Recurrence.parse("FREQ=DAILY").finite()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "FREQ=YEARLY", "FREQ=HOURLY", "FREQ=WEEKLY;BYDAY=2MO", "FREQ=WEEKLY;BYDAY=-1FR",
			"FREQ=MONTHLY;BYSETPOS=1;BYDAY=MO", "FREQ=MONTHLY;BYMONTHDAY=-1", "FREQ=DAILY;BYMONTH=3", "FREQ=DAILY;BYYEARDAY=5",
			"FREQ=WEEKLY;WKST=SU", "FREQ=DAILY;BYDAY=MO", "FREQ=WEEKLY;BYMONTHDAY=3", "FREQ=DAILY;EXDATE=20260101",
			"FREQ=DAILY;BYHOUR=3" })
	void rejectsWhatIsOutsideTheSubsetWithAStableCode(String rule) {
		assertThat(code(() -> Recurrence.parse(rule))).isEqualTo("event.recurrence_unsupported");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "nonsense", "FREQ=", "INTERVAL=2", "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;INTERVAL=100",
			"FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=1001", "FREQ=DAILY;COUNT=5;UNTIL=20270101", "FREQ=DAILY;FREQ=WEEKLY",
			"FREQ=DAILY;UNTIL=2027", "FREQ=DAILY;UNTIL=20271301", "FREQ=WEEKLY;BYDAY=XX", "FREQ=DAILY;FOO=1", "FREQ=DAILY;COUNT=x" })
	void rejectsTextThatIsNotARule(String rule) {
		assertThat(code(() -> Recurrence.parse(rule))).isEqualTo("event.recurrence_invalid");
	}

	@Test
	void theFirstDayMustMatchTheRule() {
		LocalDate tuesday = LocalDate.of(2026, 12, 1);
		assertThat(code(() -> Recurrence.parse("FREQ=WEEKLY;BYDAY=MO,WE").validateFor(tuesday, ADDIS, LocalTime.of(9, 0))))
			.isEqualTo("event.recurrence_start_mismatch");
		assertThat(code(() -> Recurrence.parse("FREQ=MONTHLY;BYMONTHDAY=2").validateFor(tuesday, ADDIS, LocalTime.of(9, 0))))
			.isEqualTo("event.recurrence_start_mismatch");
		Recurrence.parse("FREQ=WEEKLY;BYDAY=TU,WE").validateFor(tuesday, ADDIS, LocalTime.of(9, 0));
		assertThat(code(() -> Recurrence.parse("FREQ=DAILY;UNTIL=20261130").validateFor(tuesday, ADDIS, LocalTime.of(9, 0))))
			.isEqualTo("event.recurrence_invalid");
		assertThat(code(() -> Recurrence.parse("FREQ=DAILY;UNTIL=20400101").validateFor(tuesday, ADDIS, LocalTime.of(9, 0))))
			.isEqualTo("event.recurrence_too_long");
	}

	// --- expansion ----------------------------------------------------------------------------

	@Test
	void dailyWithIntervalAndCount() {
		Recurrence.Series series = timed(ADDIS, "2026-12-01T09:00", 60);
		List<Recurrence.Occurrence> days = all("FREQ=DAILY;INTERVAL=3;COUNT=4", series, 100);
		assertThat(days).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 7),
					LocalDate.of(2026, 12, 10));
		assertThat(days.getFirst().start()).isEqualTo(Instant.parse("2026-12-01T06:00:00Z"));
		assertThat(days.getFirst().end()).isEqualTo(Instant.parse("2026-12-01T07:00:00Z"));
	}

	@Test
	void weeklyByDayStartsAtTheFirstDayAndKeepsTheWeekdayOrder() {
		// Wednesday 2 December 2026: the Monday of the same week is before the start and is not an occurrence
		Recurrence.Series series = timed(ADDIS, "2026-12-02T09:00", 45);
		List<Recurrence.Occurrence> occurrences = all("FREQ=WEEKLY;BYDAY=WE,MO,FR;COUNT=5", series, 100);
		assertThat(occurrences).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 7),
					LocalDate.of(2026, 12, 9), LocalDate.of(2026, 12, 11));
	}

	@Test
	void weeklyEveryOtherWeekSkipsTheWeeksInBetween() {
		Recurrence.Series series = timed(ADDIS, "2026-12-07T09:00", 45);
		assertThat(all("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=3", series, 100)).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 21), LocalDate.of(2027, 1, 4));
	}

	@Test
	void weeklyWithoutByDayUsesTheWeekdayOfTheStart() {
		Recurrence.Series series = timed(ADDIS, "2026-12-03T09:00", 45); // Thursday
		assertThat(all("FREQ=WEEKLY;COUNT=3", series, 100)).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 17));
	}

	@Test
	void monthlyByDayOfMonthSkipsMonthsWithoutThatDay() {
		Recurrence.Series series = timed(ADDIS, "2026-01-31T09:00", 45);
		assertThat(all("FREQ=MONTHLY;COUNT=4", series, 100)).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31),
					LocalDate.of(2026, 7, 31));
		Recurrence.Series fifteenth = timed(ADDIS, "2026-11-01T09:00", 45);
		assertThat(all("FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=4", fifteenth, 100)).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 1),
					LocalDate.of(2026, 12, 15));
	}

	@Test
	void monthlyEveryThirdMonthAcrossTheYearEnd() {
		Recurrence.Series series = timed(ADDIS, "2026-11-10T09:00", 45);
		assertThat(all("FREQ=MONTHLY;INTERVAL=3;COUNT=3", series, 100)).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 11, 10), LocalDate.of(2027, 2, 10), LocalDate.of(2027, 5, 10));
	}

	@Test
	void untilIsInclusiveAsADateAndAsAnInstant() {
		Recurrence.Series series = timed(ADDIS, "2026-12-01T09:00", 60);
		assertThat(all("FREQ=DAILY;UNTIL=20261203", series, 100)).hasSize(3);
		// 06:00Z is 09:00 in Addis: an UNTIL of exactly that instant still includes the occurrence
		assertThat(all("FREQ=DAILY;UNTIL=20261203T060000Z", series, 100)).hasSize(3);
		assertThat(all("FREQ=DAILY;UNTIL=20261203T055959Z", series, 100)).hasSize(2);
	}

	@Test
	void countAndUntilGiveDifferentSeriesForTheSameDates() {
		Recurrence.Series series = timed(ADDIS, "2026-12-01T09:00", 60);
		assertThat(all("FREQ=DAILY;COUNT=10", series, 100)).hasSize(10);
		assertThat(all("FREQ=DAILY;UNTIL=20261210", series, 100)).hasSize(10);
		assertThat(Recurrence.parse("FREQ=DAILY;COUNT=10").last(series).day()).isEqualTo(LocalDate.of(2026, 12, 10));
		assertThat(Recurrence.parse("FREQ=DAILY;UNTIL=20261210").last(series).end()).isEqualTo(Instant.parse("2026-12-10T07:00:00Z"));
		assertThat(Recurrence.parse("FREQ=DAILY").last(series)).isNull();
	}

	@Test
	void expansionIsForTheWindowOnly() {
		Recurrence.Series series = timed(ADDIS, "2026-01-05T09:00", 60);
		List<Recurrence.Occurrence> window = Recurrence.parse("FREQ=WEEKLY;BYDAY=MO")
			.expand(series, Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), 100, null);
		assertThat(window).extracting(Recurrence.Occurrence::day)
			.containsExactly(LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 21),
					LocalDate.of(2026, 12, 28));
	}

	@Test
	void anOccurrenceOverlappingTheWindowEdgeIsIncludedOnceItEndsAfterTheStart() {
		Recurrence.Series series = timed(ADDIS, "2026-12-01T22:00", 180); // ends 01:00 the next day (19:00Z -> 22:00Z)
		Instant from = Instant.parse("2026-12-01T20:00:00Z"); // 23:00 local: the first occurrence is under way
		List<Recurrence.Occurrence> window = Recurrence.parse("FREQ=DAILY").expand(series, from, from.plus(Duration.ofHours(1)), 10, null);
		assertThat(window).hasSize(1);
		// a window that starts exactly when it ends excludes it
		assertThat(Recurrence.parse("FREQ=DAILY").expand(series, Instant.parse("2026-12-01T22:00:00Z"),
				Instant.parse("2026-12-01T23:00:00Z"), 10, null)).isEmpty();
	}

	@Test
	void capsStopTheExpansionAndSayWhy() {
		Recurrence.Series series = timed(ADDIS, "2026-12-01T09:00", 60);
		boolean[] truncated = new boolean[1];
		List<Recurrence.Occurrence> some = Recurrence.parse("FREQ=DAILY").expand(series, WINDOW_START, FAR, 25, truncated);
		assertThat(some).hasSize(25);
		assertThat(truncated[0]).isTrue();
		boolean[] complete = new boolean[1];
		assertThat(Recurrence.parse("FREQ=DAILY;COUNT=5").expand(series, WINDOW_START, FAR, 25, complete)).hasSize(5);
		assertThat(complete[0]).isFalse();
	}

	// --- daylight saving ----------------------------------------------------------------------

	@Test
	void aWallClockTimeSurvivesTheSpringForwardChange() {
		// New York: clocks go forward on Sunday 14 March 2027. A Monday 09:00 meeting stays at 09:00 local.
		Recurrence.Series series = timed(NEW_YORK, "2027-03-08T09:00", 60);
		List<Recurrence.Occurrence> weeks = all("FREQ=WEEKLY;BYDAY=MO;COUNT=3", series, 10);
		assertThat(weeks.get(0).start()).isEqualTo(Instant.parse("2027-03-08T14:00:00Z")); // EST, UTC-5
		assertThat(weeks.get(1).start()).isEqualTo(Instant.parse("2027-03-15T13:00:00Z")); // EDT, UTC-4
		assertThat(weeks.get(2).start()).isEqualTo(Instant.parse("2027-03-22T13:00:00Z"));
		assertThat(weeks.get(1).start().atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(Duration.between(weeks.get(1).start(), weeks.get(1).end())).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void aWallClockTimeSurvivesTheFallBackChange() {
		// New York: clocks go back on Sunday 7 November 2027
		Recurrence.Series series = timed(NEW_YORK, "2027-11-01T09:00", 60);
		List<Recurrence.Occurrence> weeks = all("FREQ=WEEKLY;COUNT=2", series, 10);
		assertThat(weeks.get(0).start()).isEqualTo(Instant.parse("2027-11-01T13:00:00Z")); // EDT
		assertThat(weeks.get(1).start()).isEqualTo(Instant.parse("2027-11-08T14:00:00Z")); // EST
	}

	@Test
	void aTimeThatDoesNotExistMovesForwardAndOneThatExistsTwiceTakesTheFirst() {
		// 02:30 on 14 March 2027 does not exist in New York (02:00 -> 03:00): it becomes 03:30 EDT
		Recurrence.Series gap = timed(NEW_YORK, "2027-03-13T02:30", 30);
		List<Recurrence.Occurrence> days = all("FREQ=DAILY;COUNT=3", gap, 10);
		assertThat(days.get(1).start()).isEqualTo(Instant.parse("2027-03-14T07:30:00Z"));
		assertThat(days.get(1).start().atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
		// 01:30 on 7 November 2027 happens twice: the first (EDT) is taken
		Recurrence.Series overlap = timed(NEW_YORK, "2027-11-06T01:30", 30);
		assertThat(all("FREQ=DAILY;COUNT=2", overlap, 10).get(1).start()).isEqualTo(Instant.parse("2027-11-07T05:30:00Z"));
	}

	@Test
	void addisAbabaHasNoDaylightSavingSoTheUtcTimeNeverMoves() {
		Recurrence.Series series = timed(ADDIS, "2026-01-05T09:00", 60);
		for (Recurrence.Occurrence o : all("FREQ=WEEKLY;BYDAY=MO;COUNT=60", series, 100)) {
			assertThat(o.start().atZone(java.time.ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.of(6, 0));
		}
	}

	// --- all-day ------------------------------------------------------------------------------

	@Test
	void allDayEventsAreDatesAndMultiDayOnesKeepTheirLength() {
		Recurrence.Series series = Recurrence.Series.allDay(NEW_YORK, LocalDate.of(2027, 3, 12), LocalDate.of(2027, 3, 14));
		List<Recurrence.Occurrence> occurrences = all("FREQ=MONTHLY;COUNT=2", series, 10);
		// 12 to 14 March 2027 spans the spring-forward day: 71 hours, not 72, and still three calendar days
		assertThat(Duration.between(occurrences.get(0).start(), occurrences.get(0).end())).isEqualTo(Duration.ofHours(71));
		assertThat(occurrences.get(0).start()).isEqualTo(Instant.parse("2027-03-12T05:00:00Z"));
		assertThat(occurrences.get(0).end()).isEqualTo(Instant.parse("2027-03-15T04:00:00Z"));
		assertThat(occurrences.get(1).day()).isEqualTo(LocalDate.of(2027, 4, 12));
		assertThat(occurrences.get(1).end()).isEqualTo(ZonedDateTime.of(2027, 4, 15, 0, 0, 0, 0, NEW_YORK).toInstant());
	}

}
