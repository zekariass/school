package com.nemaz.school.sync.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code school.sync.*}: the limits of the sync endpoint and the retention of its ledger.
 *
 * @param maxOperations operations per request
 * @param maxOperationBytes serialized payload of one operation
 * @param maxRequestBytes whole request body
 * @param maxOperationAge an operation whose device time is older than this is rejected (a queue that was stuck longer
 * than this needs a human to look at it)
 * @param futureTolerance device time further ahead than this is clamped to the receipt time
 * @param requestsPerMinute requests per user and minute (429 {@code sync.rate_limited})
 * @param payloadRetention payloads and result states (children's data) are removed after this
 * @param rowRetention the ledger rows themselves are removed after this
 */
@ConfigurationProperties("school.sync")
public record SyncProperties(@DefaultValue("100") int maxOperations, @DefaultValue("65536") int maxOperationBytes,
		@DefaultValue("1048576") int maxRequestBytes, @DefaultValue("P30D") Duration maxOperationAge,
		@DefaultValue("PT5M") Duration futureTolerance, @DefaultValue("120") int requestsPerMinute,
		@DefaultValue("P30D") Duration payloadRetention, @DefaultValue("P365D") Duration rowRetention) {
}
