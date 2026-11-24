package com.nemaz.school.media.infrastructure;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Local development and tests only ({@code school.storage.create-bucket=true}): creates the
 * bucket when it is missing and lets the web app's origin {@code PUT} to it, so browsers can
 * upload with a presigned URL. Production buckets and their CORS rules belong to the platform
 * (infrastructure as code), not to the application.
 */
@Component
@ConditionalOnBooleanProperty("school.storage.create-bucket")
class StorageBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StorageBootstrap.class);

	private final S3ObjectStorage storage;

	private final StorageProperties props;

	StorageBootstrap(S3ObjectStorage storage, StorageProperties props) {
		this.storage = storage;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		var s3 = this.storage.client();
		try {
			s3.headBucket(HeadBucketRequest.builder().bucket(this.props.bucket()).build());
		}
		catch (NoSuchBucketException ex) {
			create();
		}
		catch (S3Exception ex) {
			if (ex.statusCode() != 404) {
				throw ex;
			}
			create();
		}
		if (!this.props.corsOrigins().isEmpty()) {
			try {
				CORSRule rule = CORSRule.builder()
					.allowedOrigins(this.props.corsOrigins())
					.allowedMethods(List.of("PUT", "GET", "HEAD"))
					.allowedHeaders("*")
					.exposeHeaders("ETag")
					.maxAgeSeconds(3600)
					.build();
				s3.putBucketCors(PutBucketCorsRequest.builder()
					.bucket(this.props.bucket())
					.corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
					.build());
				log.info("Storage bucket '{}' allows browser uploads from {}", this.props.bucket(),
						this.props.corsOrigins());
			}
			catch (RuntimeException ex) {
				log.warn("Could not set CORS on bucket '{}': {} (use NEXT_PUBLIC_UPLOAD_MODE=proxy in the web app)",
						this.props.bucket(), ex.getMessage());
			}
		}
	}

	private void create() {
		this.storage.client().createBucket(CreateBucketRequest.builder().bucket(this.props.bucket()).build());
		log.info("Created storage bucket '{}'", this.props.bucket());
	}

}
