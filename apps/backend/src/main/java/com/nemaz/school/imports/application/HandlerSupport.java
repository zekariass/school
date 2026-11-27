package com.nemaz.school.imports.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.imports.application.RowHandler.Draft;
import com.nemaz.school.imports.application.RowHandler.JobContext;
import com.nemaz.school.imports.domain.DateParser;
import com.nemaz.school.imports.domain.PhoneNormalizer;

/** Small readers and checks shared by the row handlers. */
final class HandlerSupport {

	private HandlerSupport() {
	}

	static String str(Map<String, Object> normalized, String key) {
		Object v = normalized.get(key);
		return (v == null) ? null : v.toString();
	}

	static UUID uuid(Map<String, Object> normalized, String key) {
		String v = str(normalized, key);
		return (v == null) ? null : UUID.fromString(v);
	}

	static LocalDate date(Map<String, Object> normalized, String key) {
		String v = str(normalized, key);
		return (v == null) ? null : LocalDate.parse(v);
	}

	static Integer integer(Map<String, Object> normalized, String key) {
		Object v = normalized.get(key);
		return (v instanceof Number n) ? n.intValue() : null;
	}

	/** A value no longer than {@code max}; otherwise an error on the column and null. */
	static String bounded(Draft draft, String column, String value, int max) {
		if (value != null && value.length() > max) {
			draft.error(column, "too_long", String.valueOf(max));
			return null;
		}
		return value;
	}

	/** The phone in E.164, with the problem recorded on the draft (an error, or a warning when {@code lenient}). */
	static String phone(JobContext ctx, Draft draft, String column, String raw, boolean lenient) {
		PhoneNormalizer.Result result = PhoneNormalizer.normalize(raw, ctx.country().code(), ctx.country().phoneCountryCode());
		if (result.errorCode() != null) {
			if (lenient) {
				draft.warn(column, result.errorCode());
			}
			else {
				draft.error(column, result.errorCode());
			}
			return null;
		}
		if (result.warningCode() != null) {
			draft.warn(column, result.warningCode());
		}
		return result.e164();
	}

	/** A date cell; the error is recorded on the draft. */
	static LocalDate date(Draft draft, String column, String raw, boolean nativeDate, DateParser.Options options) {
		DateParser.Result result = DateParser.parse(raw, nativeDate, options);
		if (result.errorCode() != null) {
			draft.error(column, result.errorCode());
			return null;
		}
		return result.date();
	}

}
