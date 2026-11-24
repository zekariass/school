package com.nemaz.school.media.domain;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The object store behind media (S3 API: Cloudflare R2 in deployed environments, SeaweedFS
 * locally). Keys are relative to the configured bucket. Operations throw
 * {@link StorageException} for failures; a missing object is {@link Optional#empty()} from
 * {@link #head}, not an error.
 */
public interface ObjectStorage {

	/**
	 * A URL the client {@code PUT}s the bytes to. The content type and the content length are
	 * part of the signature, so the store rejects a different type or size.
	 */
	PresignedUpload presignPut(String key, String contentType, long contentLength, Duration ttl);

	/**
	 * A URL that downloads the object for {@code ttl}. {@code contentDisposition} and
	 * {@code contentType} override the response headers (inline display, download name).
	 */
	URI presignGet(String key, Duration ttl, String contentDisposition, String contentType);

	/** Size and content type of the object, empty when it does not exist. */
	Optional<ObjectHead> head(String key);

	/** The first {@code length} bytes (fewer for a shorter object): for magic-byte sniffing. */
	byte[] getRange(String key, long offset, int length);

	InputStream get(String key);

	void put(String key, byte[] content, String contentType);

	/** Deletes the object; a missing object is not an error. */
	void delete(String key);

	record PresignedUpload(URI url, Map<String, String> headers, Instant expiresAt) {
	}

	record ObjectHead(long size, String contentType) {
	}

	class StorageException extends RuntimeException {

		public StorageException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
