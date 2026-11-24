package com.nemaz.school.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into problem-details responses. Clients rely on
 * {@code code}; never put stack traces or internal messages in a response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ProblemDetailFactory problems;

	public GlobalExceptionHandler(ProblemDetailFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail body = this.problems.create(ex.status(), ex.code(), ex.args(), LocaleContextHolder.getLocale());
		return ResponseEntity.status(ex.status()).body(body);
	}

	/**
	 * Security exceptions thrown inside controllers (method security) must reach Spring
	 * Security's ExceptionTranslationFilter so anonymous users get 401 and authenticated
	 * users 403, rather than being swallowed by the catch-all below.
	 */
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void rethrowSecurityException(RuntimeException ex) {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		ProblemDetail body = this.problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", null,
				LocaleContextHolder.getLocale());
		return ResponseEntity.internalServerError().body(body);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail body = this.problems.create(HttpStatus.BAD_REQUEST, "validation_failed", null,
				LocaleContextHolder.getLocale());
		List<Map<String, Object>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> Map.<String, Object>of("field", error.getField(), "code",
					String.valueOf(error.getCode()), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		body.setProperty("errors", errors);
		return ResponseEntity.badRequest().headers(headers).body(body);
	}

	/** Adds code/title/requestId to every framework-generated problem (404, 405, 415, ...). */
	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			this.problems.enrich(problem, LocaleContextHolder.getLocale());
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

}
