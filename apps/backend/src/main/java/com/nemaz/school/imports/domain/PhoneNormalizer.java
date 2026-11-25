package com.nemaz.school.imports.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Phone numbers as people type them in spreadsheets, to E.164. Handles what Excel does to them: the leading zero is
 * lost when the cell is a number ({@code 911234567}), large numbers become scientific notation
 * ({@code 9.11234567E8}), and formatting characters, non-Latin digits and the stray zero in {@code +2510911...}
 * are common. Ethiopian mobile numbers are 9 digits starting with 9 (Ethio Telecom) or 7 (Safaricom Ethiopia).
 * <p>
 * libphonenumber may not know the newest 07 ranges, so a number that is not "valid" is still accepted with a
 * warning when it has the shape of an Ethiopian mobile number; anything that cannot be a phone number is an error.
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

	public static Result normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return new Result(null, null, null);
		}
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
		else if (d.startsWith("251") && d.length() >= 12) {
			international = d;
		}
		else if (d.startsWith("0")) {
			international = "251" + d.substring(1);
		}
		else if (d.length() == 9 && (d.startsWith("9") || d.startsWith("7"))) {
			international = "251" + d;
		}
		else {
			// A national number of another shape (a landline without its zero, a foreign number typed without +)
			international = "251" + d;
		}
		// A stray zero after the country code: +2510911234567
		if (international.startsWith("2510") && international.length() == 13) {
			international = "251" + international.substring(4);
		}
		return judge("+" + international);
	}

	private static Result judge(String candidate) {
		try {
			PhoneNumber number = UTIL.parse(candidate, "ET");
			String e164 = UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
			if (UTIL.isValidNumber(number)) {
				return new Result(e164, null, null);
			}
			if (e164.matches("^\\+251[79][0-9]{8}$")) {
				return new Result(e164, null, "phone_unrecognized");
			}
			if (UTIL.isPossibleNumber(number) && !e164.startsWith("+251")) {
				return new Result(e164, null, "phone_unrecognized");
			}
			return new Result(null, "phone_invalid", null);
		}
		catch (NumberParseException ex) {
			return new Result(null, "phone_invalid", null);
		}
	}

}
