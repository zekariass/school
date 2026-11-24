package com.nemaz.school.tenancy.api;

/**
 * Bean-validation constants. Messages are i18n keys ({@code validation.*}), never English
 * sentences: clients translate them with the shared catalogs (packages/i18n).
 */
final class Validation {

	static final String REQUIRED = "validation.required";

	static final String TOO_LONG = "validation.tooLong";

	static final String INVALID = "validation.invalid";

	static final String SLUG = "validation.slug";

	static final String EMAIL = "validation.email";

	static final String PHONE = "validation.phone";

	static final String COLOR = "validation.color";

	static final String SLUG_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

	static final String COUNTRY_PATTERN = "^[A-Za-z]{2}$";

	static final String LANGUAGE_PATTERN = "^[a-z]{2,3}$";

	static final String CODE_PATTERN = "^[A-Z0-9_]{1,32}$";

	static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

	static final String PHONE_PATTERN = "^\\+[1-9][0-9]{6,14}$";

	static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";

	static final String URL_PATTERN = "^https?://\\S+$";

	private Validation() {
	}

}
