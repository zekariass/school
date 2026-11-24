package com.nemaz.school.system;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application settings under the {@code school.*} prefix. */
@ConfigurationProperties("school")
public record SchoolProperties(@DefaultValue Cors cors, @DefaultValue Security security, @DefaultValue Events events) {

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

}
