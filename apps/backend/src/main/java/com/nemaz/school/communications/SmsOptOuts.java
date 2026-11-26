package com.nemaz.school.communications;

/**
 * For SMS provider adapters: a number that texted STOP (or whatever the provider reports as an opt-out) must never be
 * texted again, urgent or not. Call {@link #optOut} from the adapter's inbound webhook; the platform checks it before
 * every announcement SMS.
 */
public interface SmsOptOuts {

	void optOut(String phoneE164);

	boolean isOptedOut(String phoneE164);

}
