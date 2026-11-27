package com.nemaz.school.imports.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Phone numbers as people type them in spreadsheets, to E.164. Handles what Excel does to them: the leading zero is
 * lost when the cell is a number ({@code 911234567}), large numbers become scientific notation
 * ({@code 9.11234567E8}), and formatting characters, non-Latin digits and the stray zero after the calling code
 * ({@code +2510911...}) are common.
 * <p>
 * Numbers are read in the school's country: its ISO region and the calling code from the {@code countries} table
 * (nothing here names a country). libphonenumber may not know the newest ranges, so a number of the school's own
 * country that is not "valid" is still accepted with a warning when it has the length of a mobile number; a number of
 * another country when it is at least possible there; anything that cannot be a phone number is an error.
 */
public final class PhoneNormalizer {

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	private static final Pattern SCIENTIFIC = Pattern.compile("^[0-9]+(\\.[0-9]+)?[eE]\\+?[0-9]+$");

	/** {@code 911234567.0}: a whole number a spreadsheet wrote with a decimal part. */
	private static final Pattern WHOLE_DECIMAL = Pattern.compile("^[0-9]+\\.0+$");

	private PhoneNormalizer() {
	}

	/**
	 * {@code e164} is null when the cell was empty or the number is not usable ({@code errorCode} then says why);
	 * {@code warningCode} is set for a number that was accepted but not verified.
	 */
	public record Result(String e164, String errorCode, String warningCode) {

		public boolean empty() {
			return this.e164 == null && this.errorCode == null;
		}

	}

	/**
	 * @param raw the cell as the file gave it
	 * @param region ISO 3166-1 alpha-2 code of the country national numbers are read in (the school's)
	 * @param callingCode that country's calling code, with or without the plus sign ({@code +254} or {@code 254})
	 */
	public static Result normalize(String raw, String region, String callingCode) {
		if (raw == null || raw.isBlank()) {
			return new Result(null, null, null);
		}
		String cc = callingCode.replaceAll("[^0-9]", "");
		String text = Digits.toAscii(raw).strip();
		if (SCIENTIFIC.matcher(text).matches() || WHOLE_DECIMAL.matcher(text).matches()) {
			text = new BigDecimal(text).toBigInteger().toString();
		}
		boolean plus = text.startsWith("+");
		StringBuilder digits = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= '0' && c <= '9') {
				digits.append(c);
			}
			else if (Character.isLetter(c)) {
				return new Result(null, "phone_invalid", null);
			}
			else if (c == '/' || c == ';' || c == ',' || c == '\n') {
				return new Result(null, "phone_multiple", null);
			}
		}
		String d = digits.toString();
		if (d.length() < 7) {
			return new Result(null, "phone_invalid", null);
		}
		String international;
		if (plus) {
			international = d;
		}
		else if (d.startsWith("00")) {
			international = d.substring(2);
		}
		else if (d.startsWith(cc) && possible("+" + d, region)) {
			// the calling code typed without its plus sign
			international = d;
		}
		else if (d.startsWith("0")) {
			international = cc + d.substring(1);
		}
		else {
			// a national number without its trunk zero (a spreadsheet dropped it), or another shape: read it in the country
			international = cc + d;
		}
		// A stray zero after the calling code (+2510911234567): drop it when the number only makes sense without it
		if (international.startsWith(cc + "0") && !valid("+" + international, region)
				&& possible("+" + cc + international.substring(cc.length() + 1), region)) {
			international = cc + international.substring(cc.length() + 1);
		}
		return judge("+" + international, region, cc);
	}

	private static boolean possible(String candidate, String region) {
		try {
			return UTIL.isPossibleNumber(UTIL.parse(candidate, region));
		}
		catch (NumberParseException ex) {
			return false;
		}
	}

	private static boolean valid(String candidate, String region) {
		try {
			return UTIL.isValidNumber(UTIL.parse(candidate, region));
		}
		catch (NumberParseException ex) {
			return false;
		}
	}

	private static Result judge(String candidate, String region, String callingCode) {
		try {
			PhoneNumber number = UTIL.parse(candidate, region);
			String e164 = UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
			if (UTIL.isValidNumber(number)) {
				return new Result(e164, null, null);
			}
			boolean ownCountry = String.valueOf(number.getCountryCode()).equals(callingCode);
			if (ownCountry ? mobileLength(number) : UTIL.isPossibleNumber(number)) {
				return new Result(e164, null, "phone_unrecognized");
			}
			return new Result(null, "phone_invalid", null);
		}
		catch (NumberParseException ex) {
			return new Result(null, "phone_invalid", null);
		}
	}

	/** The length of a mobile number of the number's country (libphonenumber's own metadata, no per-country rule here). */
	private static boolean mobileLength(PhoneNumber number) {
		return UTIL.isPossibleNumberForType(number, PhoneNumberUtil.PhoneNumberType.MOBILE)
				|| UTIL.isPossibleNumberForType(number, PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE);
	}

}
