package com.nemaz.school.calendar.application;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.nemaz.school.common.error.ApiException;

/**
 * The recurrence of events: a deliberately small subset of RFC 5545 RRULE, parsed and expanded here (no library: the
 * subset is a few dozen lines, and the well-known Java libraries either predate Java 25 or pull in a calendar stack that
 * this product does not use).
 *
 * <p>
 * <b>Supported:</b> {@code FREQ=DAILY|WEEKLY|MONTHLY}, {@code INTERVAL} (1 to {@value #MAX_INTERVAL}), {@code BYDAY} (plain
 * weekday codes, WEEKLY only), {@code BYMONTHDAY} (1 to 31, MONTHLY only), and one of {@code UNTIL} (a date
 * {@code 20271231}, inclusive, in the event's zone, or a UTC instant {@code 20271231T210000Z}, inclusive) or
 * {@code COUNT} (1 to {@value #MAX_COUNT}). Weeks start on Monday ({@code WKST=MO} is accepted, other values are not).
 * Anything else is rejected with {@code event.recurrence_unsupported}; text that is not a rule at all with
 * {@code event.recurrence_invalid}.
 *
 * <p>
 * <b>Semantics:</b> the first occurrence is always the event's own start (a rule that does not match it is rejected with
 * {@code event.recurrence_start_mismatch}: no hidden "first real occurrence"). Occurrences keep their <em>wall-clock</em>
 * time in the event's zone across daylight-saving changes (a 09:00 meeting stays at 09:00 local; a time that does not
 * exist on a spring-forward day moves forward by the gap, one that exists twice takes the first). The duration of an
 * occurrence is the event's (in exact time for timed events, in days for all-day ones). A monthly rule on the 31st skips
 * the months that have no 31st, as RFC 5545 says. COUNT counts occurrences, exceptions (cancelled occurrences) included.
 * Expansion is always for a window and bounded.
 */
final class Recurrence {

	static final int MAX_INTERVAL = 99;

	static final int MAX_COUNT = 1000;

	/** The furthest UNTIL may be after the start (also bounds the work of finding the end of a finite series). */
	static final int MAX_YEARS = 10;

	/** Hard stop of one expansion, whatever the rule (guards against runaway loops, far above any legal series). */
	private static final int MAX_STEPS = 20_000;

	private static final DateTimeFormatter UNTIL_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private static final DateTimeFormatter UNTIL_INSTANT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

	private static final Map<String, DayOfWeek> DAYS = Map.of("MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE",
			DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY, "SU",
			DayOfWeek.SUNDAY);

	enum Freq {

		DAILY, WEEKLY, MONTHLY

	}

	private final Freq freq;

	private final int interval;

	private final EnumSet<DayOfWeek> byDay;

	private final TreeSet<Integer> byMonthDay;

	private final LocalDate untilDate;

	private final Instant untilInstant;

	private final Integer count;

	private final String text;

	private Recurrence(Freq freq, int interval, EnumSet<DayOfWeek> byDay, TreeSet<Integer> byMonthDay, LocalDate untilDate,
			Instant untilInstant, Integer count, String text) {
		this.freq = freq;
		this.interval = interval;
		this.byDay = byDay;
		this.byMonthDay = byMonthDay;
		this.untilDate = untilDate;
		this.untilInstant = untilInstant;
		this.count = count;
		this.text = text;
	}

	/** The rule in the canonical form it is stored in (upper case, fixed part order, no {@code RRULE:} prefix). */
	String text() {
		return this.text;
	}

	boolean finite() {
		return this.count != null || this.untilDate != null || this.untilInstant != null;
	}

	// --- parsing ------------------------------------------------------------------------------

	/** Parses and validates a rule (400 {@code event.recurrence_invalid} / {@code event.recurrence_unsupported}). */
	static Recurrence parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
		String rule = raw.strip().toUpperCase(Locale.ROOT);
		if (rule.startsWith("RRULE:")) {
			rule = rule.substring("RRULE:".length());
		}
		if (rule.contains("\n") || rule.contains("\r") || rule.contains("DTSTART") || rule.contains("EXDATE")) {
			throw ApiException.badRequest("event.recurrence_unsupported");
		}
		Freq freq = null;
		int interval = 1;
		EnumSet<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
		TreeSet<Integer> byMonthDay = new TreeSet<>();
		LocalDate untilDate = null;
		Instant untilInstant = null;
		Integer count = null;
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (String part : rule.split(";", -1)) {
			int eq = part.indexOf('=');
			if (eq <= 0 || eq == part.length() - 1) {
				throw ApiException.badRequest("event.recurrence_invalid");
			}
			String key = part.substring(0, eq);
			String value = part.substring(eq + 1);
			if (!seen.add(key)) {
				throw ApiException.badRequest("event.recurrence_invalid");
			}
			switch (key) {
				case "FREQ" -> {
					try {
						freq = Freq.valueOf(value);
					}
					catch (IllegalArgumentException ex) {
						throw ApiException.badRequest(
								List.of("SECONDLY", "MINUTELY", "HOURLY", "YEARLY").contains(value) ? "event.recurrence_unsupported"
										: "event.recurrence_invalid");
					}
				}
				case "INTERVAL" -> interval = number(value, 1, MAX_INTERVAL);
				case "COUNT" -> count = number(value, 1, MAX_COUNT);
				case "UNTIL" -> {
					if (value.length() == 8) {
						untilDate = parseDate(value);
					}
					else {
						untilInstant = parseInstant(value);
					}
				}
				case "BYDAY" -> {
					for (String code : value.split(",", -1)) {
						DayOfWeek day = DAYS.get(code);
						if (day == null) {
							// ordinals such as 2MO or -1FR are valid RFC 5545 but not supported
							throw ApiException.badRequest(code.matches("[+-]?[0-9]+[A-Z]{2}") ? "event.recurrence_unsupported"
									: "event.recurrence_invalid");
						}
						byDay.add(day);
					}
				}
				case "BYMONTHDAY" -> {
					for (String day : value.split(",", -1)) {
						byMonthDay.add(number(day, 1, 31, "event.recurrence_unsupported"));
					}
				}
				case "WKST" -> {
					if (!"MO".equals(value)) {
						throw ApiException.badRequest("event.recurrence_unsupported");
					}
				}
				case "BYSETPOS", "BYMONTH", "BYWEEKNO", "BYYEARDAY", "BYHOUR", "BYMINUTE", "BYSECOND", "RSCALE", "SKIP" ->
					throw ApiException.badRequest("event.recurrence_unsupported");
				default -> throw ApiException.badRequest("event.recurrence_invalid");
			}
		}
		if (freq == null) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
		if (count != null && (untilDate != null || untilInstant != null)) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
		if (!byDay.isEmpty() && freq != Freq.WEEKLY) {
			throw ApiException.badRequest("event.recurrence_unsupported");
		}
		if (!byMonthDay.isEmpty() && freq != Freq.MONTHLY) {
			throw ApiException.badRequest("event.recurrence_unsupported");
		}
		return new Recurrence(freq, interval, byDay, byMonthDay, untilDate, untilInstant, count,
				canonical(freq, interval, byDay, byMonthDay, untilDate, untilInstant, count));
	}

	private static String canonical(Freq freq, int interval, EnumSet<DayOfWeek> byDay, TreeSet<Integer> byMonthDay,
			LocalDate untilDate, Instant untilInstant, Integer count) {
		StringBuilder out = new StringBuilder("FREQ=").append(freq);
		if (interval != 1) {
			out.append(";INTERVAL=").append(interval);
		}
		if (!byDay.isEmpty()) {
			List<String> codes = new ArrayList<>();
			for (DayOfWeek day : byDay) {
				codes.add(DAYS.entrySet().stream().filter((e) -> e.getValue() == day).findFirst().orElseThrow().getKey());
			}
			out.append(";BYDAY=").append(String.join(",", codes));
		}
		if (!byMonthDay.isEmpty()) {
			out.append(";BYMONTHDAY=").append(String.join(",", byMonthDay.stream().map(String::valueOf).toList()));
		}
		if (untilDate != null) {
			out.append(";UNTIL=").append(UNTIL_DATE.format(untilDate));
		}
		if (untilInstant != null) {
			out.append(";UNTIL=").append(UNTIL_INSTANT.format(untilInstant.atOffset(ZoneOffset.UTC)));
		}
		if (count != null) {
			out.append(";COUNT=").append(count);
		}
		return out.toString();
	}

	private static int number(String value, int min, int max) {
		return number(value, min, max, "event.recurrence_invalid");
	}

	private static int number(String value, int min, int max, String outOfRangeCode) {
		try {
			int n = Integer.parseInt(value);
			if (n < min || n > max) {
				throw ApiException.badRequest(outOfRangeCode);
			}
			return n;
		}
		catch (NumberFormatException ex) {
			throw ApiException.badRequest(value.startsWith("-") ? "event.recurrence_unsupported" : "event.recurrence_invalid");
		}
	}

	private static LocalDate parseDate(String value) {
		try {
			return LocalDate.parse(value, UNTIL_DATE);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
	}

	private static Instant parseInstant(String value) {
		try {
			return LocalDateTime.parse(value, UNTIL_INSTANT).toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
	}

	// --- validation against the event --------------------------------------------------------

	/**
	 * Checks the rule against the event's first day: it must match it, and UNTIL must not precede it or lie more than
	 * {@value #MAX_YEARS} years after it.
	 */
	void validateFor(LocalDate firstDay, ZoneId zone, LocalTime time) {
		if (!matches(firstDay)) {
			throw ApiException.badRequest("event.recurrence_start_mismatch");
		}
		LocalDate last = (this.untilDate != null) ? this.untilDate
				: (this.untilInstant != null) ? this.untilInstant.atZone(zone).toLocalDate() : null;
		if (last != null && last.isBefore(firstDay)) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
		if (last != null && last.isAfter(firstDay.plusYears(MAX_YEARS))) {
			throw ApiException.badRequest("event.recurrence_too_long");
		}
		if (this.untilInstant != null && time != null && !instantOf(firstDay, time, zone).isBefore(this.untilInstant.plusSeconds(1))) {
			throw ApiException.badRequest("event.recurrence_invalid");
		}
	}

	private boolean matches(LocalDate day) {
		return switch (this.freq) {
			case DAILY -> true;
			case WEEKLY -> this.byDay.isEmpty() || this.byDay.contains(day.getDayOfWeek());
			case MONTHLY -> this.byMonthDay.isEmpty() || this.byMonthDay.contains(day.getDayOfMonth());
		};
	}

	// --- expansion ----------------------------------------------------------------------------

	/**
	 * One occurrence of a series: its day in the event's zone (the first day for a multi-day all-day event), the instant of
	 * its start and of its end (end exclusive for all-day events: the start of the day after the last one).
	 */
	record Occurrence(LocalDate day, Instant start, Instant end) {
	}

	/**
	 * The shape of a series: the zone, the first day, the time of day (null for all-day) and the length: exact for timed
	 * events, in days for all-day ones.
	 */
	record Series(ZoneId zone, LocalDate firstDay, LocalTime time, Duration duration, int days) {

		static Series timed(ZoneId zone, Instant start, Instant end) {
			ZonedDateTime local = start.atZone(zone);
			return new Series(zone, local.toLocalDate(), local.toLocalTime(), Duration.between(start, end), 0);
		}

		static Series allDay(ZoneId zone, LocalDate firstDay, LocalDate lastDay) {
			return new Series(zone, firstDay, null, Duration.ZERO, (int) (lastDay.toEpochDay() - firstDay.toEpochDay()) + 1);
		}

		boolean allDay() {
			return this.time == null;
		}

		Occurrence at(LocalDate day) {
			if (allDay()) {
				return new Occurrence(day, day.atStartOfDay(this.zone).toInstant(),
						day.plusDays(this.days).atStartOfDay(this.zone).toInstant());
			}
			Instant start = instantOf(day, this.time, this.zone);
			return new Occurrence(day, start, start.plus(this.duration));
		}

	}

	static Instant instantOf(LocalDate day, LocalTime time, ZoneId zone) {
		return ZonedDateTime.of(day, time, zone).toInstant();
	}

	/**
	 * The occurrences that overlap {@code [from, to)} in order, at most {@code max}: an occurrence overlaps when it
	 * starts before {@code to} and ends after {@code from} (a zero-length one when it starts at or after {@code from}).
	 * {@code truncated[0]} is set when the cap cut the list short.
	 */
	List<Occurrence> expand(Series series, Instant from, Instant to, int max, boolean[] truncated) {
		List<Occurrence> out = new ArrayList<>();
		int produced = 0;
		int steps = 0;
		for (LocalDate day : days(series)) {
			if (++steps > MAX_STEPS) {
				break;
			}
			Occurrence occurrence = series.at(day);
			produced++;
			if (this.count != null && produced > this.count) {
				break;
			}
			if (past(occurrence)) {
				break;
			}
			if (!occurrence.start().isBefore(to)) {
				break;
			}
			if (occurrence.end().isAfter(from) || !occurrence.start().isBefore(from)) {
				if (out.size() >= max) {
					if (truncated != null) {
						truncated[0] = true;
					}
					break;
				}
				out.add(occurrence);
			}
		}
		return out;
	}

	/** The last occurrence of a finite series, or null for an endless one. */
	Occurrence last(Series series) {
		if (!finite()) {
			return null;
		}
		Occurrence last = null;
		int produced = 0;
		int steps = 0;
		for (LocalDate day : days(series)) {
			if (++steps > MAX_STEPS) {
				break;
			}
			Occurrence occurrence = series.at(day);
			produced++;
			if ((this.count != null && produced > this.count) || past(occurrence)) {
				break;
			}
			last = occurrence;
		}
		return last;
	}

	private boolean past(Occurrence occurrence) {
		if (this.untilDate != null) {
			return occurrence.day().isAfter(this.untilDate);
		}
		return this.untilInstant != null && occurrence.start().isAfter(this.untilInstant);
	}

	/** The days of the series in order, lazily, from the first day on (rules only, no bounds). */
	private Iterable<LocalDate> days(Series series) {
		LocalDate first = series.firstDay();
		return () -> new java.util.Iterator<>() {

			private final List<LocalDate> batch = new ArrayList<>();

			private int index;

			private long period;

			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public LocalDate next() {
				while (this.index >= this.batch.size()) {
					if (this.period > MAX_STEPS) {
						throw new IllegalStateException("Runaway recurrence");
					}
					this.batch.clear();
					this.index = 0;
					fill(first, this.period++, this.batch);
				}
				return this.batch.get(this.index++);
			}

		};
	}

	/** The matching days of the {@code period}-th step of the rule (a day, a week or a month), at or after {@code first}. */
	private void fill(LocalDate first, long period, List<LocalDate> into) {
		long step = period * this.interval;
		switch (this.freq) {
			case DAILY -> into.add(first.plusDays(step));
			case WEEKLY -> {
				LocalDate monday = first.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
					.plusWeeks(step);
				EnumSet<DayOfWeek> days = this.byDay.isEmpty() ? EnumSet.of(first.getDayOfWeek()) : this.byDay;
				for (DayOfWeek day : days) {
					LocalDate date = monday.plusDays(day.getValue() - 1L);
					if (!date.isBefore(first)) {
						into.add(date);
					}
				}
			}
			case MONTHLY -> {
				YearMonth month = YearMonth.from(first).plusMonths(step);
				TreeSet<Integer> days = this.byMonthDay.isEmpty() ? new TreeSet<>(List.of(first.getDayOfMonth()))
						: this.byMonthDay;
				for (int day : days) {
					if (day <= month.lengthOfMonth()) {
						LocalDate date = month.atDay(day);
						if (!date.isBefore(first)) {
							into.add(date);
						}
					}
				}
			}
		}
	}

}
