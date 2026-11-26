package com.nemaz.school.safeguarding.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.nemaz.school.common.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Application-level AEAD of the narrative and the notes of a safeguarding incident (docs/Safeguarding_Rules.md S4.4):
 * AES-256-GCM, a fresh random 96-bit nonce for EVERY record, the key id stored with the ciphertext so keys can rotate, and
 * the record's own context ({@code incident:<id>:narrative}, {@code note:<id>}) as additional authenticated data, so a
 * ciphertext cannot be moved to another row or field. Envelope: {@code v1.<keyId>.<nonce>.<ciphertext+tag>} (base64url,
 * no padding). Decryption uses the key named in the envelope, so after a rotation old rows still read; encryption always
 * uses the active key. Plain text exists only in memory while a lead reads it.
 */
@Component
public class IncidentCipher {

	static final String VERSION = "v1";

	private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecureRandom random = new SecureRandom();

	private final Map<String, SecretKeySpec> keys;

	private final String activeKeyId;

	IncidentCipher(EncryptionProperties properties) {
		this.keys = parse(properties.keys());
		String active = (properties.activeKeyId() == null) ? null : properties.activeKeyId().trim();
		this.activeKeyId = (active != null && this.keys.containsKey(active)) ? active : null;
	}

	/** Parses the configured keys: blank values (an unset slot) are ignored, a malformed key is a startup error. */
	static Map<String, SecretKeySpec> parse(Map<String, String> configured) {
		Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
		if (configured == null) {
			return keys;
		}
		configured.forEach((id, value) -> {
			if (value == null || value.isBlank() || value.contains("${")) {
				return;
			}
			if (!KEY_ID.matcher(id).matches()) {
				throw new IllegalStateException("A safeguarding encryption key id is not valid (letters, digits, _ and -, 1-32)");
			}
			byte[] raw;
			try {
				raw = Base64.getDecoder().decode(value.trim());
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalStateException("Safeguarding encryption key '" + id + "' is not valid base64");
			}
			if (raw.length != 32) {
				throw new IllegalStateException("Safeguarding encryption key '" + id + "' must be 32 bytes (AES-256)");
			}
			keys.put(id, new SecretKeySpec(raw, "AES"));
		});
		return keys;
	}

	/** True when new text can be encrypted (an active key is configured and listed). */
	public boolean available() {
		return this.activeKeyId != null;
	}

	public String activeKeyId() {
		return this.activeKeyId;
	}

	/** Encrypts {@code plain} for the record context {@code aad}; fails closed (503) without an active key. */
	public String encrypt(String plain, String aad) {
		if (this.activeKeyId == null) {
			throw unavailable();
		}
		byte[] nonce = new byte[NONCE_BYTES];
		this.random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, this.keys.get(this.activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
			return VERSION + "." + this.activeKeyId + "." + enc.encodeToString(nonce) + "." + enc.encodeToString(ciphertext);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Encryption failed", ex);
		}
	}

	/** Decrypts an envelope with the key it names (an old key still works); null stays null. */
	public String decrypt(String envelope, String aad) {
		if (envelope == null) {
			return null;
		}
		String[] parts = envelope.split("\\.");
		if (parts.length != 4 || !VERSION.equals(parts[0])) {
			throw new IllegalStateException("Unknown safeguarding ciphertext format");
		}
		SecretKeySpec key = this.keys.get(parts[1]);
		if (key == null) {
			throw unavailable();
		}
		try {
			Base64.Decoder dec = Base64.getUrlDecoder();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, dec.decode(parts[2])));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(dec.decode(parts[3])), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Safeguarding ciphertext could not be authenticated", ex);
		}
	}

	/** The key id an envelope names (for tests and rotation reports); null for null. */
	public static String keyIdOf(String envelope) {
		if (envelope == null) {
			return null;
		}
		String[] parts = envelope.split("\\.");
		return (parts.length == 4) ? parts[1] : null;
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "safeguarding.encryption_unavailable");
	}

}
