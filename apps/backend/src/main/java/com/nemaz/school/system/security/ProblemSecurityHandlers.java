package com.nemaz.school.system.security;

import java.io.IOException;
import java.util.Locale;

import com.nemaz.school.common.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 401/403 responses produced inside the security filter chain, which runs before
 * Spring MVC: same problem-details shape and language as every other error.
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
		write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized");
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
