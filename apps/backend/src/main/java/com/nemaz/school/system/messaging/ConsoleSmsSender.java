package com.nemaz.school.system.messaging;

import com.nemaz.school.common.security.Masking;
import com.nemaz.school.system.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development SMS "provider": writes the message to the log instead of sending it, so a
 * developer can read one-time codes from the backend output. Never active in a deployed
 * environment unless explicitly allowed ({@link MessagingConfig}).
 */
class ConsoleSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleSmsSender.class);

	@Override
	public void send(String phoneE164, String text) {
		log.info("[console SMS] to {}: {}", Masking.phone(phoneE164), text);
	}

}
