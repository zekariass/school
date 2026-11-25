package com.nemaz.school.system.observability;

import java.util.regex.Pattern;

/**
 * Removes personal data and secrets from free text before it leaves the process (error tracking):
 * e-mail addresses, phone numbers and other long digit runs, the values in PostgreSQL's
 * {@code Key (column)=(value)} details, bearer tokens and long opaque tokens. The text keeps its shape so
 * an error stays readable. Children's data is minimised: when in doubt the value is replaced.
 */
final class PiiScrubber {

	static final String REDACTED = "[redacted]";

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	/** PostgreSQL detail: Key (phone)=(+251911223344) already exists. / Failing row contains (...). */
	private static final Pattern PG_KEY_VALUE = Pattern.compile("(?i)\\bKey \\(([^)]*)\\)=\\(.*?\\)");

	private static final Pattern PG_ROW = Pattern.compile("(?i)\\bFailing row contains \\(.*?\\)");

	private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

	/** A phone number or id made of digits (optionally with +, spaces, dashes, parentheses): seven or more digits. */
	private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{5,}\\d");

	/** Long opaque strings: tokens, keys, hashes (UUIDs have dashes and are kept). */
	private static final Pattern OPAQUE = Pattern.compile("\\b[A-Za-z0-9_-]{32,}\\b");

	private static final Pattern UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private PiiScrubber() {
	}

	static String scrub(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String result = EMAIL.matcher(text).replaceAll(REDACTED);
		result = PG_KEY_VALUE.matcher(result).replaceAll("Key ($1)=(" + REDACTED + ")");
		result = PG_ROW.matcher(result).replaceAll("Failing row contains (" + REDACTED + ")");
		result = BEARER.matcher(result).replaceAll("Bearer " + REDACTED);
		result = replacePhones(result);
		result = replaceOpaque(result);
		return result;
	}

	private static String replacePhones(String text) {
		return PHONE.matcher(text).replaceAll((match) -> {
			String candidate = match.group();
			// UUID fragments and short numbers (counts, ports, years) are not phone numbers
			long digits = candidate.chars().filter(Character::isDigit).count();
			return (digits >= 7) ? REDACTED : candidate;
		});
	}

	private static String replaceOpaque(String text) {
		return OPAQUE.matcher(text).replaceAll((match) -> {
			String candidate = match.group();
			// keep Java class names and file names: they contain dots, which the pattern excludes; keep UUIDs
			return UUID.matcher(candidate).matches() ? candidate : REDACTED;
		});
	}

}
