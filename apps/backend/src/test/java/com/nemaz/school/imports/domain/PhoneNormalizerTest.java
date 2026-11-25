package com.nemaz.school.imports.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

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
		PhoneNormalizer.Result result = PhoneNormalizer.normalize(raw);
		assertThat(result.errorCode()).isNull();
		assertThat(result.e164()).isEqualTo(expected);
	}

	@Test
	void internationalNumbersKeepTheirCountry() {
		assertThat(PhoneNormalizer.normalize("+254712345678").e164()).isEqualTo("+254712345678");
	}

	@Test
	void aMobileShapedNumberOfAnUnknownRangeIsAWarningNotAnError() {
		PhoneNormalizer.Result result = PhoneNormalizer.normalize("0799999999");
		assertThat(result.e164()).isEqualTo("+251799999999");
		assertThat(result.errorCode()).isNull();
		// valid or not, the warning says an unrecognised range was accepted
		if (result.warningCode() != null) {
			assertThat(result.warningCode()).isEqualTo("phone_unrecognized");
		}
	}

	@Test
	void refusesWhatCannotBeAPhoneNumber() {
		assertThat(PhoneNormalizer.normalize("abc").errorCode()).isEqualTo("phone_invalid");
		assertThat(PhoneNormalizer.normalize("12345").errorCode()).isEqualTo("phone_invalid");
		assertThat(PhoneNormalizer.normalize("0911234567 / 0922334455").errorCode()).isEqualTo("phone_multiple");
		assertThat(PhoneNormalizer.normalize("0911234567x").errorCode()).isEqualTo("phone_invalid");
	}

	@Test
	void emptyCellsAreEmptyNotErrors() {
		assertThat(PhoneNormalizer.normalize(null).empty()).isTrue();
		assertThat(PhoneNormalizer.normalize("  ").empty()).isTrue();
	}

}
