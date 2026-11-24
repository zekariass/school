package com.nemaz.school.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SmsSender;

/** Captures SMS and email sent by the application (delivery is asynchronous, so tests await). */
public class RecordingMessages implements SmsSender, EmailSender {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	private final Map<String, BlockingDeque<String>> inbox = new ConcurrentHashMap<>();

	@Override
	public void send(String phoneE164, String text) {
		box(phoneE164).add(text);
	}

	@Override
	public void send(String to, String subject, String text) {
		box(to).add(subject + "\n" + text);
	}

	/** The next message to {@code destination}, waiting up to 5 seconds. */
	public String awaitMessage(String destination) {
		try {
			String message = box(destination).poll(5, TimeUnit.SECONDS);
			if (message == null) {
				throw new AssertionError("No message delivered to " + destination);
			}
			return message;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	/** The 6-digit code of the next message to {@code destination}. */
	public String awaitCode(String destination) {
		Matcher matcher = CODE.matcher(awaitMessage(destination));
		if (!matcher.find()) {
			throw new AssertionError("No code in message to " + destination);
		}
		return matcher.group(1);
	}

	/** True when nothing reaches {@code destination} within {@code wait}. */
	public boolean nothingDelivered(String destination, Duration wait) {
		try {
			return box(destination).poll(wait.toMillis(), TimeUnit.MILLISECONDS) == null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	private BlockingDeque<String> box(String destination) {
		return this.inbox.computeIfAbsent(destination, (d) -> new LinkedBlockingDeque<>());
	}

}
