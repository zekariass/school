package com.nemaz.school.notifications;

import java.util.Locale;
import java.util.Map;

/**
 * A message argument that exists in several languages (a role or school name). It is resolved
 * per recipient: their language, else English, else any available text.
 */
public record LocalizedText(Map<String, String> values) {

	public LocalizedText {
		values = Map.copyOf(values);
	}

	public static LocalizedText of(Map<String, String> values) {
		return new LocalizedText(values);
	}

	public String in(Locale locale) {
		String text = this.values.get(locale.getLanguage());
		if (text == null) {
			text = this.values.get("en");
		}
		if (text == null && !this.values.isEmpty()) {
			text = this.values.values().iterator().next();
		}
		return (text != null) ? text : "";
	}

}
