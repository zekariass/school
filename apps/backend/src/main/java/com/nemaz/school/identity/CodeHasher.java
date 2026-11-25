package com.nemaz.school.identity;

/**
 * Keyed hashing of secrets that are handed out once and verified later (student login codes), with the
 * same pepper as one-time sign-in codes ({@code school.auth.otp.pepper}). A database dump alone does not
 * reveal them. {@code purpose} separates uses of the key.
 */
public interface CodeHasher {

	/** Lower-case hex of {@code HMAC-SHA256(pepper, purpose + ":" + value)}. */
	String hmac(String purpose, String value);

}
