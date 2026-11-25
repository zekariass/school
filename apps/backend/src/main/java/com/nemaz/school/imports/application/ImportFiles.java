package com.nemaz.school.imports.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.RowSource;
import com.nemaz.school.imports.infrastructure.RowSources;
import com.nemaz.school.imports.infrastructure.SourceOptions;
import com.nemaz.school.media.MediaContent;
import com.nemaz.school.media.MediaService;

import org.springframework.stereotype.Component;

/**
 * Brings an uploaded file to local disk (the zip central directory of a workbook needs random access), fingerprints
 * it, and runs parsing on a small bounded pool with a timeout, so a hostile or huge file cannot hold a request
 * thread or the heap for long.
 */
@Component
class ImportFiles {

	private final MediaService media;

	private final ImportProperties properties;

	private final ThreadPoolExecutor parser;

	ImportFiles(MediaService media, ImportProperties properties, ThreadPoolExecutor importParserPool) {
		this.media = media;
		this.properties = properties;
		this.parser = importParserPool;
	}

	/** A staged copy of the upload; closing deletes it. */
	static final class Staged implements AutoCloseable {

		private final Path path;

		private final String sha256;

		private final long size;

		private final String filename;

		Staged(Path path, String sha256, long size, String filename) {
			this.path = path;
			this.sha256 = sha256;
			this.size = size;
			this.filename = filename;
		}

		Path path() {
			return this.path;
		}

		String sha256() {
			return this.sha256;
		}

		long size() {
			return this.size;
		}

		String filename() {
			return this.filename;
		}

		@Override
		public void close() {
			try {
				Files.deleteIfExists(this.path);
			}
			catch (IOException ex) {
				// a temp file that cannot be removed is cleaned by the operating system
			}
		}

	}

	Staged stage(UUID mediaId) {
		MediaContent content = this.media.content(mediaId).orElseThrow(() -> ApiException.conflict("import.file_unavailable"));
		long limit = this.properties.maxFileBytes();
		if (content.sizeBytes() > limit) {
			closeQuietly(content.stream());
			throw ApiException.badRequest("import.file_too_large", limit / (1024 * 1024));
		}
		Path temp = null;
		try (InputStream in = content.stream()) {
			temp = Files.createTempFile("roster-import-", ".bin");
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			long total = 0;
			try (DigestInputStream digesting = new DigestInputStream(in, digest);
					java.io.OutputStream out = Files.newOutputStream(temp)) {
				byte[] buffer = new byte[64 * 1024];
				int n;
				while ((n = digesting.read(buffer)) > 0) {
					total += n;
					if (total > limit) {
						throw ApiException.badRequest("import.file_too_large", limit / (1024 * 1024));
					}
					out.write(buffer, 0, n);
				}
			}
			return new Staged(temp, HexFormat.of().formatHex(digest.digest()), total, content.filename());
		}
		catch (IOException | NoSuchAlgorithmException ex) {
			deleteQuietly(temp);
			throw new IllegalStateException("Could not read the uploaded file", ex);
		}
		catch (RuntimeException ex) {
			deleteQuietly(temp);
			throw ex;
		}
	}

	/** SHA-256 of the stored file, streamed (nothing is kept). */
	String fingerprint(UUID mediaId) {
		MediaContent content = this.media.content(mediaId).orElseThrow(() -> ApiException.conflict("import.file_unavailable"));
		try (InputStream in = content.stream()) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Could not read the uploaded file", ex);
		}
	}

	RowSource open(Staged file, SourceOptions options) {
		return RowSources.open(file.path(), file.filename(), this.properties.limits(), options);
	}

	/** Runs {@code work} on the parser pool; at most {@code parseTimeout}, then {@code import.parse_timeout}. */
	<T> T parse(Callable<T> work) {
		Future<T> future;
		try {
			future = this.parser.submit(work);
		}
		catch (RejectedExecutionException ex) {
			throw ApiException.tooManyRequests("import.busy", 15);
		}
		try {
			return future.get(this.properties.parseTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw ApiException.badRequest("import.parse_timeout");
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw ApiException.badRequest("import.parse_timeout");
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(ex.getCause());
		}
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		}
		catch (IOException ex) {
			// nothing to do
		}
	}

	private static void deleteQuietly(Path path) {
		if (path != null) {
			try {
				Files.deleteIfExists(path);
			}
			catch (IOException ex) {
				// ignore
			}
		}
	}

}
