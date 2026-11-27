package com.nemaz.school.common.i18n;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Server messages (error titles/details, notifications) follow the client's
 * {@code Accept-Language}, which the API client sets from the UI locale. The supported locales are the
 * message bundles the backend has ({@link SupportedLocales}); anything else falls back to English.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

	@Bean
	LocaleResolver localeResolver(SupportedLocales supported) {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(supported.locales());
		resolver.setDefaultLocale(Locale.ENGLISH);
		return resolver;
	}

}
