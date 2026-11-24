package com.nemaz.school.system;

/**
 * Outbound SMS. Exactly one implementation is active, chosen by {@code school.sms.provider};
 * the application refuses to start without one. The console provider (log only) is allowed
 * only in the local and test profiles or with {@code school.sms.allow-console=true}.
 * Implementations must never log the full number.
 */
public interface SmsSender {

	void send(String phoneE164, String text);

}
