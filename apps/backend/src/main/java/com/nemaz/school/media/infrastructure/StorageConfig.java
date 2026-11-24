package com.nemaz.school.media.infrastructure;

import com.nemaz.school.media.domain.ObjectStorage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StorageConfig {

	@Bean(destroyMethod = "close")
	S3ObjectStorage objectStorage(StorageProperties props) {
		require(props.endpoint() != null, "school.storage.endpoint (SCHOOL_STORAGE_ENDPOINT)");
		require(notBlank(props.bucket()), "school.storage.bucket (SCHOOL_STORAGE_BUCKET)");
		require(notBlank(props.accessKey()), "school.storage.access-key (SCHOOL_STORAGE_ACCESS_KEY)");
		require(notBlank(props.secretKey()), "school.storage.secret-key (SCHOOL_STORAGE_SECRET_KEY)");
		return new S3ObjectStorage(props);
	}

	/** The interface other beans inject. */
	@Bean
	ObjectStorage storage(S3ObjectStorage storage) {
		return storage;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	private static void require(boolean condition, String setting) {
		if (!condition) {
			throw new IllegalStateException("Object storage is not configured: " + setting + " is required");
		}
	}

}
