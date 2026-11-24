package com.nemaz.school.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.nemaz.school.notifications.domain.PushSender;

/**
 * Stands in for the push provider in integration tests: records every message and answers with
 * SENT unless a test installs a {@link #respondWith responder}. Reset it after use ({@link #reset}).
 */
public class RecordingPushSender implements PushSender {

	private final List<PushMessage> sent = new CopyOnWriteArrayList<>();

	private volatile Function<PushMessage, PushResult> responder = (m) -> PushResult.sent("test-" + m.token());

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		List<PushResult> results = new ArrayList<>();
		for (PushMessage message : messages) {
			this.sent.add(message);
			results.add(this.responder.apply(message));
		}
		return results;
	}

	public List<PushMessage> sent() {
		return List.copyOf(this.sent);
	}

	/** The messages sent to one token. */
	public List<PushMessage> sentTo(String token) {
		return this.sent.stream().filter((m) -> m.token().equals(token)).toList();
	}

	public void respondWith(Function<PushMessage, PushResult> responder) {
		this.responder = responder;
	}

	public void reset() {
		this.sent.clear();
		this.responder = (m) -> PushResult.sent("test-" + m.token());
	}

}
