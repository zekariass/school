package com.nemaz.school.imports.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.nemaz.school.imports.application.ImportModel.Job;

/** SHA-256 digests: of a row's normalized data, and of everything a dry run depended on. */
final class ImportHashes {

	private ImportHashes() {
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the validated rows depend on: the import type, the file's fingerprint, the column mapping and how the file
	 * is read. A commit refuses to run when any of them differs from what the dry run saw.
	 */
	static String validated(Job job) {
		ImportOptions options = job.options();
		Map<String, Integer> mapping = new TreeMap<>(job.columnMapping());
		return sha256(String.join("|", job.type().name(), job.fingerprint(), mapping.toString(),
				String.valueOf(options.encoding()), String.valueOf(options.delimiter()), String.valueOf(options.sheet()),
				options.dateSystemOrDefault().name()));
	}

}
