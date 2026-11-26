package com.nemaz.school.feed;

/**
 * The machine-translation adapter (decision D5). No provider is shipped: the feature is off by default
 * ({@code feed.translation.enabled}) and without a bean of this type "See translation" answers 503. An implementation
 * receives only the text and the two language codes: never ids, names of children, tags or any other context, and the
 * caller has already masked student names. Implementations must not log the text.
 */
public interface TranslationProvider {

	/** A short stable name stored with each cached translation (e.g. {@code google}, {@code fake}). */
	String name();

	/** Whether the provider is configured and usable right now. */
	default boolean available() {
		return true;
	}

	/**
	 * Translates {@code request.text()} into {@code request.targetLanguage()}.
	 *
	 * @throws TranslationException when the provider fails (the feed answers 503 and caches nothing)
	 */
	String translate(TranslationRequest request) throws TranslationException;

	/** {@code sourceLanguage} is null when the author did not state one (the provider detects it). */
	record TranslationRequest(String text, String sourceLanguage, String targetLanguage) {

		@Override
		public String toString() {
			return "TranslationRequest[" + this.sourceLanguage + "->" + this.targetLanguage + "]";
		}

	}

	class TranslationException extends Exception {

		public TranslationException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
