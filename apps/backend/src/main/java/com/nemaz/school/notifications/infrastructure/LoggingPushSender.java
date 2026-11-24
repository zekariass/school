package com.nemaz.school.notifications.infrastructure;

import java.util.List;

import com.nemaz.school.notifications.domain.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one line per push instead of sending it (local development and tests). */
public class LoggingPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		return messages.stream().map((m) -> {
			log.info("push to {}: {}", mask(m.token()), m.title());
			return PushResult.sent("log-" + Integer.toHexString(m.hashCode()));
		}).toList();
	}

	/** Keeps the kind and the last four characters of a token. */
	static String mask(String token) {
		if (token == null || token.length() <= 8) {
			return "****";
		}
		return token.substring(0, Math.min(6, token.length() - 4)) + "..." + token.substring(token.length() - 4);
	}

}
