package com.nemaz.school.media.infrastructure;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.nemaz.school.media.domain.ObjectStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link ObjectStorage} over the AWS SDK for Java v2 (path-style addressing, custom endpoint).
 * Presigned URLs are signed for the public endpoint, because the host is part of the
 * signature; all other calls go to the internal endpoint.
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

	private final S3Client client;

	private final S3Presigner presigner;

	private final String bucket;

	public S3ObjectStorage(StorageProperties props) {
		StaticCredentialsProvider credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
		Region region = Region.of(props.region());
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		// R2 and SeaweedFS reject the default CRC checksum trailers of newer SDKs: only when required.
		this.client = S3Client.builder()
			.endpointOverride(props.endpoint())
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(props.publicEndpoint())
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			.build();
		this.bucket = props.bucket();
	}

	@Override
	public PresignedUpload presignPut(String key, String contentType, long contentLength, Duration ttl) {
		try {
			PutObjectRequest put = PutObjectRequest.builder()
				.bucket(this.bucket)
				.key(key)
				.contentType(contentType)
				.contentLength(contentLength)
				.build();
			PresignedPutObjectRequest presigned = this.presigner.presignPutObject(
					PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());
			// Headers the client must send. Host and Content-Length are set by the HTTP client itself.
			Map<String, String> headers = new LinkedHashMap<>();
			presigned.signedHeaders().forEach((name, values) -> {
				String lower = name.toLowerCase(java.util.Locale.ROOT);
				if (!lower.equals("host") && !lower.equals("content-length") && !values.isEmpty()) {
					headers.put(canonical(lower), String.join(",", values));
				}
			});
			Instant expires = presigned.expiration();
			return new PresignedUpload(presigned.url().toURI(), headers, expires);
		}
		catch (Exception ex) {
			throw new StorageException("Could not presign upload", ex);
		}
	}

	/** {@code content-type} becomes {@code Content-Type}. */
	private static String canonical(String lowerCaseName) {
		StringBuilder name = new StringBuilder();
		boolean upper = true;
		for (char c : lowerCaseName.toCharArray()) {
			name.append(upper ? Character.toUpperCase(c) : c);
			upper = c == '-';
		}
		return name.toString();
	}

	@Override
	public URI presignGet(String key, Duration ttl, String contentDisposition, String contentType) {
		try {
			GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(this.bucket).key(key);
			if (contentDisposition != null) {
				get.responseContentDisposition(contentDisposition);
			}
			if (contentType != null) {
				get.responseContentType(contentType);
			}
			return this.presigner
				.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get.build()).build())
				.url()
				.toURI();
		}
		catch (Exception ex) {
			throw new StorageException("Could not presign download", ex);
		}
	}

	@Override
	public Optional<ObjectHead> head(String key) {
		try {
			var response = this.client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
			return Optional.of(new ObjectHead(response.contentLength(), response.contentType()));
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return Optional.empty();
			}
			throw new StorageException("Could not read object metadata", ex);
		}
		catch (RuntimeException ex) {
			throw new StorageException("Could not read object metadata", ex);
		}
	}

	@Override
	public byte[] getRange(String key, long offset, int length) {
		try {
			String range = "bytes=" + offset + "-" + (offset + length - 1);
			return this.client
				.getObjectAsBytes(GetObjectRequest.builder().bucket(this.bucket).key(key).range(range).build())
				.asByteArray();
		}
		catch (RuntimeException ex) {
			throw new StorageException("Could not read object", ex);
		}
	}

	@Override
	public InputStream get(String key) {
		try {
			return this.client.getObject(GetObjectRequest.builder().bucket(this.bucket).key(key).build());
		}
		catch (RuntimeException ex) {
			throw new StorageException("Could not read object", ex);
		}
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		try {
			this.client.putObject(
					PutObjectRequest.builder().bucket(this.bucket).key(key).contentType(contentType).build(),
					RequestBody.fromBytes(content));
		}
		catch (RuntimeException ex) {
			throw new StorageException("Could not write object", ex);
		}
	}

	@Override
	public void delete(String key) {
		try {
			this.client.deleteObject(DeleteObjectRequest.builder().bucket(this.bucket).key(key).build());
		}
		catch (RuntimeException ex) {
			throw new StorageException("Could not delete object", ex);
		}
	}

	/** The raw client, for bucket bootstrap. */
	S3Client client() {
		return this.client;
	}

	@Override
	public void close() {
		this.presigner.close();
		this.client.close();
	}
}
