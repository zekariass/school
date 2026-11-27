package com.nemaz.school.attendance.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.nemaz.school.common.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The keyed hashing of the QR check-in (M7e). Two separate uses, two separate secrets:
 * <ul>
 * <li><b>Credential tokens</b> (the static QR of a card): {@code HMAC-SHA256(key, "attendance-credential:" + token)} as hex,
 * with the key named by {@code keyId} (stored next to the hash). The token has 128 random bits, so the pepper is a second
 * line of defence (a database dump alone does not reveal a usable token). Verification computes the hash under every
 * configured key, so the old key keeps working while the new one is active; a credential found under an older key is
 * re-hashed under the active key on its first successful scan ({@code CredentialService}).</li>
 * <li><b>Rotating codes</b>: {@code HMAC-SHA256(qrKey, "attendance-qr:" + qrSessionId + ":" + window)}, truncated to 128 bits,
 * base64url. {@code window = floor(epochSecond / rotationSeconds)}. No secret is stored per session.</li>
 * </ul>
 * The key material never leaves this class: no accessor returns a key, and nothing here logs a token, a code or a hash.
 */
@Component
public class AttendanceKeys {

	static final String CREDENTIAL_PURPOSE = "attendance-credential:";

	static final String QR_PURPOSE = "attendance-qr:";

	private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

	private static final String HMAC = "HmacSHA256";

	private static final int CODE_BYTES = 16;

	private final Map<String, SecretKeySpec> keys;

	private final String activeKeyId;

	private final SecretKeySpec qrKey;

	AttendanceKeys(AttendanceSecretsProperties properties) {
		this.keys = parse(properties.keys());
		String active = (properties.activeKeyId() == null) ? null : properties.activeKeyId().trim();
		this.activeKeyId = (active != null && this.keys.containsKey(active)) ? active : null;
		this.qrKey = parseKey("QR key", properties.qrKey());
	}

	/** Blank values (an unset rotation slot) are ignored; a malformed key is a startup error. */
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
				throw new IllegalStateException("An attendance credential key id is not valid (letters, digits, _ and -, 1-32)");
			}
			keys.put(id, parseKey("credential key '" + id + "'", value));
		});
		return keys;
	}

	private static SecretKeySpec parseKey(String what, String value) {
		if (value == null || value.isBlank() || value.contains("${")) {
			return null;
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(value.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("The attendance " + what + " is not valid base64");
		}
		if (raw.length != 32) {
			throw new IllegalStateException("The attendance " + what + " must be 32 bytes");
		}
		return new SecretKeySpec(raw, HMAC);
	}

	/** True when credentials can be issued and verified. */
	public boolean credentialsAvailable() {
		return this.activeKeyId != null;
	}

	/** True when rotating codes can be made and checked. */
	public boolean codesAvailable() {
		return this.qrKey != null;
	}

	String activeKeyId() {
		requireCredentials();
		return this.activeKeyId;
	}

	/** The hash of a token under the active key. */
	String hash(String token) {
		requireCredentials();
		return hex(mac(this.keys.get(this.activeKeyId), CREDENTIAL_PURPOSE + token));
	}

	/** The hash of a token under every configured key (key id to hash): the candidates a stored hash may equal. */
	Map<String, String> candidateHashes(String token) {
		requireCredentials();
		Map<String, String> hashes = new LinkedHashMap<>();
		this.keys.forEach((id, key) -> hashes.put(id, hex(mac(key, CREDENTIAL_PURPOSE + token))));
		return hashes;
	}

	/** The code shown for the session in the window (base64url, 22 characters). */
	String code(UUID qrSessionId, long window) {
		if (this.qrKey == null) {
			throw unavailable();
		}
		byte[] full = mac(this.qrKey, QR_PURPOSE + qrSessionId + ":" + window);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, CODE_BYTES));
	}

	/** Does the submitted code equal the session's code of one of the windows? Every comparison runs in constant time. */
	boolean matches(UUID qrSessionId, List<Long> windows, String submitted) {
		if (submitted == null) {
			return false;
		}
		byte[] given = submitted.getBytes(StandardCharsets.UTF_8);
		boolean ok = false;
		for (long window : windows) {
			byte[] expected = code(qrSessionId, window).getBytes(StandardCharsets.UTF_8);
			ok |= MessageDigest.isEqual(expected, given);
		}
		return ok;
	}

	private void requireCredentials() {
		if (this.activeKeyId == null) {
			throw unavailable();
		}
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "attendance.secrets_unavailable");
	}

	private static byte[] mac(SecretKeySpec key, String value) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(key);
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

}
