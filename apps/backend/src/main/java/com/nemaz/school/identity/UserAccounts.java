package com.nemaz.school.identity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Account operations other modules may need when they create people on behalf of a school. The acting
 * administrator's authority is checked by the caller; this interface only guards invariants of accounts.
 * Joins the caller's transaction. Audit rows are written by the caller (it knows the school and the
 * actor's intent), except where noted.
 */
public interface UserAccounts {

	/**
	 * Finds the user by an exact normalized phone or email, or creates an ACTIVE STANDARD account whose
	 * destination counts as verified (the administrator vouches for it, as for {@code POST /platform/users}).
	 * When both are given and name different users, or the match is not ACTIVE, it is a 409. Never creates
	 * duplicates.
	 */
	ProvisionedUser findOrCreateVerified(ProvisionRequest request);

	/** A MANAGED, ACTIVE account without phone or email (R-31). {@code username} is optional and globally unique. */
	UUID createManaged(ManagedRequest request);

	/** Changes a MANAGED account's names, birth date, gender and username (absent = unchanged, blank username = clear). */
	void updateManaged(UUID userId, ManagedChange change);

	/** Sets a password that must be changed ({@code must_change}); other sessions of the account end. MANAGED accounts only. */
	void setInitialPassword(UUID userId, String password);

	/** Signs the account out everywhere (reason USER_INACTIVE), e.g. after a student's last enrollment ended. */
	void signOutEverywhere(UUID userId);

	/**
	 * Platform lock (M13, docs/Safeguarding_Rules.md S2.4): the ACTIVE account becomes LOCKED, every session ends through the
	 * token service (access tokens deny-listed, {@code SessionsRevoked} closes sockets and push devices) and nothing is deleted.
	 * Returns false when the account was not ACTIVE (nothing changed). The caller checks authority and audits.
	 */
	boolean lock(UUID userId);

	/** Reverses {@link #lock}: a LOCKED account becomes ACTIVE (it signs in again). False when it was not LOCKED. */
	boolean unlock(UUID userId);

	record ProvisionRequest(String phone, String email, String country, String givenName, String familyName,
			String displayName, String preferredLanguageCode) {
	}

	record ProvisionedUser(UUID id, boolean created, String displayName, String preferredLanguageCode, String phoneE164,
			String email) {
	}

	record ManagedRequest(String givenName, String middleName, String familyName, LocalDate dateOfBirth, String gender,
			String username, String preferredLanguageCode) {
	}

	record ManagedChange(String givenName, String middleName, String familyName, LocalDate dateOfBirth, String gender,
			String username) {
	}

}
