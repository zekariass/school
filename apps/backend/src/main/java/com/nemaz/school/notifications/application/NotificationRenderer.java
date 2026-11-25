package com.nemaz.school.notifications.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nemaz.school.notifications.LocalizedText;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

/** Renders a notification's title and body in a recipient's language from the message bundles. */
@Component
class NotificationRenderer {

	private static final Set<String> LANGUAGES = Set.of("en", "am");

	private final MessageSource messages;

	NotificationRenderer(MessageSource messages) {
		this.messages = messages;
	}

	/** The locale to write to a user in: their preferred language when supported, else English. */
	static Locale localeOf(String languageCode) {
		return (languageCode != null && LANGUAGES.contains(languageCode)) ? Locale.forLanguageTag(languageCode)
				: Locale.ENGLISH;
	}

	Rendered render(String messageKey, List<Object> args, Locale locale) {
		Object[] resolved = args.stream().map((a) -> (a instanceof LocalizedText t) ? t.in(locale) : a).toArray();
		try {
			String title = this.messages.getMessage(messageKey + ".title", resolved, locale);
			String body = this.messages.getMessage(messageKey + ".body", resolved, null, locale);
			return new Rendered(title, body);
		}
		catch (NoSuchMessageException ex) {
			throw new IllegalArgumentException("Missing notification texts for " + messageKey, ex);
		}
	}

	String label(String code, Locale locale) {
		return this.messages.getMessage(code, null, code, locale);
	}

	record Rendered(String title, String body) {
	}

}
