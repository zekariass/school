package com.nemaz.school.media.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage ({@code school.storage.*}). Deployed environments supply every connection
 * value (no defaults, so a misconfigured deploy fails at startup); the local profile points
 * at the SeaweedFS container of {@code infra/docker-compose.yml}.
 *
 * <p>
 * {@code endpoint} is how the backend reaches the store; {@code publicEndpoint} is the host
 * clients put in presigned URLs (the same for R2; {@code http://localhost:8343} locally, and
 * the machine's LAN address when a phone uploads). {@code createBucket} creates the bucket and
 * applies a CORS rule for {@code corsOrigins} at startup: local development and tests only,
 * production buckets are provisioned with the platform.
 */
@ConfigurationProperties("school.storage")
public record StorageProperties(URI endpoint, URI publicEndpoint, @DefaultValue("auto") String region, String bucket,
		String accessKey, String secretKey, @DefaultValue("false") boolean createBucket,
		@DefaultValue List<String> corsOrigins, @DefaultValue("PT10M") Duration uploadUrlTtl,
		@DefaultValue("PT5M") Duration readUrlTtl) {

	public StorageProperties {
		if (publicEndpoint == null) {
			publicEndpoint = endpoint;
		}
		corsOrigins = List.copyOf(corsOrigins);
	}

}
