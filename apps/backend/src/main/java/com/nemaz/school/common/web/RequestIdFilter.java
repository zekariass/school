package com.nemaz.school.common.web;

import java.io.IOException;

import com.nemaz.school.common.id.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation id (reusing a safe client-supplied
 * {@code X-Request-Id}), exposes it in the MDC for logs and error bodies, and echoes it
 * in the response. Runs before Spring Security so 401/403 bodies carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(RequestIds.HEADER);
		String requestId = RequestIds.isAcceptable(incoming) ? incoming : Ids.newId().toString();
		MDC.put(RequestIds.MDC_KEY, requestId);
		response.setHeader(RequestIds.HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(RequestIds.MDC_KEY);
		}
	}

}
