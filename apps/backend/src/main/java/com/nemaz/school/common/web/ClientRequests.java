package com.nemaz.school.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The client address and user agent of the current HTTP request, for rate limits, sessions
 * and the audit log. The address is {@code request.getRemoteAddr()}: behind a proxy or load
 * balancer it is the proxy's address unless {@code server.forward-headers-strategy} is
 * configured for a trusted proxy. {@code X-Forwarded-For} is never read directly, because
 * any client can send it.
 */
public final class ClientRequests {

	private static final int MAX_USER_AGENT = 512;

	private ClientRequests() {
	}

	/** The current servlet request, or null outside a request (startup, scheduled jobs). */
	public static HttpServletRequest current() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return (attributes instanceof ServletRequestAttributes servlet) ? servlet.getRequest() : null;
	}

	public static String ip() {
		HttpServletRequest request = current();
		return (request != null) ? request.getRemoteAddr() : null;
	}

	public static String userAgent() {
		HttpServletRequest request = current();
		if (request == null) {
			return null;
		}
		String agent = request.getHeader("User-Agent");
		if (agent == null || agent.isBlank()) {
			return null;
		}
		return (agent.length() > MAX_USER_AGENT) ? agent.substring(0, MAX_USER_AGENT) : agent;
	}

}
