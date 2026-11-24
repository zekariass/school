package com.nemaz.school.realtime.infrastructure;

import org.springframework.messaging.MessagingException;

/** A STOMP frame was refused. {@code code} is a stable machine-readable reason sent in the ERROR frame. */
class RealtimeAccessException extends MessagingException {

	private final String code;

	RealtimeAccessException(String code) {
		super(code);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}
