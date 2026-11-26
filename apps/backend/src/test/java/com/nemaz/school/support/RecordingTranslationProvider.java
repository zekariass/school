package com.nemaz.school.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.nemaz.school.feed.TranslationProvider;

/**
 * The fake translation adapter of the tests (decision D5). Unavailable until a test turns it on, so the shipped default
 * (no provider) stays covered; it records exactly what a real provider would receive and answers with the text reversed
 * word by word, keeping the placeholders.
 */
public class RecordingTranslationProvider implements TranslationProvider {

	private final List<TranslationRequest> received = new CopyOnWriteArrayList<>();

	private volatile boolean available;

	private volatile boolean failing;

	@Override
	public String name() {
		return "fake";
	}

	@Override
	public boolean available() {
		return this.available;
	}

	public void available(boolean available) {
		this.available = available;
	}

	public void failing(boolean failing) {
		this.failing = failing;
	}

	@Override
	public String translate(TranslationRequest request) throws TranslationException {
		this.received.add(request);
		if (this.failing) {
			throw new TranslationException("provider down", null);
		}
		return "[" + request.targetLanguage() + "] " + request.text();
	}

	/** Everything the provider was asked to translate (text and languages only: that is all it ever gets). */
	public List<TranslationRequest> received() {
		return List.copyOf(this.received);
	}

	public void reset() {
		this.received.clear();
		this.available = false;
		this.failing = false;
	}

}
