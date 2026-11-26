package com.nemaz.school.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.nemaz.school.communications.SmsAdapter;

/**
 * A per-country SMS adapter for tests: it serves Kenya (KE) only, records every message, and answers accepted unless a test
 * installs a {@link #respondWith responder}. Other countries keep using the platform sender through the {@code *} adapter.
 */
public class RecordingSmsAdapter implements SmsAdapter {

	private final List<SmsMessage> sent = new CopyOnWriteArrayList<>();

	private volatile Function<SmsMessage, SmsResult> responder = (m) -> SmsResult.accepted("ref-" + m.clientReference());

	@Override
	public String id() {
		return "recording-ke";
	}

	@Override
	public Set<String> countries() {
		return Set.of("KE");
	}

	@Override
	public SmsResult send(SmsMessage message) {
		this.sent.add(message);
		return this.responder.apply(message);
	}

	public List<SmsMessage> sent() {
		return List.copyOf(this.sent);
	}

	public void respondWith(Function<SmsMessage, SmsResult> responder) {
		this.responder = responder;
	}

	public void reset() {
		this.sent.clear();
		this.responder = (m) -> SmsResult.accepted("ref-" + m.clientReference());
	}

}
