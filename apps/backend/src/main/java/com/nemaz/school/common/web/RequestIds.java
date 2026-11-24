package com.nemaz.school.common.web;

import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Per-request correlation id, carried in the {@value #HEADER} header and the log MDC. */
public final class RequestIds {

	public static final String HEADER = "X-Request-Id";

	public static final String MDC_KEY = "requestId";

	private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

	private RequestIds() {
	}

	/** The current request's id, or null outside a request. */
	public static String current() {
		return MDC.get(MDC_KEY);
	}

	/** Client-supplied ids are accepted only if they are short and log-safe. */
	static boolean isAcceptable(String candidate) {
		return candidate != null && SAFE.matcher(candidate).matches();
	}

}
