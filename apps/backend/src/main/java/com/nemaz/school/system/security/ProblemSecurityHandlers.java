package com.nemaz.school.system.security;

import java.io.IOException;
import java.util.Locale;

import com.nemaz.school.common.error.ProblemDetailFactory;
import com.nemaz.school.common.security.CodedAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 401/403 responses produced inside the security filter chain, which runs before
 * Spring MVC: same problem-details shape and language as every other error.
 * <ul>
 * <li>no token: {@code unauthorized}</li>
 * <li>a token that is malformed, badly signed, expired or for another audience:
 * {@code auth.invalid_token}</li>
 * <li>a valid token whose session was revoked or whose account is no longer active:
 * the {@link CodedAuthenticationException}'s code ({@code auth.session_revoked},
 * {@code auth.account_inactive})</li>
 * </ul>
 */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ProblemDetailFactory problems;

	private final LocaleResolver localeResolver;

	private final JsonMapper json;

	ProblemSecurityHandlers(ProblemDetailFactory problems, LocaleResolver localeResolver, JsonMapper json) {
		this.problems = problems;
		this.localeResolver = localeResolver;
		this.json = json;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		String code;
		if (ex instanceof CodedAuthenticationException coded) {
			code = coded.code();
		}
		else if (ex instanceof InvalidBearerTokenException) {
			code = "auth.invalid_token";
		}
		else {
			code = "unauthorized";
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
				"unauthorized".equals(code) ? "Bearer" : "Bearer error=\"invalid_token\"");
		write(request, response, HttpStatus.UNAUTHORIZED, code);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		write(request, response, HttpStatus.FORBIDDEN, "forbidden");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
			throws IOException {
		Locale locale = this.localeResolver.resolveLocale(request);
		ProblemDetail problem = this.problems.create(status, code, null, locale);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		this.json.writeValue(response.getOutputStream(), ProblemDetailFactory.toMap(problem));
	}

}
