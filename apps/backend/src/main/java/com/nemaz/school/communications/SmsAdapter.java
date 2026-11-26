package com.nemaz.school.communications;

import java.util.Set;

/**
 * One SMS provider for one or more countries. The registry ({@code SmsAdapterRegistry}) picks the adapter whose
 * {@link #countries()} contains the number's country (ISO 3166-1 alpha-2), else the adapter that lists {@code "*"}. No
 * production provider exists yet (the owner has not chosen one): see {@code docs/ops/sms-provider-adapter.md} for exactly
 * what an adapter must implement.
 *
 * <p>
 * Implementations never log or store a full number or the message text, must be safe to call from several threads, and
 * must not retry on their own (the platform decides): throw nothing for ordinary failures, return
 * {@link SmsResult#failed}.
 */
public interface SmsAdapter {

	/** A short stable name, stored with every message (for example {@code "africastalking"}). */
	String id();

	/** The countries this adapter serves (ISO codes), or {@code Set.of("*")} for every country without a specific adapter. */
	Set<String> countries();

	SmsResult send(SmsMessage message);

	/**
	 * {@code clientReference} is the recipient row id: pass it to the provider so a delivery receipt can be matched back.
	 * {@code senderId} is the school's or the platform's registered sender name where the country needs one (may be null).
	 */
	record SmsMessage(String phoneE164, String text, String languageCode, String senderId, String clientReference) {

		@Override
		public String toString() {
			return "SmsMessage[redacted]";
		}

	}

	/** {@code accepted}: the provider took the message (not yet delivered); {@code providerRef} identifies it there. */
	record SmsResult(boolean accepted, String providerRef, String error) {

		public static SmsResult accepted(String providerRef) {
			return new SmsResult(true, providerRef, null);
		}

		public static SmsResult failed(String error) {
			return new SmsResult(false, null, error);
		}

	}

}
