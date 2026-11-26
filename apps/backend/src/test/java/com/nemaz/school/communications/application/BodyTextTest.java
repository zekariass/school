package com.nemaz.school.communications.application;

import com.nemaz.school.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyTextTest {

	@Test
	void markupIsJustCharacters() {
		String text = "<script>alert(1)</script> <b>bold</b> & \"quoted\" 5 < 6 > 4";
		assertThat(BodyText.required(text, 5000, "body")).isEqualTo(text);
	}

	@Test
	void lineEndsAreNormalizedAndControlCharactersDropped() {
		assertThat(BodyText.required("a\r\nb\rc\td\u0000e\u0007f", 5000, "body")).isEqualTo("a\nb\nc\tdef");
	}

	@Test
	void directionOverridesAndZeroWidthCharactersAreDropped() {
		assertThat(BodyText.required("Pay‮ gnisrever​ now﻿⁦", 200, "title")).isEqualTo("Pay gnisrever now");
	}

	@Test
	void amharicAndEmojiSurvive() {
		assertThat(BodyText.required("ሰላም 👋 ዓለም", 200, "title")).isEqualTo("ሰላም 👋 ዓለም");
	}

	@Test
	void lengthIsCountedInCharactersNotUnits() {
		assertThat(BodyText.required("👋".repeat(200), 200, "title")).hasSize(400);
		assertThatThrownBy(() -> BodyText.required("👋".repeat(201), 200, "title")).isInstanceOf(ApiException.class)
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("announcement.title_too_long");
	}

	@Test
	void blankIsRefusedWhereRequiredAndNullWhereOptional() {
		assertThatThrownBy(() -> BodyText.required("  ​ ", 200, "title")).isInstanceOf(ApiException.class)
			.extracting((e) -> ((ApiException) e).code())
			.isEqualTo("announcement.title_required");
		assertThat(BodyText.optional("   ", 500, "reason")).isNull();
		assertThat(BodyText.optional(null, 500, "reason")).isNull();
	}

	@Test
	void oneLineCollapsesSpaceAndShortens() {
		assertThat(BodyText.oneLine("a  b\n c", 20)).isEqualTo("a b c");
		assertThat(BodyText.oneLine("abcdefghij", 5)).isEqualTo("abcd…");
	}

}
