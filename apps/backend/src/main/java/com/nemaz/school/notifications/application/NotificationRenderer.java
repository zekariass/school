package com.nemaz.school.notifications.application;

import java.util.List;
import java.util.Locale;

import com.nemaz.school.common.i18n.SupportedLocales;
import com.nemaz.school.notifications.LocalizedText;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

/** Renders a notification's title and body in a recipient's language from the message bundles. */
@Component
class NotificationRenderer {

	private final MessageSource messages;

	private final SupportedLocales supported;

	NotificationRenderer(MessageSource messages, SupportedLocales supported) {
		this.messages = messages;
		this.supported = supported;
	}

	/** The locale to write to a user in: their preferred language when supported, else English. */
	Locale localeOf(String languageCode) {
		return this.supported.localeOf(languageCode);
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
