package com.nemaz.school.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's password (argon2id) and its lockout state. */
@Entity
@Table(name = "password_credentials")
public class PasswordCredential {

	@Id
	private UUID userId;

	private String passwordHash;

	private boolean mustChange;

	private Instant changedAt;

	private short failedAttempts;

	private Instant lockedUntil;

	protected PasswordCredential() {
	}

	public PasswordCredential(UUID userId, String passwordHash, Instant changedAt) {
		this.userId = userId;
		this.passwordHash = passwordHash;
		this.changedAt = changedAt;
	}

	/** A password an administrator chose for someone else: the owner is expected to change it. */
	public static PasswordCredential initial(UUID userId, String passwordHash, Instant changedAt) {
		PasswordCredential credential = new PasswordCredential(userId, passwordHash, changedAt);
		credential.mustChange = true;
		return credential;
	}

	/** Replaces the password with one an administrator chose; {@code must_change} stays set until the owner changes it. */
	public void resetByAdmin(String passwordHash, Instant at) {
		this.passwordHash = passwordHash;
		this.changedAt = at;
		this.mustChange = true;
		recordSuccess();
	}

	public boolean isLocked(Instant now) {
		return this.lockedUntil != null && this.lockedUntil.isAfter(now);
	}

	/** Counts a failure; at {@code maxFailures} consecutive failures locks until {@code lockUntil}. Returns true when it locked. */
	public boolean recordFailure(int maxFailures, Instant lockUntil) {
		this.failedAttempts++;
		if (this.failedAttempts >= maxFailures) {
			this.failedAttempts = 0;
			this.lockedUntil = lockUntil;
			return true;
		}
		return false;
	}

	public void recordSuccess() {
		this.failedAttempts = 0;
		this.lockedUntil = null;
	}

	public void change(String passwordHash, Instant at) {
		this.passwordHash = passwordHash;
		this.changedAt = at;
		this.mustChange = false;
		recordSuccess();
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public boolean isMustChange() {
		return this.mustChange;
	}

	public short getFailedAttempts() {
		return this.failedAttempts;
	}

	public Instant getLockedUntil() {
		return this.lockedUntil;
	}

}
