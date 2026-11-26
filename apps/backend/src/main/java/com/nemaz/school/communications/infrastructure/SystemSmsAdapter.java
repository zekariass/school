package com.nemaz.school.communications.infrastructure;

import java.util.Set;

import com.nemaz.school.communications.SmsAdapter;
import com.nemaz.school.system.SmsSender;

import org.springframework.stereotype.Component;

/**
 * The adapter for every country that has no adapter of its own: it hands the text to the platform's {@link SmsSender}, the
 * one that sends sign-in codes ({@code school.sms.provider}; today only the console provider, which writes to the log and
 * is refused outside the local and test profiles unless explicitly allowed). It carries no sender id, templates or
 * delivery receipts: it exists so the channel works end to end in development, and is replaced by real per-country
 * adapters once a provider is chosen (docs/ops/sms-provider-adapter.md). Announcement SMS stays off in every school until
 * the setting {@code announcements.channel.sms.enabled} is switched on.
 */
@Component
class SystemSmsAdapter implements SmsAdapter {

	private final SmsSender sender;

	SystemSmsAdapter(SmsSender sender) {
		this.sender = sender;
	}

	@Override
	public String id() {
		return "system";
	}

	@Override
	public Set<String> countries() {
		return Set.of("*");
	}

	@Override
	public SmsResult send(SmsMessage message) {
		try {
			this.sender.send(message.phoneE164(), message.text());
			return SmsResult.accepted(null);
		}
		catch (RuntimeException ex) {
			return SmsResult.failed("sender_error:" + ex.getClass().getSimpleName());
		}
	}

}
