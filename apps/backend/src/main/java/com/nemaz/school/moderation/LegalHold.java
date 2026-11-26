package com.nemaz.school.moderation;

import java.util.UUID;

/**
 * The question other modules ask before erasing or exporting a person's data (docs/Safeguarding_Rules.md S6.3): is any of it
 * under a legal hold on a moderation case or a safeguarding incident? The data-subject erasure and export workflows are not
 * built yet; they must call {@link #requireNotHeld} first and escalate to a human when it refuses.
 */
public interface LegalHold {

	/** True when the user is the subject or a reporter of a held case, or a party (concerned, involved, raiser) of a held incident. */
	boolean isHeld(UUID userId);

	/** Throws 409 {@code legal_hold.erasure_refused} when {@link #isHeld}: held data is kept and a human decides. */
	void requireNotHeld(UUID userId);

}
