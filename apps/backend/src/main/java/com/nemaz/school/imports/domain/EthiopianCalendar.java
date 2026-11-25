package com.nemaz.school.imports.domain;

import java.time.LocalDate;

/**
 * Gregorian and Ethiopian civil dates through Julian Day Numbers: a port of {@code packages/calendar/src/convert.ts}
 * (the TypeScript package stays the source of truth; {@code CalendarParityTest} sweeps every day of 1900-2100
 * against a fixture generated from it). The JDK has no Ethiopic chronology.
 * <p>
 * Twelve months of 30 days and Pagume of 5 days (6 in a leap year, when {@code year % 4 == 3}). Epoch: 1 Meskerem 1
 * is JDN 1724221.
 */
public final class EthiopianCalendar {

	private static final long EPOCH_JDN = 1724221L;

	private EthiopianCalendar() {
	}

	/** A civil date with a 1-based month. */
	public record Civil(int year, int month, int day) {
	}

	public static boolean isLeapYear(int ethiopianYear) {
		return Math.floorMod(ethiopianYear, 4) == 3;
	}

	public static int daysInMonth(int year, int month) {
		return (month == 13) ? (isLeapYear(year) ? 6 : 5) : 30;
	}

	public static boolean isValid(int year, int month, int day) {
		return year >= 1 && month >= 1 && month <= 13 && day >= 1 && day <= daysInMonth(year, month);
	}

	public static long gregorianToJdn(int year, int month, int day) {
		long a = Math.floorDiv(14 - month, 12);
		long y = year + 4800 - a;
		long m = month + 12 * a - 3;
		return day + Math.floorDiv(153 * m + 2, 5) + 365 * y + Math.floorDiv(y, 4) - Math.floorDiv(y, 100)
				+ Math.floorDiv(y, 400) - 32045;
	}

	public static Civil jdnToGregorian(long jdn) {
		long a = jdn + 32044;
		long b = Math.floorDiv(4 * a + 3, 146097);
		long c = a - Math.floorDiv(146097 * b, 4);
		long d = Math.floorDiv(4 * c + 3, 1461);
		long e = c - Math.floorDiv(1461 * d, 4);
		long m = Math.floorDiv(5 * e + 2, 153);
		return new Civil((int) (100 * b + d - 4800 + Math.floorDiv(m, 10)), (int) (m + 3 - 12 * Math.floorDiv(m, 10)),
				(int) (e - Math.floorDiv(153 * m + 2, 5) + 1));
	}

	public static long ethiopianToJdn(int year, int month, int day) {
		return EPOCH_JDN + 365L * (year - 1) + Math.floorDiv(year, 4) + 30L * (month - 1) + day - 1;
	}

	public static Civil jdnToEthiopian(long jdn) {
		// Cycles of four years counted from the non-leap year 0, so a cycle's leap day is its last day.
		long offset = jdn - (EPOCH_JDN - 365);
		long r = Math.floorMod(offset, 1461L);
		long n = Math.floorMod(r, 365L) + 365 * Math.floorDiv(r, 1460L);
		return new Civil((int) (4 * Math.floorDiv(offset, 1461L) + Math.floorDiv(r, 365L) - Math.floorDiv(r, 1460L)),
				(int) (Math.floorDiv(n, 30L) + 1), (int) (Math.floorMod(n, 30L) + 1));
	}

	public static Civil toEthiopian(LocalDate date) {
		return jdnToEthiopian(gregorianToJdn(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
	}

	/** The Gregorian date of an Ethiopian one; throws {@link IllegalArgumentException} when the date does not exist. */
	public static LocalDate toGregorian(int year, int month, int day) {
		if (!isValid(year, month, day)) {
			throw new IllegalArgumentException("Invalid Ethiopian date: " + year + "-" + month + "-" + day);
		}
		Civil g = jdnToGregorian(ethiopianToJdn(year, month, day));
		return LocalDate.of(g.year(), g.month(), g.day());
	}

}
