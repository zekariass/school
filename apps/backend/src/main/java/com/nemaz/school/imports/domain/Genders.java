package com.nemaz.school.imports.domain;

import java.util.Locale;
import java.util.Map;

/** Gender cells in English and Amharic as the stored values MALE and FEMALE (OTHER for the explicit word). */
public final class Genders {

	private static final Map<String, String> WORDS = Map.ofEntries(Map.entry("m", "MALE"), Map.entry("male", "MALE"),
			Map.entry("boy", "MALE"), Map.entry("man", "MALE"), Map.entry("ወንድ", "MALE"), Map.entry("ወ", "MALE"),
			Map.entry("f", "FEMALE"), Map.entry("female", "FEMALE"), Map.entry("girl", "FEMALE"),
			Map.entry("woman", "FEMALE"), Map.entry("ሴት", "FEMALE"), Map.entry("ሴ", "FEMALE"),
			Map.entry("other", "OTHER"), Map.entry("o", "OTHER"));

	private Genders() {
	}

	/** The stored value, or null when the cell is not a recognised gender. */
	public static String parse(String raw) {
		if (raw == null) {
			return null;
		}
		return WORDS.get(raw.strip().toLowerCase(Locale.ROOT).replace(".", ""));
	}

}
