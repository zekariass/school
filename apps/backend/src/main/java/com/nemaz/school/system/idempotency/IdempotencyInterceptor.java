package com.nemaz.school.system.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.system.idempotency.IdempotencyFilter.BufferedRequest;
import com.nemaz.school.system.idempotency.IdempotencyFilter.Reservation;
import com.nemaz.school.system.idempotency.IdempotencyStore.Outcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

/**
 * Applies {@code Idempotency-Key} to handlers annotated {@link Idempotent}: reserves the key,
 * or replays the stored response, or refuses a reused or in-flight key. See {@link Idempotent}.
 */
class IdempotencyInterceptor implements HandlerInterceptor {

	static final String RESERVATION = IdempotencyInterceptor.class.getName() + ".reservation";

	/** 1 to 128 visible ASCII characters (no spaces or control characters). */
	private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

	private final IdempotencyStore store;

	IdempotencyInterceptor(IdempotencyStore store) {
		this.store = store;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Idempotent.class)
				|| !"POST".equals(request.getMethod())) {
			return true;
		}
		String key = request.getHeader(Idempotent.HEADER);
		CurrentUser user = CurrentUser.current().orElse(null);
		BufferedRequest buffered = WebUtils.getNativeRequest(request, BufferedRequest.class);
		if (key == null || user == null || buffered == null) {
			return true;
		}
		if (!KEY.matcher(key).matches()) {
			throw ApiException.badRequest("idempotency.key_invalid");
		}
		Outcome outcome = this.store.reserve(user.userId(), key, requestHash(request, buffered.body()));
		switch (outcome.kind()) {
			case RESERVED -> request.setAttribute(RESERVATION, new Reservation(user.userId(), key));
			case REPLAY -> {
				response.setStatus(outcome.status());
				response.setHeader(Idempotent.REPLAYED_HEADER, "true");
				if (outcome.body() != null) {
					response.setContentType(MediaType.APPLICATION_JSON_VALUE);
					response.setCharacterEncoding(StandardCharsets.UTF_8.name());
					response.getOutputStream().write(outcome.body().getBytes(StandardCharsets.UTF_8));
				}
				return false;
			}
			case MISMATCH -> throw ApiException.unprocessable("idempotency.key_reused");
			case IN_PROGRESS -> throw ApiException.conflict("idempotency.in_progress");
		}
		return true;
	}

	static String requestHash(HttpServletRequest request, byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
			String query = (request.getQueryString() != null) ? request.getQueryString() : "";
			String canonical = request.getMethod() + "\n" + request.getRequestURI() + "\n" + query + "\n" + bodyHash;
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
