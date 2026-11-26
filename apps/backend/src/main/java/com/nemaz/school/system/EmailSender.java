package com.nemaz.school.system;

/**
 * Outbound plain-text email through {@code JavaMailSender} ({@code spring.mail.*}; Mailpit
 * in the local profile). Without a configured mail server messages are dropped with a warning.
 */
public interface EmailSender {

	void send(String to, String subject, String text);

	/**
	 * False when outbound email is not set up (no mail server): {@link #send} would drop the message. Channels that are
	 * optional (announcements) check it so they never report a delivery that cannot happen.
	 */
	default boolean configured() {
		return true;
	}

}
