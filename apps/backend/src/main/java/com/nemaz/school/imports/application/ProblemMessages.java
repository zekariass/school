package com.nemaz.school.imports.application;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Text of what is wrong with a row ({@code import.row.<code>}) or with a whole job ({@code error.<code>}) in the
 * reader's language, rendered when it is read (rows store only codes and arguments).
 */
@Component
class ProblemMessages {

	private final MessageSource messages;

	ProblemMessages(MessageSource messages) {
		this.messages = messages;
	}

	String row(String code, List<?> args) {
		return row(code, args, LocaleContextHolder.getLocale());
	}

	String row(String code, List<?> args, Locale locale) {
		return this.messages.getMessage("import.row." + code, args.toArray(), code, locale);
	}

	String job(String code, Locale locale) {
		return this.messages.getMessage("error." + code, null, code, locale);
	}

	String text(String key, Locale locale) {
		return this.messages.getMessage(key, null, key, locale);
	}

}
