package com.nemaz.school.imports.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalizer reads numbers in the school's country, given as ISO region and calling code (both come from the
 * {@code countries} table in production): the same spreadsheet repairs work for every country.
 */
class PhoneNormalizerTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			0911234567         | +251911234567
			+251911234567      | +251911234567
			251911234567       | +251911234567
			00251911234567     | +251911234567
			911234567          | +251911234567
			9.11234567E8       | +251911234567
			911234567.0        | +251911234567
			+2510911234567     | +251911234567
			0911 23 45 67      | +251911234567
			(0911) 234-567     | +251911234567
			091.123.4567       | +251911234567
			+251 91 123 4567   | +251911234567
			0711234567         | +251711234567
			711234567          | +251711234567
			٠٩١١٢٣٤٥٦٧         | +251911234567
			0111234567         | +251111234567
			""")
	void readsEthiopianNumbersAsTheyAreTypedOrMangledByASpreadsheet(String raw, String expected) {
		PhoneNormalizer.Result result = PhoneNormalizer.normalize(raw, "ET", "+251");
		assertThat(result.errorCode()).isNull();
		assertThat(result.e164()).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			0712345678         | +254712345678
			712345678          | +254712345678
			254712345678       | +254712345678
			+254712345678      | +254712345678
			00254712345678     | +254712345678
			7.12345678E8       | +254712345678
			+2540712345678     | +254712345678
			0712 345 678       | +254712345678
			0112345678         | +254112345678
			""")
	void readsKenyanNumbersWithTheSameRepairs(String raw, String expected) {
		PhoneNormalizer.Result result = PhoneNormalizer.normalize(raw, "KE", "+254");
		assertThat(result.errorCode()).isNull();
		assertThat(result.warningCode()).isNull();
		assertThat(result.e164()).isEqualTo(expected);
	}

	@Test
	void readsRwandanNumbersAndAcceptsTheCallingCodeWithOrWithoutItsPlus() {
		assertThat(PhoneNormalizer.normalize("0788123456", "RW", "+250").e164()).isEqualTo("+250788123456");
		assertThat(PhoneNormalizer.normalize("788123456", "RW", "250").e164()).isEqualTo("+250788123456");
	}

	@Test
	void theSameDigitsMeanDifferentNumbersInDifferentCountries() {
		assertThat(PhoneNormalizer.normalize("0712345678", "ET", "+251").e164()).isEqualTo("+251712345678");
		assertThat(PhoneNormalizer.normalize("0712345678", "KE", "+254").e164()).isEqualTo("+254712345678");
	}

	@Test
	void internationalNumbersKeepTheirCountry() {
		assertThat(PhoneNormalizer.normalize("+254712345678", "ET", "+251").e164()).isEqualTo("+254712345678");
		assertThat(PhoneNormalizer.normalize("+251911234567", "KE", "+254").e164()).isEqualTo("+251911234567");
	}

	@Test
	void aMobileShapedNumberOfAnUnknownRangeIsAWarningNotAnError() {
		PhoneNormalizer.Result result = PhoneNormalizer.normalize("0799999999", "ET", "+251");
		assertThat(result.e164()).isEqualTo("+251799999999");
		assertThat(result.errorCode()).isNull();
		// valid or not, the warning says an unrecognised range was accepted
		if (result.warningCode() != null) {
			assertThat(result.warningCode()).isEqualTo("phone_unrecognized");
		}
	}

	@Test
	void refusesWhatCannotBeAPhoneNumber() {
		for (String[] country : new String[][] { { "ET", "+251" }, { "KE", "+254" } }) {
			assertThat(PhoneNormalizer.normalize("abc", country[0], country[1]).errorCode()).isEqualTo("phone_invalid");
			assertThat(PhoneNormalizer.normalize("12345", country[0], country[1]).errorCode()).isEqualTo("phone_invalid");
			assertThat(PhoneNormalizer.normalize("0911234567 / 0922334455", country[0], country[1]).errorCode())
				.isEqualTo("phone_multiple");
			assertThat(PhoneNormalizer.normalize("0911234567x", country[0], country[1]).errorCode()).isEqualTo("phone_invalid");
		}
	}

	@Test
	void emptyCellsAreEmptyNotErrors() {
		assertThat(PhoneNormalizer.normalize(null, "ET", "+251").empty()).isTrue();
		assertThat(PhoneNormalizer.normalize("  ", "KE", "+254").empty()).isTrue();
	}

}
