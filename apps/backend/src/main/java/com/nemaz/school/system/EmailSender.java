package com.nemaz.school.system;

/**
 * Outbound plain-text email through {@code JavaMailSender} ({@code spring.mail.*}; Mailpit
 * in the local profile). Without a configured mail server messages are dropped with a warning.
 */
public interface EmailSender {

	void send(String to, String subject, String text);

}
