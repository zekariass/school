package com.nemaz.school.system;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application settings under the {@code school.*} prefix (identity settings: {@code school.auth.*}). */
@ConfigurationProperties("school")
public record SchoolProperties(@DefaultValue Cors cors, @DefaultValue Security security, @DefaultValue Events events,
		@DefaultValue Sms sms, @DefaultValue Mail mail, @DefaultValue Idempotency idempotency) {

	/** Browser origins allowed to call the API directly (the web BFF calls server-side). */
	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}

	/**
	 * Exposure of operational endpoints. Both default to false; enable only in local/dev
	 * or behind a private network.
	 */
	public record Security(@DefaultValue("false") boolean publicApiDocs, @DefaultValue("false") boolean publicMetrics) {
	}

	/** Transactional outbox maintenance (Spring Modulith event publication registry). */
	public record Events(@DefaultValue("PT5M") Duration resubmitOlderThan,
			@DefaultValue("P7D") Duration purgeCompletedOlderThan) {
	}

	/**
	 * SMS provider ({@code console} only so far). {@code allowConsole} permits the console
	 * provider outside the local/test profiles.
	 */
	public record Sms(@DefaultValue("") String provider, @DefaultValue("false") boolean allowConsole) {
	}

	/** Sender address of system emails. */
	public record Mail(@DefaultValue("School <no-reply@school.local>") String from) {
	}

	/**
	 * Idempotency-Key handling: how long a stored response is replayed, and after how long an
	 * unfinished first request is considered abandoned and its key may be reused.
	 */
	public record Idempotency(@DefaultValue("PT24H") Duration retention, @DefaultValue("PT2M") Duration staleAfter) {
	}

}
