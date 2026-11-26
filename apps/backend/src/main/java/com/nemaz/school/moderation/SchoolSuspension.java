package com.nemaz.school.moderation;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by the modules that own memberships (people: staff and students; guardians: guardian links) so that a
 * school-level SUSPEND_USER ends the person's activity in that school and can be undone. Reversible, deletes nothing. Each
 * implementation touches only what it owns and reports the ids it changed so that the lift restores exactly those.
 */
public interface SchoolSuspension {

	/** Suspends what this module holds of the user at the school; an empty result when the user has nothing here. */
	Result suspend(UUID userId, UUID schoolId);

	/** Reverses {@link #suspend}; {@code affected} are the ids the suspend reported. */
	void lift(UUID userId, UUID schoolId, List<UUID> affected);

	record Result(boolean applied, List<UUID> affected) {

		public Result {
			affected = List.copyOf(affected);
		}

		public static Result nothing() {
			return new Result(false, List.of());
		}

	}

}
