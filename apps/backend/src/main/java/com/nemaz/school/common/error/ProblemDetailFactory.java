package com.nemaz.school.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.nemaz.school.common.web.RequestIds;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the platform's RFC 9457 error bodies. Every body carries:
 * <ul>
 * <li>{@code code}: stable machine-readable id clients switch on</li>
 * <li>{@code title}/{@code detail}: localized via messages*.properties</li>
 * <li>{@code requestId}: correlates with server logs</li>
 * </ul>
 */
@Component
public class ProblemDetailFactory {

	static final String TYPE_PREFIX = "urn:school:problem:";

	private final MessageSource messages;

	public ProblemDetailFactory(MessageSource messages) {
		this.messages = messages;
	}

	public ProblemDetail create(HttpStatusCode status, String code, Object[] args, Locale locale) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create(TYPE_PREFIX + code));
		problem.setTitle(title(status, locale));
		problem.setDetail(this.messages.getMessage("error." + code, args, problem.getTitle(), locale));
		problem.setProperty("code", code);
		addRequestId(problem);
		return problem;
	}

	/** Fill code/title/requestId on a framework-generated problem (404, 405, 415, ...). */
	public ProblemDetail enrich(ProblemDetail problem, Locale locale) {
		HttpStatusCode status = HttpStatusCode.valueOf(problem.getStatus());
		Map<String, Object> props = problem.getProperties();
		if (props == null || !props.containsKey("code")) {
			String code = defaultCode(status);
			problem.setProperty("code", code);
			problem.setType(URI.create(TYPE_PREFIX + code));
			problem.setDetail(this.messages.getMessage("error." + code, null, problem.getDetail(), locale));
		}
		problem.setTitle(title(status, locale));
		addRequestId(problem);
		return problem;
	}

	/** Flat JSON-ready representation, for writers outside Spring MVC (security filters). */
	public static Map<String, Object> toMap(ProblemDetail problem) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", problem.getType().toString());
		body.put("title", problem.getTitle());
		body.put("status", problem.getStatus());
		body.put("detail", problem.getDetail());
		if (problem.getInstance() != null) {
			body.put("instance", problem.getInstance().toString());
		}
		if (problem.getProperties() != null) {
			body.putAll(problem.getProperties());
		}
		return body;
	}

	static String defaultCode(HttpStatusCode status) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		return (resolved != null) ? resolved.name().toLowerCase(Locale.ROOT) : "http_" + status.value();
	}

	private String title(HttpStatusCode status, Locale locale) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		String fallback = (resolved != null) ? resolved.getReasonPhrase() : "Error";
		return this.messages.getMessage("error.title." + status.value(), null, fallback, locale);
	}

	private static void addRequestId(ProblemDetail problem) {
		String requestId = RequestIds.current();
		if (requestId != null) {
			problem.setProperty("requestId", requestId);
		}
	}

}
