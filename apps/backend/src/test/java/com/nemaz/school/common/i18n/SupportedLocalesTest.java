package com.nemaz.school.common.i18n;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedLocalesTest {

	@Test
	void detectsTheMessageBundlesOnTheClasspathWithEnglishFirst() {
		SupportedLocales locales = new SupportedLocales(List.of());
		assertThat(locales.codes()).startsWith("en").contains("am");
		assertThat(locales.locales()).contains(Locale.ENGLISH, Locale.forLanguageTag("am"));
	}

	@Test
	void anOperatorMayRestrictTheDetectedSetButEnglishStays() {
		assertThat(new SupportedLocales(List.of("en")).codes()).containsExactly("en");
		assertThat(new SupportedLocales(List.of("am")).codes()).containsExactly("en", "am");
		assertThat(new SupportedLocales(List.of("xx")).codes()).containsExactly("en");
	}

	@Test
	void aRecipientGetsTheirLanguageWhenThereAreMessagesForItElseEnglish() {
		SupportedLocales locales = SupportedLocales.of("am", "sw");
		assertThat(locales.codes()).containsExactly("en", "am", "sw");
		assertThat(locales.localeOf("sw")).isEqualTo(Locale.forLanguageTag("sw"));
		assertThat(locales.localeOf("SW")).isEqualTo(Locale.forLanguageTag("sw"));
		assertThat(locales.localeOf("om")).isEqualTo(Locale.ENGLISH);
		assertThat(locales.localeOf(null)).isEqualTo(Locale.ENGLISH);
		assertThat(locales.supports("am")).isTrue();
		assertThat(locales.supports("om")).isFalse();
	}

}
