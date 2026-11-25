package com.nemaz.school.imports.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DateUtil;

/**
 * Dates as they appear in rosters. Accepted: ISO {@code 2012-05-14} (also with {@code /} or {@code .}), day first
 * {@code 14/05/2012} and {@code 14.5.2012}, an optional time after the date, and an Excel serial number
 * ({@code 41043}; the 1900 leap year bug and the 1904 workbook flag are handled by POI's {@code DateUtil}).
 * Never {@code MM/dd}: an ambiguous order is not guessed. Two-digit years are refused.
 * <p>
 * Text dates are Gregorian unless the import's date system is ETHIOPIAN; then they are read as Ethiopian year, month
 * (1-13) and day and converted. Real spreadsheet date cells and Excel serials are instants of the Gregorian
 * calendar whatever the option says.
 */
public final class DateParser {

	private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");

	private static final Pattern DAY_FIRST = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$");

	private static final Pattern TWO_DIGIT_YEAR = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2})$");

	private static final Pattern SERIAL = Pattern.compile("^(\\d{5,6})(\\.\\d+)?$");

	private static final Pattern TIME_TAIL = Pattern.compile("^(.*?\\d)[ T]+\\d{1,2}:\\d{2}.*$");

	public enum DateSystem {

		GREGORIAN, ETHIOPIAN

	}

	/** {@code date1904}: the workbook uses the 1904 date system (Excel serials count from 1904). */
	public record Options(DateSystem system, boolean date1904) {

		public static final Options GREGORIAN = new Options(DateSystem.GREGORIAN, false);

	}

	/** Either a date or an error code (the message {@code import.row.<code>}). */
	public record Result(LocalDate date, String errorCode) {

		public boolean empty() {
			return this.date == null && this.errorCode == null;
		}

	}

	private DateParser() {
	}

	/** {@code nativeDate}: the cell is a spreadsheet date, already converted to ISO text (Gregorian). */
	public static Result parse(String raw, boolean nativeDate, Options options) {
		if (raw == null || raw.isBlank()) {
			return new Result(null, null);
		}
		String text = Digits.toAscii(raw).strip();
		Matcher time = TIME_TAIL.matcher(text);
		if (time.matches()) {
			text = time.group(1);
		}
		Matcher serial = SERIAL.matcher(text);
		if (serial.matches()) {
			try {
				LocalDateTime when = DateUtil.getLocalDateTime(Double.parseDouble(text), options.date1904());
				return new Result(when.toLocalDate(), null);
			}
			catch (RuntimeException ex) {
				return new Result(null, "date_invalid");
			}
		}
		if (TWO_DIGIT_YEAR.matcher(text).matches()) {
			return new Result(null, "date_two_digit_year");
		}
		int year;
		int month;
		int day;
		Matcher ymd = YEAR_FIRST.matcher(text);
		Matcher dmy = DAY_FIRST.matcher(text);
		if (ymd.matches()) {
			year = Integer.parseInt(ymd.group(1));
			month = Integer.parseInt(ymd.group(2));
			day = Integer.parseInt(ymd.group(3));
		}
		else if (dmy.matches()) {
			day = Integer.parseInt(dmy.group(1));
			month = Integer.parseInt(dmy.group(2));
			year = Integer.parseInt(dmy.group(3));
		}
		else {
			return new Result(null, "date_unrecognized");
		}
		if (!nativeDate && options.system() == DateSystem.ETHIOPIAN) {
			if (!EthiopianCalendar.isValid(year, month, day)) {
				return new Result(null, "date_invalid");
			}
			return new Result(EthiopianCalendar.toGregorian(year, month, day), null);
		}
		if (month == 13 && !nativeDate) {
			return new Result(null, "date_month13");
		}
		try {
			return new Result(LocalDate.of(year, month, day), null);
		}
		catch (java.time.DateTimeException ex) {
			return new Result(null, "date_invalid");
		}
	}

}
