package com.nemaz.school.media.application;

/**
 * The content cannot be processed and never will be (corrupt, unsupported, too large to
 * decode): the media becomes FAILED. Storage and infrastructure errors are different: they
 * propagate, and the event is redelivered.
 */
class UnprocessableMediaException extends Exception {

	private final String reason;

	UnprocessableMediaException(String reason, Throwable cause) {
		super(reason, cause);
		this.reason = reason;
	}

	UnprocessableMediaException(String reason) {
		this(reason, null);
	}

	/** A short machine-readable reason, stored in {@code media.failure_reason}. */
	String reason() {
		return this.reason;
	}

}
