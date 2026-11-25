package com.nemaz.school.imports.domain;

import java.time.LocalDate;

import com.nemaz.school.imports.domain.DateParser.DateSystem;
import com.nemaz.school.imports.domain.DateParser.Options;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DateParserTest {

	private static final Options ETHIOPIAN = new Options(DateSystem.ETHIOPIAN, false);

	private static LocalDate parse(String raw) {
		DateParser.Result r = DateParser.parse(raw, false, Options.GREGORIAN);
		assertThat(r.errorCode()).as(raw).isNull();
		return r.date();
	}

	@Test
	void readsIsoAndDayFirstTextDates() {
		assertThat(parse("2012-05-14")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse("2012/5/4")).isEqualTo(LocalDate.of(2012, 5, 4));
		assertThat(parse("14/05/2012")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse("4.5.2012")).isEqualTo(LocalDate.of(2012, 5, 4));
		assertThat(parse("14-05-2012")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse("2012-05-14 00:00:00")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse("2012-05-14T08:30")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse(" ٢٠١٢-٠٥-١٤ ")).as("Arabic-Indic digits").isEqualTo(LocalDate.of(2012, 5, 14));
	}

	@Test
	void neverGuessesMonthFirstOrTwoDigitYears() {
		// 05/14/2012 would be month 14: it is an error, not May 14
		assertThat(DateParser.parse("05/14/2012", false, Options.GREGORIAN).errorCode()).isEqualTo("date_invalid");
		// an ambiguous 03/04/2012 is read day first: 3 April
		assertThat(parse("03/04/2012")).isEqualTo(LocalDate.of(2012, 4, 3));
		assertThat(DateParser.parse("14/05/12", false, Options.GREGORIAN).errorCode()).isEqualTo("date_two_digit_year");
		assertThat(DateParser.parse("May 14 2012", false, Options.GREGORIAN).errorCode()).isEqualTo("date_unrecognized");
		assertThat(DateParser.parse("2012-02-30", false, Options.GREGORIAN).errorCode()).isEqualTo("date_invalid");
	}

	@Test
	void excelSerialNumbersIncludingTheLeapYearBugAnd1904() {
		// 41043 is 14 May 2012 in the 1900 date system
		assertThat(parse("41043")).isEqualTo(LocalDate.of(2012, 5, 14));
		assertThat(parse("41043.0")).isEqualTo(LocalDate.of(2012, 5, 14));
		// five digits are needed to tell a serial from a year, so the 1900 leap year bug (serials 59-61) is POI's
		// business: serial 61 is 1 March 1900, 59 is 28 February (60 is the phantom 29 February)
		assertThat(org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(61, false).toLocalDate())
			.isEqualTo(LocalDate.of(1900, 3, 1));
		assertThat(org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(59, false).toLocalDate())
			.isEqualTo(LocalDate.of(1900, 2, 28));
		assertThat(parse("32874")).as("1 January 1990").isEqualTo(LocalDate.of(1990, 1, 1));
		// 1904 system: the same serial is four years and a day later
		DateParser.Result r1904 = DateParser.parse("41043", false, new Options(DateSystem.GREGORIAN, true));
		assertThat(r1904.date()).isEqualTo(LocalDate.of(2016, 5, 15));
		// serials are Gregorian whatever the date system says
		assertThat(DateParser.parse("41043", false, ETHIOPIAN).date()).isEqualTo(LocalDate.of(2012, 5, 14));
	}

	@Test
	void ethiopianTextDatesAreConverted() {
		// 6 Meskerem 2005 E.C. is 16 September 2012
		assertThat(DateParser.parse("2005-01-06", false, ETHIOPIAN).date()).isEqualTo(LocalDate.of(2012, 9, 16));
		// day first, month 13 (Pagume has 5 days, 6 in a leap year: 2003 E.C. is one)
		assertThat(DateParser.parse("06/13/2003", false, ETHIOPIAN).date())
			.isEqualTo(LocalDate.of(2011, 9, 11));
		assertThat(DateParser.parse("05/13/2004", false, ETHIOPIAN).errorCode()).isNull();
		assertThat(DateParser.parse("06/13/2004", false, ETHIOPIAN).errorCode()).as("Pagume 6 in a non-leap year").isEqualTo("date_invalid");
		assertThat(DateParser.parse("2004-14-01", false, ETHIOPIAN).errorCode()).isEqualTo("date_invalid");
	}

	@Test
	void monthThirteenInTheGregorianCalendarIsExplained() {
		assertThat(DateParser.parse("2005-13-02", false, Options.GREGORIAN).errorCode()).isEqualTo("date_month13");
	}

	@Test
	void aSpreadsheetDateCellIsGregorianEvenWhenTheFileIsEthiopian() {
		DateParser.Result r = DateParser.parse("2012-05-14", true, ETHIOPIAN);
		assertThat(r.date()).isEqualTo(LocalDate.of(2012, 5, 14));
	}

	@Test
	void emptyIsEmpty() {
		assertThat(DateParser.parse(" ", false, Options.GREGORIAN).empty()).isTrue();
		assertThat(DateParser.parse(null, false, Options.GREGORIAN).empty()).isTrue();
	}

}
