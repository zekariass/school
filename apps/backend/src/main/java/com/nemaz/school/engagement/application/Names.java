package com.nemaz.school.engagement.application;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/** Localized labels ({@code name_i18n}: language code to text): reading, writing and picking one for a reader. */
final class Names {

	private Names() {
	}

	/** The label in the reader's language, else English, else the first one; empty when there is none. */
	static String pick(Map<String, String> names, Locale locale) {
		if (names == null || names.isEmpty()) {
			return "";
		}
		String text = names.get(locale.getLanguage());
		if (text == null || text.isBlank()) {
			text = names.get("en");
		}
		if (text == null || text.isBlank()) {
			text = names.values().stream().filter((v) -> v != null && !v.isBlank()).findFirst().orElse("");
		}
		return text;
	}

	@SuppressWarnings("unchecked")
	static Map<String, String> read(JsonMapper json, String raw) {
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}
		Map<String, Object> parsed = json.readValue(raw, Map.class);
		Map<String, String> result = new LinkedHashMap<>();
		parsed.forEach((k, v) -> {
			if (v != null) {
				result.put(k, v.toString());
			}
		});
		return result;
	}

	static String write(JsonMapper json, Map<String, String> names) {
		return json.writeValueAsString(names);
	}

}
