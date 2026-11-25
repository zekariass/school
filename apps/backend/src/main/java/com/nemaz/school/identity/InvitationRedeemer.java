package com.nemaz.school.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by the module that owns invitations (guardians, M5). {@code POST /invitations/otp} and
 * {@code /invitations/accept} are sign-in flows, so identity hosts them: it proves the adult's phone or email with a
 * one-time code, finds or creates the account and signs it in, and hands the invitation part to this port.
 * <p>
 * Every failure (unknown, expired, revoked, used-up invitation) is the same empty answer, so callers can only ever
 * give one uniform error ({@code invitation.not_found}).
 */
public interface InvitationRedeemer {

	/**
	 * The live invitation named by a link token or a printed code (exactly one is set), or empty. Applies the
	 * guessing limits of invitation keys (a 429 {@code auth.rate_limited} once too many unknown keys came from one
	 * address or for one code prefix). No other side effects.
	 */
	Optional<InvitationTarget> find(InvitationKey key);

	/**
	 * Accepts the invitation for {@code userId} in the caller's transaction (locks it and re-checks that it is live).
	 * {@code proof} is the phone or email the user has just proven. Empty when the invitation is no longer live.
	 */
	Optional<UUID> redeem(InvitationKey key, UUID userId, VerifiedContact proof);

	/** A link token or a printed code, as submitted (exactly one is non-blank for a well-formed request). */
	record InvitationKey(String token, String code) {

		@Override
		public String toString() {
			return "InvitationKey[redacted]";
		}

	}

	/** {@code suggestedName}: the name the inviter typed (for a new account), may be null; {@code languageCode}: the school's. */
	record InvitationTarget(UUID invitationId, UUID schoolId, String suggestedName, String languageCode) {
	}

	/** A destination the user has just proven control of (one of the two is set). */
	record VerifiedContact(String phoneE164, String email) {

		@Override
		public String toString() {
			return "VerifiedContact[redacted]";
		}

	}

}
