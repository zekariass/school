package com.nemaz.school.tenancy;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * An extension point of {@link SettingsService}: any module can register a bean to refuse a settings change that its own
 * rules forbid, without the tenancy module knowing about it (the safeguarding module refuses to switch on student
 * messaging in a school that has no safeguarding lead). Every guard runs inside {@code set} and {@code clear}, after the
 * value was validated and before anything is written; it refuses by throwing an {@code ApiException} (a stable code, with its
 * message in both languages), which rolls the change back. A guard must be fast, side-effect free and must not call back into
 * {@link SettingsService#set}.
 */
public interface SettingChangeGuard {

	void check(Change change);

	/**
	 * The change about to be made. {@code effectiveBefore} and {@code effectiveAfter} are what {@code key} resolves to for the
	 * scope itself before and after the change (after a {@code clear}: the value inherited from the next level up), so a guard
	 * can tell "turned on from off" without re-implementing precedence.
	 */
	record Change(String key, SettingScope scope, JsonNode effectiveBefore, JsonNode effectiveAfter, UUID actorId) {
	}

}
