package com.nemaz.school.system.observability;

import java.util.List;

import com.nemaz.school.common.error.ApiException;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;

import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;

/**
 * Error tracking hygiene (CLAUDE.md: minimise data sent to third parties; children's data). Sentry is enabled only
 * when {@code SENTRY_DSN} is set; these callbacks then guarantee what leaves the process:
 * <ul>
 * <li>only server errors: client errors (4xx {@link ApiException}, validation, access denied) are dropped;</li>
 * <li>no request (URL, query string, headers, cookies, body), no user, no server name, no breadcrumbs;</li>
 * <li>exception messages and the event message go through {@link PiiScrubber} (emails, phone numbers, database
 * values, tokens).</li>
 * </ul>
 * Stack traces (code locations) are the useful part and are kept. Default PII sending stays off
 * ({@code sentry.send-default-pii=false}) and request bodies are never attached ({@code max-request-body-size=none}).
 */
@Component
class SentryScrubber implements SentryOptions.BeforeSendCallback, SentryOptions.BeforeBreadcrumbCallback {

	@Override
	public SentryEvent execute(SentryEvent event, Hint hint) {
		if (isClientError(event.getThrowable())) {
			return null;
		}
		event.setRequest(null);
		event.setUser(null);
		event.setServerName(null);
		event.setBreadcrumbs(null);
		Message message = event.getMessage();
		if (message != null) {
			message.setMessage(PiiScrubber.scrub(message.getMessage()));
			message.setFormatted(PiiScrubber.scrub(message.getFormatted()));
			message.setParams(null);
		}
		List<SentryException> exceptions = event.getExceptions();
		if (exceptions != null) {
			exceptions.forEach((exception) -> exception.setValue(PiiScrubber.scrub(exception.getValue())));
		}
		return event;
	}

	/** Breadcrumbs (log lines, HTTP calls) can carry personal data; none are sent. */
	@Override
	public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
		return null;
	}

	static boolean isClientError(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = (current.getCause() == current) ? null
				: current.getCause()) {
			if (current instanceof ApiException api) {
				return api.status().is4xxClientError();
			}
			if (current instanceof AccessDeniedException || current instanceof AuthenticationException) {
				return true;
			}
			if (current instanceof ErrorResponse response) {
				HttpStatusCode status = response.getStatusCode();
				return status.is4xxClientError();
			}
		}
		return false;
	}

}
