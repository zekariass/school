package com.nemaz.school.system.messaging;

import com.nemaz.school.common.security.Masking;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SchoolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** {@link EmailSender} over {@link JavaMailSender}, which exists only when {@code spring.mail.host} is set. */
@Component
class SmtpEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

	private final ObjectProvider<JavaMailSender> mail;

	private final String from;

	SmtpEmailSender(ObjectProvider<JavaMailSender> mail, SchoolProperties props) {
		this.mail = mail;
		this.from = props.mail().from();
	}

	@Override
	public boolean configured() {
		return this.mail.getIfAvailable() != null;
	}

	@Override
	public void send(String to, String subject, String text) {
		JavaMailSender sender = this.mail.getIfAvailable();
		if (sender == null) {
			log.warn("Email delivery is not configured (spring.mail.host); message to {} dropped", Masking.email(to));
			return;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(this.from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		try {
			sender.send(message);
		}
		catch (MailException ex) {
			log.error("Email to {} could not be sent: {}", Masking.email(to), ex.getMessage());
		}
	}

}
