package com.nemaz.school.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * One-time codes: 6 random digits ({@link SecureRandom}); stored as
 * {@code HMAC-SHA256(pepper, challengeId + ":" + code)}, compared in constant time. The
 * pepper is required (at least 32 characters), so a leaked database alone does not reveal
 * codes even though the code space is small.
 */
@Component
public class OtpCodes implements com.nemaz.school.identity.CodeHasher {

	static final int MIN_PEPPER_LENGTH = 32;

	private static final String HMAC = "HmacSHA256";

	private final SecureRandom random = new SecureRandom();

	private final SecretKeySpec key;

	OtpCodes(IdentityProperties props) {
		String pepper = props.otp().pepper();
		if (pepper == null || pepper.startsWith("${") || pepper.length() < MIN_PEPPER_LENGTH) {
			throw new IllegalStateException("school.auth.otp.pepper (SCHOOL_AUTH_OTP_PEPPER) must be set to a secret of at "
					+ "least " + MIN_PEPPER_LENGTH + " characters");
		}
		this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC);
	}

	/** A uniformly random 6-digit code (leading zeros kept). */
	public String newCode() {
		return String.format("%06d", this.random.nextInt(1_000_000));
	}

	public String hash(UUID challengeId, String code) {
		return HexFormat.of().formatHex(mac(challengeId + ":" + code));
	}

	@Override
	public String hmac(String purpose, String value) {
		return HexFormat.of().formatHex(mac(purpose + ":" + value));
	}

	/** Constant-time comparison of a submitted code with a stored hash. */
	public boolean matches(UUID challengeId, String code, String storedHash) {
		byte[] expected;
		try {
			expected = HexFormat.of().parseHex(storedHash);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		return MessageDigest.isEqual(expected, mac(challengeId + ":" + code));
	}

	/** Same work as a real check, for requests about unknown destinations (equal timing). */
	public void dummyCheck(String code) {
		mac("00000000-0000-0000-0000-000000000000:" + code);
	}

	private byte[] mac(String input) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(this.key);
			return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
