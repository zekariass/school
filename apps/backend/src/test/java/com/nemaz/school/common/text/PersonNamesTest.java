package com.nemaz.school.common.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNamesTest {

	@Test
	void cleanNormalizesWhitespaceZeroWidthAndPunctuation() {
		assertThat(PersonNames.clean("  Abebe   Kebede ")).isEqualTo("Abebe Kebede");
		assertThat(PersonNames.clean("አበበ፡በቀለ")).as("Ethiopic word space").isEqualTo("አበበ በቀለ");
		assertThat(PersonNames.clean("Ab​ebe‎")).as("zero-width and bidi marks").isEqualTo("Abebe");
		assertThat(PersonNames.clean("O'Brien-Smith")).isEqualTo("O'Brien-Smith");
		assertThat(PersonNames.clean("=cmd|' /C calc'!A0")).as("formula characters are dropped").doesNotStartWith("=");
		assertThat(PersonNames.clean("Abebe።፣")).as("Ethiopic punctuation").isEqualTo("Abebe");
		assertThat(PersonNames.clean("   ")).isNull();
		assertThat(PersonNames.clean(null)).isNull();
		// NFC: e + combining acute becomes the precomposed letter
		assertThat(PersonNames.clean("José")).isEqualTo("José");
	}

	@Test
	void foldMakesCaseAccentsAndEthiopicHomophonesEqual() {
		assertThat(PersonNames.fold("ABEBE", "Kebede")).isEqualTo(PersonNames.fold("abebe", "kebede"));
		assertThat(PersonNames.fold("José")).isEqualTo(PersonNames.fold("jose"));
		// ሀ ሐ ኀ, ሰ ሠ, አ ዐ, ጸ ፀ are one letter each for comparison
		assertThat(PersonNames.foldPart("ሐይሌ")).isEqualTo(PersonNames.foldPart("ሀይሌ")).isEqualTo(PersonNames.foldPart("ኀይሌ"));
		assertThat(PersonNames.foldPart("ሠላም")).isEqualTo(PersonNames.foldPart("ሰላም"));
		assertThat(PersonNames.foldPart("ዐበባ")).isEqualTo(PersonNames.foldPart("አበባ"));
		assertThat(PersonNames.foldPart("ፀሐይ")).isEqualTo(PersonNames.foldPart("ጸሀይ"));
		// the other vowel orders fold the same way
		assertThat(PersonNames.foldPart("ሑ")).isEqualTo(PersonNames.foldPart("ሁ"));
	}

	@Test
	void foldNeverMatchesDifferentLettersOrScripts() {
		assertThat(PersonNames.foldPart("አበበ")).isNotEqualTo(PersonNames.foldPart("አበባ"));
		assertThat(PersonNames.foldPart("አበበ")).isNotEqualTo(PersonNames.foldPart("Abebe"));
		assertThat(PersonNames.fold(null, "  ", null)).isEmpty();
	}

	@Test
	void foldJoinsThePartsWithSingleSpacesAndSkipsAbsentOnes() {
		assertThat(PersonNames.fold("Abebe", null, "Tadesse")).isEqualTo("abebe tadesse");
	}

}
