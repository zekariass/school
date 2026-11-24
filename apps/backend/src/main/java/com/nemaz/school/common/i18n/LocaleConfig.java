package com.nemaz.school.common.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Server messages (error titles/details, notifications) follow the client's
 * {@code Accept-Language}, which the API client sets from the UI locale. Supported
 * locales mirror packages/i18n (SUPPORTED_LOCALES); anything else falls back to English.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

	@Bean
	LocaleResolver localeResolver(@Value("${school.i18n.supported-locales:en,am}") List<String> supported) {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(supported.stream().map(Locale::forLanguageTag).toList());
		resolver.setDefaultLocale(Locale.ENGLISH);
		return resolver;
	}

}
