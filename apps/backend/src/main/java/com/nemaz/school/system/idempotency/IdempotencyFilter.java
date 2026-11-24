package com.nemaz.school.system.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Buffers the request body and the response of authenticated POSTs that carry an
 * {@code Idempotency-Key}, so that {@link IdempotencyInterceptor} can hash the request and,
 * once the handler has run, the outcome can be stored (2xx) or the reservation released.
 * Runs after Spring Security, so the current user is known. Requests without the header
 * pass straight through.
 */
class IdempotencyFilter extends OncePerRequestFilter {

	private final IdempotencyStore store;

	IdempotencyFilter(IdempotencyStore store) {
		this.store = store;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod()) || request.getHeader(Idempotent.HEADER) == null
				|| CurrentUser.current().isEmpty();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		BufferedRequest buffered = new BufferedRequest(request);
		ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
		boolean completed = false;
		try {
			chain.doFilter(buffered, cached);
			Reservation reservation = (Reservation) request.getAttribute(IdempotencyInterceptor.RESERVATION);
			if (reservation != null) {
				int status = cached.getStatus();
				if (status >= 200 && status < 300) {
					byte[] body = cached.getContentAsByteArray();
					this.store.complete(reservation.userId(), reservation.key(), status,
							(body.length == 0) ? null : new String(body, StandardCharsets.UTF_8));
					completed = true;
				}
			}
		}
		finally {
			Reservation reservation = (Reservation) request.getAttribute(IdempotencyInterceptor.RESERVATION);
			if (reservation != null && !completed) {
				this.store.release(reservation.userId(), reservation.key());
			}
			cached.copyBodyToResponse();
		}
	}

	/** The request with its body read once up front, so it can be hashed and still be read by the handler. */
	static final class BufferedRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		BufferedRequest(HttpServletRequest request) throws IOException {
			super(request);
			this.body = request.getInputStream().readAllBytes();
		}

		byte[] body() {
			return this.body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream in = new ByteArrayInputStream(this.body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return in.read(b, off, len);
				}

				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

	}

	record Reservation(java.util.UUID userId, String key) {
	}

}
